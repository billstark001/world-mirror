package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.MirrorSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.PendingImport;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.format.WorldGraphLease;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/** Publishes the world graph and manages the current connection's direct download target. */
public final class WorldGraphService {
    private static final class ClientService {
        static final WorldGraphService INSTANCE =
                new WorldGraphService(FabricLoader.getInstance().getGameDir(),
                        FabricLoader.getInstance().getConfigDir().resolve("worldmirror"), true);
    }

    public record Source(String nodeId, String label, String endpoint) {}
    public record
            RoutingView(Snapshot graph, WorldGraphConfig.Snapshot config, List<Location> untrackedLocalWorlds,
                    List<Location> orphanedMirrors, Map<String, Target> downloadTargets) {
        public WorldTargetSelection.Decision targetSelection(String sourceId) {
            return WorldTargetSelection.resolve(graph, config, downloadTargets, sourceId, null, false);
        }
    }
    public record Target(String nodeId, String parentNodeId, String sourceId, Path folder) {
        public Target {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(parentNodeId);
            Objects.requireNonNull(sourceId);
            folder = folder.toAbsolutePath().normalize();
        }
    }
    record MoveResult(Path source, Path target) {}
    record Published(Snapshot graph, WorldGraphConfig.Snapshot config, Map<String, Target> targets,
            List<Location> untrackedLocalWorlds, List<Location> orphanedMirrors, boolean available) {}
    private final WorldInventoryFolders folders;
    private final WorldGraphInventory inventory;
    private final Path configDirectory;
    private final WorldConnectionRouting routing;
    private final WorldGraphWorlds worlds;
    private final LegacyLocalRouteAdoption localRoutes;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final boolean clientService;
    private final java.util.concurrent.Executor worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WM-WorldGraph");
        thread.setDaemon(true);
        return thread;
    });
    private WorldGraphConfig store;
    private volatile Published published = new Published(WorldGraph.reconcile(List.of(), Map.of()),
            WorldGraphConfig.Snapshot.empty(), Map.of(), List.of(), List.of(), false);
    WorldGraphService(Path game, Path configDirectory) {
        this(game, configDirectory, false);
    }

    private WorldGraphService(Path game, Path configDirectory, boolean clientService) {
        this.clientService = clientService;
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        folders = new WorldInventoryFolders(
                game.toAbsolutePath().normalize(), () -> ModConfig.get().defaultSaveLocation);
        inventory = new WorldGraphInventory(
                folders, this.configDirectory, () -> ModConfig.get().defaultSaveLocation);
        routing = new WorldConnectionRouting(this, folders);
        worlds = new WorldGraphWorlds(this, folders);
        localRoutes = new LegacyLocalRouteAdoption(this, folders);
    }

    public static WorldGraphService instance() {
        return ClientService.INSTANCE;
    }

    public WorldConnectionRouting routing() {
        return routing;
    }

    public WorldGraphWorlds worlds() {
        return worlds;
    }

    public LegacyLocalRouteAdoption localRoutes() {
        return localRoutes;
    }

    Path configDirectory() {
        return configDirectory;
    }

    public void refreshAsync() {
        requested.set(true);
        if (!queued.compareAndSet(false, true))
            return;
        worker.execute(() -> {
            try {
                while (requested.getAndSet(false)) {
                    try {
                        refresh();
                    } catch (IOException | RuntimeException failure) {
                        WMLogger.warnRateLimited(
                                "world-graph", 30_000, "World graph refresh failed", failure);
                    }
                }
            } finally {
                queued.set(false);
                if (requested.get())
                    refreshAsync();
            }
        });
    }

    Published published() {
        return published;
    }

    boolean clientService() {
        return clientService;
    }

    public boolean available() {
        return published.available();
    }
    public Snapshot graph() {
        return published.graph();
    }
    public RoutingView routingView() {
        Published view = published;
        return new RoutingView(view.graph(), view.config(), view.untrackedLocalWorlds(),
                view.orphanedMirrors(), view.available() ? view.targets() : Map.of());
    }
    public WorldGraphConfig.Snapshot configSnapshot() {
        return published.config();
    }
    public Target targetById(String id) {
        Published view = published;
        return id == null || !view.available() ? null : view.targets().get(id);
    }
    public List<Target> targetsForSource(String id) {
        Published view = published;
        return id == null || !view.available() ? List.of()
                                               : view.graph()
                                                         .childrenOf(id)
                                                         .stream()
                                                         .map(node -> view.targets().get(node.id()))
                                                         .filter(Objects::nonNull)
                                                         .toList();
    }

    public Target target(Source observation) {
        Published view = published;
        return view.available()
                ? chooseTarget(view, routing.sourceNode(observation, view.config()), null, false).target()
                : null;
    }

    static WorldTargetSelection.Decision chooseTarget(
            Published view, String sourceId, String choice, boolean createMirror) {
        return WorldTargetSelection.resolve(
                view.graph(), view.config(), view.targets(), sourceId, choice, createMirror);
    }

    public Path preview(Source observation) {
        Published view = published;
        if (observation == null || !view.available())
            return null;
        String sourceId = routing.sourceNode(observation, view.config());
        if (sourceId == null) {
            return observation == routing.source() && observation.endpoint() == null
                            && observation.nodeId() == null
                    ? anticipatedPath(SourceSettings.EMPTY, observation.label())
                    : null;
        }
        var decision = chooseTarget(view, sourceId, null, false);
        if (decision.target() != null)
            return decision.target().folder();
        if (decision.state() != WorldTargetSelection.State.CREATE)
            return null;
        return anticipatedPath(settings(view.config().sourceSettings(), sourceId), observation.label());
    }

    private Path anticipatedPath(SourceSettings settings, String label) {
        return creationRoot(settings).resolve(creationName(settings, label));
    }

    Path creationRoot(SourceSettings settings) {
        return rootFor(settings.saveLocation() == null ? ModConfig.get().defaultSaveLocation
                                                       : settings.saveLocation());
    }

    static String creationName(SourceSettings settings, String label) {
        return settings.preferredFolderName() == null ? MirrorFolders.generate(label)
                                                      : settings.preferredFolderName();
    }

    public ModConfig.SaveLocation saveLocation(String sourceNodeId) {
        SourceSettings settings =
                sourceNodeId == null ? null : published.config().sourceSettings().get(sourceNodeId);
        return settings == null || settings.saveLocation() == null ? ModConfig.get().defaultSaveLocation
                                                                   : settings.saveLocation();
    }

    public ModConfig.ConflictStrategy conflictStrategy(String nodeId, String parentId) {
        WorldGraphConfig.Snapshot config = published.config();
        MirrorSettings mirror = nodeId == null ? null : config.mirrorSettings().get(nodeId);
        if (mirror != null && mirror.conflictStrategy() != null)
            return mirror.conflictStrategy();
        SourceSettings settings = parentId == null ? null : config.sourceSettings().get(parentId);
        return settings != null && settings.conflictStrategy() != null
                ? settings.conflictStrategy()
                : ModConfig.get().defaultConflictStrategy;
    }

    public synchronized void setConflictStrategy(
            String nodeId, String parentId, ModConfig.ConflictStrategy strategy) throws IOException {
        try (var ignored = acquireGraphLease()) {
            saveConflictStrategy(nodeId, parentId, strategy);
        }
    }

    private void saveConflictStrategy(String nodeId, String parentId, ModConfig.ConflictStrategy strategy)
            throws IOException {
        WorldGraphConfig.Snapshot before = currentStore().snapshot();
        Map<String, MirrorSettings> mirrors = new HashMap<>(before.mirrorSettings());
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        if (nodeId != null) {
            if (strategy == null)
                mirrors.remove(nodeId);
            else
                mirrors.put(nodeId, new MirrorSettings(strategy));
        } else if (parentId != null) {
            SourceSettings old = settings(sources, parentId);
            sources.put(parentId, old.withConflictStrategy(strategy));
        } else
            throw new IOException("No source selected");
        publishConfig(
                before, before.nodes(), sources, mirrors, before.endpointRules(), before.pendingImports());
        if (nodeId != null ? !before.mirrorSettings().equals(mirrors)
                           : !before.sourceSettings().equals(sources))
            WMLogger.info("World conflict policy saved scope=" + (nodeId == null ? "source" : "mirror")
                    + " strategy=" + (strategy == null ? "inherit" : strategy));
    }

    public synchronized void setSourceSaveLocation(String sourceId, ModConfig.SaveLocation location)
            throws IOException {
        if (!WorldGraph.isNodeId(sourceId))
            throw new IOException("No source selected");
        try (var ignored = acquireGraphLease()) {
            saveLocationPreference(sourceId, location);
        }
    }

    private void saveLocationPreference(String sourceId, ModConfig.SaveLocation location) throws IOException {
        WorldGraphConfig.Snapshot before = currentStore().snapshot();
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        SourceSettings old = settings(sources, sourceId);
        sources.put(sourceId, old.withSaveLocation(location));
        publishConfig(before, before.nodes(), sources, before.mirrorSettings(), before.endpointRules(),
                before.pendingImports());
        if (!before.sourceSettings().equals(sources))
            WMLogger.info("Default download location saved location=" + location);
    }

    synchronized Target prepare(Source observation) throws IOException {
        return prepare(observation, () -> true);
    }

    synchronized Target prepare(Source observation, BooleanSupplier stillCurrent) throws IOException {
        return prepare(observation, null, false, stillCurrent);
    }

    synchronized Target prepare(Source observation, String mirrorChoice, boolean createMirror,
            BooleanSupplier stillCurrent) throws IOException {
        try (var ignored = acquireGraphLease()) {
            refreshPublished();
            if (!stillCurrent.getAsBoolean())
                throw new IOException("Connection changed during mirror preparation");
            return WorldGraphTargetPreparation.prepare(
                    this, routing.routeFor(observation), mirrorChoice, createMirror, stillCurrent);
        }
    }

    public synchronized void editRouting(UnaryOperator<WorldGraphConfig.Snapshot> edit) throws IOException {
        if (clientService && DownloadManager.hasRoutingWork())
            throw new IOException("Stop capture and wait for pending exports before editing routes");
        try (var ignored = acquireGraphLease()) {
            var before = currentStore().snapshot();
            var next = Objects.requireNonNull(edit.apply(before));
            if (!before.nodes().equals(next.nodes()) || !before.locations().equals(next.locations())
                    || !before.pendingImports().equals(next.pendingImports()))
                throw new IOException("Routing edits cannot modify the on-disk graph");
            for (var entry : next.sourceSettings().entrySet()) {
                var old = before.sourceSettings().get(entry.getKey());
                String id = entry.getValue().pinnedMirrorId();
                if (id != null && (old == null || !id.equals(old.pinnedMirrorId()))
                        && chooseTarget(published, entry.getKey(), id, false).target() == null)
                    throw new IOException("Default mirror must be an available direct download target");
            }
            replaceConfig(before, next);
            if (!next.equals(before)) {
                WMLogger.info("World routing settings saved endpointsChanged="
                        + !before.endpointRules().equals(next.endpointRules())
                        + " stableBindingsChanged=" + !before.stableBindings().equals(next.stableBindings())
                        + " sourceSettingsChanged=" + !before.sourceSettings().equals(next.sourceSettings())
                        + " mirrorSettingsChanged=" + !before.mirrorSettings().equals(next.mirrorSettings()));
            }
        }
        if (clientService)
            refreshAsync();
    }

    public Target currentTarget(Target target) throws IOException {
        Published view = published;
        Target current = view.available() ? view.targets().get(target.nodeId()) : null;
        if (current == null || !Objects.equals(current.parentNodeId(), target.parentNodeId())
                || !current.sourceId().equals(target.sourceId())) {
            throw new IOException("Mirror target identity changed: " + target.nodeId());
        }
        return current;
    }

    public synchronized void refresh() throws IOException {
        try (var ignored = acquireGraphLease()) {
            refreshPublished();
        }
    }

    FileChannel acquireGraphLease() throws IOException {
        FileProof.requirePlainDirectory(configDirectory);
        Files.createDirectories(configDirectory);
        WorldInventoryFolders.requirePlainRoot(configDirectory);
        return WorldGraphLease.acquire(configDirectory);
    }

    void refreshPublished() throws IOException {
        try {
            boolean wasAvailable = published.available();
            WorldGraphConfig config = currentStore();
            localRoutes.recover();
            WorldGraphInventory.Result result = inventory.refresh(config);
            publishInventory(result, config.snapshot());
            if (!wasAvailable)
                WMLogger.info("World graph available worlds=" + published.graph().nodes().size()
                        + " downloadTargets=" + published.targets().size());
        } catch (IOException | RuntimeException failure) {
            invalidatePublication();
            throw failure;
        }
    }

    private void publishInventory(WorldGraphInventory.Result result, WorldGraphConfig.Snapshot config) {
        Snapshot previous = published.graph();
        Snapshot graph = result.graph();
        Map<String, Target> targets = new HashMap<>();
        result.indexSources().forEach((id, indexSource) -> {
            Node node = graph.nodes().get(id);
            Location location = node.location();
            Path folder = folders.root(location.root()).resolve(location.folder());
            targets.put(id, new Target(id, node.parentId(), indexSource, folder));
        });
        published = new Published(graph, config, Map.copyOf(targets), result.untrackedLocalWorlds(),
                result.orphanedMirrors(), true);
        if (!previous.cyclicIds().equals(graph.cyclicIds()) && !graph.cyclicIds().isEmpty())
            WMLogger.warn("World graph has cyclic upstream links worlds=" + graph.cyclicIds().size());
        if (!previous.missingParents().equals(graph.missingParents()))
            WMLogger.debug("World graph missing upstreams=" + graph.missingParents().size());
    }

    WorldGraphConfig open() throws IOException {
        if (store == null)
            store = WorldGraphConfig.open(configDirectory.resolve(WorldGraphConfig.FILE_NAME));
        return store;
    }

    WorldGraphConfig currentStore() throws IOException {
        if (store != null && !store.isCurrent())
            store = null;
        return open();
    }

    private void invalidatePublication() {
        store = null;
        Published view = published;
        published = new Published(view.graph(), view.config(), view.targets(), view.untrackedLocalWorlds(),
                view.orphanedMirrors(), false);
    }

    void publishConfig(WorldGraphConfig.Snapshot before, Map<String, Node> nodes,
            Map<String, SourceSettings> sources, Map<String, MirrorSettings> mirrors,
            Map<String, EndpointRule> rules, Map<String, PendingImport> pending) throws IOException {
        publishConfig(before, nodes, before.locations(), sources, mirrors, rules, pending);
    }

    void publishConfig(WorldGraphConfig.Snapshot before, Map<String, Node> nodes,
            Map<String, Location> locations, Map<String, SourceSettings> sources,
            Map<String, MirrorSettings> mirrors, Map<String, EndpointRule> rules,
            Map<String, PendingImport> pending) throws IOException {
        var next = new WorldGraphConfig.Snapshot(WorldGraphConfig.SCHEMA, nodes, locations, sources, mirrors,
                rules, before.stableBindings(), pending);
        replaceConfig(before, next);
    }

    void replaceConfig(WorldGraphConfig.Snapshot before, WorldGraphConfig.Snapshot next) throws IOException {
        try {
            if (!open().replace(before, next))
                throw new IOException("World graph changed before publication");
        } catch (IOException failure) {
            invalidatePublication();
            throw failure;
        }
        Published view = published;
        published = new Published(view.graph(), next, view.targets(), view.untrackedLocalWorlds(),
                view.orphanedMirrors(), view.available());
    }

    static SourceSettings settings(Map<String, SourceSettings> sources, String id) {
        return sources.getOrDefault(id, SourceSettings.EMPTY);
    }

    Path rootFor(ModConfig.SaveLocation location) {
        return folders.root(Root.valueOf(location.name()));
    }
}
