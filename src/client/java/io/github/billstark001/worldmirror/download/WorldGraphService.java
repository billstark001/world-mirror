package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.MirrorSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.PendingImport;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Publishes the world graph and manages the current connection's direct download target. */
public final class WorldGraphService {
    private static final String GRAPH_LOCK = ".world-graph.lock";

    private static final class ClientService {
        static final WorldGraphService INSTANCE =
                new WorldGraphService(FabricLoader.getInstance().getGameDir(),
                        FabricLoader.getInstance().getConfigDir().resolve("worldmirror"));
    }

    public record Source(String nodeId, String label, String endpoint) {}
    public record Target(String nodeId, String parentNodeId, String sourceId, Path folder) {
        public Target {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(parentNodeId);
            Objects.requireNonNull(sourceId);
            folder = folder.toAbsolutePath().normalize();
        }
    }
    record MoveResult(Path source, Path target) {}
    private record Published(Snapshot graph, WorldGraphConfig.Snapshot config, Map<String, Target> targets) {}

    private final WorldGraphInventory inventory;
    private final Path configDirectory;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final java.util.concurrent.Executor worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WM-WorldGraph");
        thread.setDaemon(true);
        return thread;
    });
    private WorldGraphConfig store;
    private volatile Published published = new Published(
            WorldGraph.reconcile(List.of(), Map.of()), WorldGraphConfig.Snapshot.empty(), Map.of());
    private volatile boolean available;
    private volatile Source source;
    private String observedAddress;

    WorldGraphService(Path game, Path configDirectory) {
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        inventory = new WorldGraphInventory(game.toAbsolutePath().normalize(), this.configDirectory,
                () -> ModConfig.get().defaultSaveLocation);
    }

    public static WorldGraphService instance() {
        return ClientService.INSTANCE;
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

    public boolean available() {
        return available;
    }
    public Snapshot graph() {
        return published.graph();
    }
    public Source source() {
        return source;
    }

    /** Checks game-thread capture against the observation that owns the current session. */
    public boolean observationIsCurrent(Minecraft client) {
        Source current = source;
        if (current == null)
            return false;
        return client.getSingleplayerServer() != null
                ? current.endpoint() == null
                : current.endpoint() != null && Objects.equals(observedAddress, currentAddress(client));
    }

    public void enter(Minecraft client) {
        try {
            if (client.getSingleplayerServer() != null) {
                Path folder = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
                var metadata = WorldMetadata.loadIfPresent(folder);
                String id = metadata.map(value -> value.nodeId).orElse(null);
                if (id == null && WorldMetadata.inspectSchema(folder) == WorldMetadata.Schema.MISSING) {
                    id = LocalWorldIdentity.ensure(folder);
                }
                source = id == null
                        ? null
                        : new Source(id, client.getSingleplayerServer().getWorldData().getLevelName(), null);
            } else {
                source = observeAddress(client);
            }
        } catch (IOException | IllegalArgumentException failure) {
            source = null;
            WMLogger.warnRateLimited(
                    "world-graph-source", 30_000, "Could not identify the open world", failure);
        }
        refreshAsync();
    }

    public void leave() {
        source = null;
        observedAddress = null;
        refreshAsync();
    }

    /** A connection event supplies local identity. Remote address changes require no filesystem access. */
    boolean updateRemoteObservation(Minecraft client) {
        Source current = source;
        if (client.getSingleplayerServer() != null || (current != null && current.endpoint() == null))
            return false;
        String address = currentAddress(client);
        if (Objects.equals(observedAddress, address))
            return false;
        Source observed = observeAddress(client);
        if (Objects.equals(current, observed))
            return false;
        source = observed;
        return true;
    }

    private static String currentAddress(Minecraft client) {
        var server = client.getCurrentServer();
        return server == null ? null : server.ip;
    }

    private Source observeAddress(Minecraft client) {
        observedAddress = currentAddress(client);
        if (observedAddress == null)
            return null;
        try {
            String endpoint = WorldGraphConfig.normalizeEndpoint(observedAddress);
            return new Source(null, endpoint, endpoint);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public String sourceNode(Source observation) {
        return sourceNode(observation, published.config());
    }

    private static String sourceNode(Source observation, WorldGraphConfig.Snapshot config) {
        if (observation == null)
            return null;
        if (observation.nodeId() != null)
            return observation.nodeId();
        EndpointRule rule = config.endpointRules().get(observation.endpoint());
        if (rule == null)
            return WorldGraphConfig.deterministicId("remote", observation.endpoint());
        if (rule.idPolicy() == IdPolicy.REQUIRE_BOUND_ID)
            return null;
        if (rule.defaultSourceId() != null)
            return rule.defaultSourceId();
        return rule.sourceIds().size() == 1 ? rule.sourceIds().iterator().next() : null;
    }

    public Target target(Source observation) {
        if (!available)
            return null;
        Published view = published;
        String sourceId = sourceNode(observation, view.config());
        if (sourceId == null)
            return null;
        SourceSettings settings = view.config().sourceSettings().get(sourceId);
        return view.graph()
                .defaultMirror(sourceId, settings == null ? null : settings.defaultMirrorId())
                .map(node -> view.targets().get(node.id()))
                .orElse(null);
    }

    public Path preview(Source observation) {
        if (observation == null || !available)
            return null;
        Target target = target(observation);
        if (target != null)
            return target.folder();
        String sourceId = sourceNode(observation);
        if (sourceId == null)
            return null;
        SourceSettings settings = published.config().sourceSettings().get(sourceId);
        return rootFor(settings == null || settings.saveLocation() == null
                        ? ModConfig.get().defaultSaveLocation
                        : settings.saveLocation())
                .resolve(settings == null || settings.preferredFolderName() == null
                                ? MirrorFolders.generate(observation.label())
                                : settings.preferredFolderName());
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
        WorldGraphConfig.Snapshot before = open().snapshot();
        Map<String, MirrorSettings> mirrors = new HashMap<>(before.mirrorSettings());
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        if (nodeId != null) {
            if (strategy == null)
                mirrors.remove(nodeId);
            else
                mirrors.put(nodeId, new MirrorSettings(strategy));
        } else if (parentId != null) {
            SourceSettings old = settings(sources, parentId);
            sources.put(parentId,
                    new SourceSettings(
                            old.defaultMirrorId(), old.saveLocation(), old.preferredFolderName(), strategy));
        } else
            throw new IOException("No source selected");
        publishConfig(
                before, before.nodes(), sources, mirrors, before.endpointRules(), before.pendingImports());
    }

    public synchronized void setSaveLocation(Source observation, ModConfig.SaveLocation location)
            throws IOException {
        try (var ignored = acquireGraphLease()) {
            saveLocationPreference(observation, location);
        }
    }

    private void saveLocationPreference(Source observation, ModConfig.SaveLocation location)
            throws IOException {
        WorldGraphConfig.Snapshot before = open().snapshot();
        String sourceId = sourceNode(observation, before);
        if (sourceId == null)
            throw new IOException("No source selected");
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        SourceSettings old = settings(sources, sourceId);
        sources.put(sourceId,
                new SourceSettings(
                        old.defaultMirrorId(), location, old.preferredFolderName(), old.conflictStrategy()));
        publishConfig(before, before.nodes(), sources, before.mirrorSettings(), before.endpointRules(),
                before.pendingImports());
    }

    synchronized Target prepare(Source observation) throws IOException {
        return prepare(observation, () -> true);
    }

    synchronized Target prepare(Source observation, BooleanSupplier stillCurrent) throws IOException {
        try (var ignored = acquireGraphLease()) {
            refreshPublished();
            if (!stillCurrent.getAsBoolean())
                throw new IOException("Connection changed during mirror preparation");
            return prepareTarget(observation, stillCurrent);
        }
    }

    private Target prepareTarget(Source observation, BooleanSupplier stillCurrent) throws IOException {
        String parent = sourceNode(observation);
        if (parent == null)
            throw new IOException("No unambiguous source selected");
        Snapshot graph = published.graph();
        if (!graph.isUnambiguous(parent)) {
            throw new IOException("Source identity is ambiguous: " + parent);
        }
        if (observation.endpoint() == null && !graph.nodes().containsKey(parent))
            throw new IOException("Local source unavailable: " + parent);
        Target selected = target(observation);
        WorldGraphConfig.Snapshot before = open().snapshot();
        SourceSettings old = settings(before.sourceSettings(), parent);
        if (selected != null) {
            if (old.defaultMirrorId() == null)
                recordDefault(before, parent, selected.nodeId(), observation.endpoint());
            return selected;
        }
        if (old.defaultMirrorId() != null)
            throw new IOException("Default mirror unavailable: " + old.defaultMirrorId());
        if (WorldGraphInventory.hasDeferredSource(before, parent)) {
            throw new IOException("An existing mirror is still awaiting metadata upgrade");
        }
        if (graph.duplicates().stream().anyMatch(copy -> parent.equals(copy.original().parentId()))) {
            throw new IOException("A copied mirror is still being reconciled");
        }
        Path base = rootFor(
                old.saveLocation() == null ? ModConfig.get().defaultSaveLocation : old.saveLocation());
        Files.createDirectories(base);
        WorldGraphInventory.requirePlainRoot(base);
        String name = old.preferredFolderName() == null ? MirrorFolders.generate(observation.label())
                                                        : old.preferredFolderName();
        if (!stillCurrent.getAsBoolean())
            throw new IOException("Connection changed during mirror preparation");
        Path folder = MirrorFolders.claim(base, name);
        String nodeId = "mirror:" + UUID.randomUUID();
        String indexSource = observation.endpoint() == null ? parent : "server:" + observation.endpoint();
        try {
            MirrorClaim.create(folder, nodeId, parent, indexSource);
        } catch (IOException failure) {
            try {
                Files.delete(folder);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        recordDefault(before, parent, nodeId, observation.endpoint());
        refreshPublished();
        return new Target(nodeId, parent, indexSource, folder);
    }

    private void recordDefault(WorldGraphConfig.Snapshot before, String parent, String target,
            String endpoint) throws IOException {
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        SourceSettings old = settings(sources, parent);
        sources.put(parent,
                new SourceSettings(
                        target, old.saveLocation(), old.preferredFolderName(), old.conflictStrategy()));
        Map<String, EndpointRule> rules = new HashMap<>(before.endpointRules());
        if (endpoint != null)
            WorldGraphConfig.addEndpoint(rules, endpoint, parent);
        publishConfig(
                before, before.nodes(), sources, before.mirrorSettings(), rules, before.pendingImports());
    }

    synchronized MoveResult move(Target target, ModConfig.SaveLocation location) throws IOException {
        try (var ignored = acquireGraphLease()) {
            refreshPublished();
            return moveTarget(target, location);
        }
    }

    private MoveResult moveTarget(Target target, ModConfig.SaveLocation location) throws IOException {
        Target current = published.targets().get(target.nodeId());
        if (current == null || !current.parentNodeId().equals(target.parentNodeId())
                || !current.sourceId().equals(target.sourceId()))
            throw new IOException("Mirror unavailable");
        Path base = rootFor(location);
        Files.createDirectories(base);
        WorldGraphInventory.requirePlainRoot(base);
        Path from = current.folder();
        if (from.getParent().equals(base))
            return new MoveResult(from, from);
        Path desired = base.resolve(from.getFileName());
        Path destination = WorldRelocation.isEmptyReservation(desired, current.nodeId())
                ? desired
                : MirrorFolders.available(base, from.getFileName().toString());
        WorldGraphConfig.Snapshot before = open().snapshot();
        WorldRelocation.move(from, destination, current, () -> {
            Node existing = before.nodes().get(current.nodeId());
            if (existing == null)
                return false;
            Map<String, Node> nodes = new HashMap<>(before.nodes());
            nodes.put(existing.id(),
                    new Node(existing.id(), existing.parentId(), inventory.locationOf(destination),
                            existing.kind(), existing.role()));
            Map<String, Location> locations = new HashMap<>(before.locations());
            locations.put(existing.id(), inventory.locationOf(destination));
            try {
                publishConfig(before, nodes, locations, before.sourceSettings(), before.mirrorSettings(),
                        before.endpointRules(), before.pendingImports());
                return true;
            } catch (IOException failure) {
                WMLogger.warn("Could not publish relocated mirror", failure);
                return false;
            }
        });
        refreshPublished();
        return new MoveResult(from, destination);
    }

    public Target currentTarget(Target target) throws IOException {
        Target current = available ? published.targets().get(target.nodeId()) : null;
        if (current == null || !Objects.equals(current.parentNodeId(), target.parentNodeId())
                || !current.sourceId().equals(target.sourceId())) {
            throw new IOException("Mirror target identity changed: " + target.nodeId());
        }
        return current;
    }

    synchronized void refresh() throws IOException {
        try (var ignored = acquireGraphLease()) {
            refreshPublished();
        }
    }

    private FileChannel acquireGraphLease() throws IOException {
        FileProof.requirePlainDirectory(configDirectory);
        Files.createDirectories(configDirectory);
        WorldGraphInventory.requirePlainRoot(configDirectory);
        var channel = FileChannel.open(configDirectory.resolve(GRAPH_LOCK), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lease = channel.tryLock();
            if (lease == null)
                throw new IOException("World graph operation is already running");
            // The channel holds the lease until the entire graph operation closes it.
            return channel;
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException close) {
                failure.addSuppressed(close);
            }
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("World graph operation is already running", failure);
            }
            throw failure;
        }
    }

    private void refreshPublished() throws IOException {
        try {
            if (store != null && !store.isCurrent())
                store = null;
            WorldGraphConfig config = open();
            inventory.refresh(config, result -> publishInventory(result, config.snapshot()));
            available = true;
        } catch (IOException | RuntimeException failure) {
            store = null;
            available = false;
            throw failure;
        }
    }

    private void publishInventory(WorldGraphInventory.Result result, WorldGraphConfig.Snapshot config) {
        Map<String, Target> targets = new HashMap<>();
        result.indexSources().forEach((id, indexSource) -> {
            Node node = result.graph().nodes().get(id);
            Location location = node.location();
            Path folder = inventory.root(location.root()).resolve(location.folder());
            targets.put(id, new Target(id, node.parentId(), indexSource, folder));
        });
        published = new Published(result.graph(), config, Map.copyOf(targets));
    }

    private WorldGraphConfig open() throws IOException {
        if (store == null)
            store = WorldGraphConfig.open(configDirectory.resolve(WorldGraphConfig.FILE_NAME));
        return store;
    }

    private void publishConfig(WorldGraphConfig.Snapshot before, Map<String, Node> nodes,
            Map<String, SourceSettings> sources, Map<String, MirrorSettings> mirrors,
            Map<String, EndpointRule> rules, Map<String, PendingImport> pending) throws IOException {
        publishConfig(before, nodes, before.locations(), sources, mirrors, rules, pending);
    }

    private void publishConfig(WorldGraphConfig.Snapshot before, Map<String, Node> nodes,
            Map<String, Location> locations, Map<String, SourceSettings> sources,
            Map<String, MirrorSettings> mirrors, Map<String, EndpointRule> rules,
            Map<String, PendingImport> pending) throws IOException {
        var next = new WorldGraphConfig.Snapshot(WorldGraphConfig.SCHEMA, nodes, locations, sources, mirrors,
                rules, before.stableBindings(), pending);
        try {
            if (!open().replace(before, next))
                throw new IOException("World graph changed before publication");
        } catch (IOException failure) {
            store = null;
            available = false;
            throw failure;
        }
        Published view = published;
        published = new Published(view.graph(), next, view.targets());
    }

    private static SourceSettings settings(Map<String, SourceSettings> sources, String id) {
        return sources.getOrDefault(id, new SourceSettings(null, null, null, null));
    }

    private Path rootFor(ModConfig.SaveLocation location) {
        return inventory.root(Root.valueOf(location.name()));
    }
}
