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
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ConnectionObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderState;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.Resolution;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.io.NbtReadSupport;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/** Publishes the world graph and manages the current connection's direct download target. */
public final class WorldGraphService {
    private static final String GRAPH_LOCK = ".world-graph.lock";

    private static final class ClientService {
        static final WorldGraphService INSTANCE =
                new WorldGraphService(FabricLoader.getInstance().getGameDir(),
                        FabricLoader.getInstance().getConfigDir().resolve("worldmirror"), true);
    }

    public record Source(String nodeId, String label, String endpoint) {}
    public record RoutingView(
            Snapshot graph, WorldGraphConfig.Snapshot config, List<Location> untrackedLocalWorlds) {}
    public record Target(String nodeId, String parentNodeId, String sourceId, Path folder) {
        public Target {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(parentNodeId);
            Objects.requireNonNull(sourceId);
            folder = folder.toAbsolutePath().normalize();
        }
    }
    record MoveResult(Path source, Path target) {}
    private record Published(Snapshot graph, WorldGraphConfig.Snapshot config, Map<String, Target> targets,
            List<Location> untrackedLocalWorlds) {}
    private record Route(Source source, ConnectionObservation observation, String chosenSource,
            boolean createSource, boolean confirmSwitch) {}

    private final WorldGraphInventory inventory;
    private final Path configDirectory;
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
            WorldGraphConfig.Snapshot.empty(), Map.of(), List.of());
    private volatile boolean available;
    private volatile Route route;
    private volatile ProviderObservation latestProvider = ProviderObservation.pending();
    private ClientPacketListener connection;
    private IntegratedServer integratedServer;
    private CompletableFuture<String> localIdentityRequest;
    private long connectionEpoch;
    private String observedAddress;

    WorldGraphService(Path game, Path configDirectory) {
        this(game, configDirectory, false);
    }

    private WorldGraphService(Path game, Path configDirectory, boolean clientService) {
        this.clientService = clientService;
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
    public RoutingView routingView() {
        Published view = published;
        return new RoutingView(view.graph(), view.config(), view.untrackedLocalWorlds());
    }
    public WorldGraphConfig.Snapshot configSnapshot() {
        return published.config();
    }
    public Source source() {
        Route current = route;
        return current == null ? null : current.source();
    }
    long connectionEpoch() {
        return connectionEpoch;
    }
    ConnectionObservation observation() {
        Route current = route;
        return current == null ? null : current.observation();
    }
    boolean expectsIdentity() {
        var observation = observation();
        if (observation == null || observation.endpoint() == null
                || latestProvider.state() != ProviderState.ADVERTISED)
            return false;
        var rule = published.config().endpointRules().get(observation.endpoint());
        return rule == null || rule.idPolicy() != IdPolicy.ENDPOINT_ONLY;
    }
    public Resolution currentResolution() {
        return resolve(available ? route : null, published.config());
    }
    public Target targetById(String id) {
        return id == null || !available ? null : published.targets().get(id);
    }
    public List<Target> targetsForSource(String id) {
        Published view = published;
        return id == null || !available ? List.of()
                                        : view.graph()
                                                  .childrenOf(id)
                                                  .stream()
                                                  .map(node -> view.targets().get(node.id()))
                                                  .filter(Objects::nonNull)
                                                  .toList();
    }

    /** Checks game-thread capture against the observation that owns the current session. */
    public boolean observationIsCurrent(Minecraft client) {
        Source current = source();
        if (current == null || connection != client.getConnection())
            return false;
        if (current.endpoint() == null)
            return integratedServer != null && integratedServer == client.getSingleplayerServer();
        return client.getSingleplayerServer() == null
                && Objects.equals(observedAddress, currentAddress(client));
    }

    public void enter(Minecraft client) {
        cancelLocalIdentityRequest();
        connection = client.getConnection();
        integratedServer = client.getSingleplayerServer();
        Source source;
        try {
            if (integratedServer != null) {
                Path folder = integratedServer.getWorldPath(LevelResource.ROOT);
                var metadata = WorldMetadata.loadIfPresent(folder);
                String id = metadata.map(value -> value.nodeId).orElse(null);
                boolean original =
                        id == null && WorldMetadata.inspectSchema(folder) == WorldMetadata.Schema.MISSING;
                if (original) {
                    Path checked = activeLocalOriginal(folder);
                    id = LocalWorldIdentity.read(checked);
                }
                source = id != null || original
                        ? new Source(id, integratedServer.getWorldData().getLevelName(), null)
                        : null;
            } else {
                source = observeAddress(client);
            }
        } catch (IOException | IllegalArgumentException failure) {
            source = null;
            WMLogger.warnRateLimited(
                    "world-graph-source", 30_000, "Could not identify the open world", failure);
        }
        beginObservation(source);
        refreshAsync();
    }

    void beginObservation(Source source) {
        connectionEpoch++;
        latestProvider = ProviderObservation.pending();
        route = observe(source);
    }

    public void leave() {
        route = null;
        connection = null;
        integratedServer = null;
        cancelLocalIdentityRequest();
        latestProvider = ProviderObservation.pending();
        observedAddress = null;
        refreshAsync();
    }

    /** An ordinary save can participate before it has a persistent node. */
    boolean needsLocalIdentity(Minecraft client) {
        Source current = source();
        return current != null && current.endpoint() == null && current.nodeId() == null
                && observationIsCurrent(client);
    }

    public boolean canUseLinkedSource(Minecraft client) {
        if (!available || !observationIsCurrent(client))
            return false;
        String id = currentResolution().sourceNodeId();
        return id == null ? needsLocalIdentity(client) : graph().isUnambiguous(id);
    }

    /** Resolves on the client thread. First-use writes run on the integrated server that holds the save lease. */
    public CompletableFuture<String> requireLinkedSource(Minecraft client) {
        if (!client.isSameThread() || !canUseLinkedSource(client))
            return CompletableFuture.failedFuture(new IOException("The active source is unavailable"));
        String id = currentResolution().sourceNodeId();
        if (id != null)
            return CompletableFuture.completedFuture(id);
        if (localIdentityRequest != null)
            return localIdentityRequest;
        var server = integratedServer;
        var original = route;
        var result = new CompletableFuture<String>();
        localIdentityRequest = result;
        Runnable assignIdentity = () -> {
            try {
                if (result.isDone() || route != original || !server.isSameThread() || !server.isRunning()
                        || server.isStopped())
                    throw new IOException("The active world changed before assigning its identity");
                Path folder = activeLocalOriginal(server.getWorldPath(LevelResource.ROOT));
                String assigned = LocalWorldIdentity.ensure(folder);
                client.execute(() -> finishLocalIdentity(client, original, result, assigned, null));
            } catch (IOException | RuntimeException failure) {
                client.execute(() -> finishLocalIdentity(client, original, result, null, failure));
            }
        };
        try {
            server.execute(assignIdentity);
        } catch (RuntimeException failure) {
            finishLocalIdentity(client, original, result, null, failure);
        }
        return result;
    }

    private void finishLocalIdentity(Minecraft client, Route original, CompletableFuture<String> result,
            String id, Exception failure) {
        if (localIdentityRequest != result)
            return;
        localIdentityRequest = null;
        if (failure != null) {
            result.completeExceptionally(failure);
        } else if (route != original || !observationIsCurrent(client)) {
            result.completeExceptionally(new IOException("The active world changed"));
        } else {
            route = observe(new Source(id, original.source().label(), null));
            refreshAsync();
            result.complete(id);
        }
    }

    private void cancelLocalIdentityRequest() {
        var result = localIdentityRequest;
        localIdentityRequest = null;
        if (result != null)
            result.completeExceptionally(new IOException("The active world changed"));
    }

    private Path activeLocalOriginal(Path folder) throws IOException {
        Path normalized = folder.toAbsolutePath().normalize();
        if (!inventory.root(Root.SAVES).equals(normalized.getParent()))
            throw new IOException("The active save is outside the saves directory");
        return inventory.localOriginal(inventory.locationOf(normalized));
    }

    /** A connection event supplies local identity. Remote address changes require no filesystem access. */
    boolean updateRemoteObservation(Minecraft client) {
        Source current = source();
        if (client.getSingleplayerServer() != null || (current != null && current.endpoint() == null))
            return false;
        String address = currentAddress(client);
        if (Objects.equals(observedAddress, address))
            return false;
        Source observed = observeAddress(client);
        if (Objects.equals(current, observed))
            return false;
        latestProvider = ProviderObservation.pending();
        route = observe(observed);
        return true;
    }

    private Route observe(Source source) {
        return source == null
                ? null
                : new Route(source,
                          new ConnectionObservation(connectionEpoch, source.endpoint(), source.nodeId(),
                                  source.endpoint() == null ? ProviderObservation.absent()
                                                            : ProviderObservation.pending()),
                          null, false, false);
    }

    boolean observeProvider(ProviderObservation evidence, boolean late) {
        Route previous = route;
        if (previous == null || previous.source().endpoint() == null)
            return false;
        // Provider state is scoped to the connection, not a particular vanilla dimension.
        if (evidence.state() == ProviderState.PENDING
                || (evidence.state() == ProviderState.ABSENT
                        && latestProvider.state() == ProviderState.ADVERTISED))
            return false;
        latestProvider = evidence;
        if (previous.observation().providerObservation().equals(evidence))
            return false;
        var observation = new ConnectionObservation(
                previous.observation().connectionEpoch(), previous.source().endpoint(), null, evidence);
        EndpointRule rule = published.config().endpointRules().get(previous.source().endpoint());
        boolean ignored = rule != null && rule.idPolicy() == IdPolicy.ENDPOINT_ONLY;
        if (ignored)
            return false;
        var before = resolve(previous, published.config());
        var next = new Route(previous.source(), observation, null, false, false);
        var after = resolve(next, published.config());
        boolean sameSource =
                before.sourceNodeId() != null && before.sourceNodeId().equals(after.sourceNodeId());
        route = sameSource ? next : new Route(previous.source(), observation, null, false, late);
        return !sameSource;
    }

    void requireSourceChoice() {
        Route previous = route;
        if (previous != null && previous.source().endpoint() != null)
            route = new Route(previous.source(), previous.observation(), null, false, true);
    }

    String chooseSource(ConnectionObservation expected, String id, boolean create) {
        Route previous = route;
        if (previous == null || previous.observation() != expected || previous.source().endpoint() == null)
            return null;
        var config = published.config();
        String selected = create ? WorldSourceResolver.suggestedSourceId(config, expected) : id;
        Resolution current = resolve(previous, config);
        var rule = config.endpointRules().get(expected.endpoint());
        if (create && rule != null && config.hasEndpointSource(rule.addressGroupId(), selected))
            selected = "remote:" + UUID.randomUUID();
        if (selected == null
                || (current.state() == State.WAITING_ID && rule != null
                        && rule.idPolicy() == IdPolicy.REQUIRE_BOUND_ID)
                || (!create && !current.candidateSourceIds().contains(selected)))
            return null;
        boolean newAdvertisedSource = expected.providerObservation().state() == ProviderState.ADVERTISED
                && (rule == null || !config.hasEndpointSource(rule.addressGroupId(), selected))
                && selected.equals(WorldSourceResolver.suggestedSourceId(config, expected));
        route = new Route(previous.source(), expected, selected, create || newAdvertisedSource, false);
        return selected;
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
        return resolve(routeFor(observation), published.config()).sourceNodeId();
    }

    private Route routeFor(Source source) {
        Route current = route;
        if (current != null && current.source() == source)
            return current;
        return source == null ? null
                              : new Route(source,
                                        new ConnectionObservation(0, source.endpoint(), source.nodeId(),
                                                ProviderObservation.absent()),
                                        null, false, false);
    }

    private static Resolution resolve(Route route, WorldGraphConfig.Snapshot config) {
        if (route == null)
            return new Resolution(State.UNAVAILABLE, null, java.util.Set.of());
        var resolved = WorldSourceResolver.resolve(config, route.observation(), route.chosenSource());
        if (route.chosenSource() != null && route.createSource())
            return new Resolution(State.RESOLVED, route.chosenSource(), resolved.candidateSourceIds());
        if (route.confirmSwitch()) {
            var choices = new java.util.TreeSet<>(resolved.candidateSourceIds());
            if (resolved.sourceNodeId() != null)
                choices.add(resolved.sourceNodeId());
            return new Resolution(State.CHOOSE_SOURCE, null, choices);
        }
        return resolved;
    }

    public Target target(Source observation) {
        if (!available)
            return null;
        Published view = published;
        return targetFor(view, sourceNode(observation), null);
    }

    private static Target targetFor(Published view, String sourceId, String choice) {
        if (sourceId == null)
            return null;
        SourceSettings settings = view.config().sourceSettings().get(sourceId);
        String preferred = choice != null ? choice : preferredMirror(settings);
        return view.graph()
                .defaultMirror(sourceId, preferred)
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
                    new SourceSettings(old.defaultMirrorId(), old.saveLocation(), old.preferredFolderName(),
                            strategy, old.inheritedMirrorId()));
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
        String sourceId = resolve(routeFor(observation), before).sourceNodeId();
        if (sourceId == null)
            throw new IOException("No source selected");
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        SourceSettings old = settings(sources, sourceId);
        sources.put(sourceId,
                new SourceSettings(old.defaultMirrorId(), location, old.preferredFolderName(),
                        old.conflictStrategy(), old.inheritedMirrorId()));
        publishConfig(before, before.nodes(), sources, before.mirrorSettings(), before.endpointRules(),
                before.pendingImports());
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
            return prepareTarget(routeFor(observation), mirrorChoice, createMirror, stillCurrent);
        }
    }

    private Target prepareTarget(Route route, String mirrorChoice, boolean createMirror,
            BooleanSupplier stillCurrent) throws IOException {
        String parent = resolve(route, published.config()).sourceNodeId();
        if (parent == null)
            throw new IOException("No unambiguous source selected");
        Source observation = route.source();
        Snapshot graph = published.graph();
        if (!graph.isUnambiguous(parent)) {
            throw new IOException("Source identity is ambiguous: " + parent);
        }
        if (observation.endpoint() == null && !graph.nodes().containsKey(parent))
            throw new IOException("Local source unavailable: " + parent);
        Target selected = createMirror ? null : targetFor(published, parent, mirrorChoice);
        WorldGraphConfig.Snapshot before = open().snapshot();
        SourceSettings old = settings(before.sourceSettings(), parent);
        if (selected != null) {
            if (route.createSource() || (mirrorChoice == null && preferredMirror(old) == null))
                recordRoute(before, route, parent,
                        mirrorChoice == null && preferredMirror(old) == null ? selected.nodeId() : null);
            return selected;
        }
        if (mirrorChoice != null)
            throw new IOException("Selected mirror unavailable: " + mirrorChoice);
        if (preferredMirror(old) != null && !createMirror)
            throw new IOException("Default mirror unavailable: " + preferredMirror(old));
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
        String indexSource = parent;
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
        recordRoute(before, route, parent, preferredMirror(old) == null && !createMirror ? nodeId : null);
        refreshPublished();
        return new Target(nodeId, parent, indexSource, folder);
    }

    private void recordRoute(WorldGraphConfig.Snapshot before, Route route, String parent, String target)
            throws IOException {
        var next = target == null
                ? before
                : before.withSourceSettings(
                          parent, settings(before.sourceSettings(), parent).withDefaultMirror(target));
        String endpoint = route.source().endpoint();
        if (endpoint != null) {
            next = next.withEndpointSource(endpoint, parent);
            EndpointRule rule = next.endpointRules().get(endpoint);
            if (route.createSource() && rule.idPolicy() != IdPolicy.ENDPOINT_ONLY) {
                ProviderObservation evidence = route.observation().providerObservation();
                if (evidence.state() == ProviderState.ADVERTISED) {
                    String group = rule.addressGroupId();
                    var binding =
                            new StableBinding(evidence.provider(), group, evidence.advertisedId(), parent);
                    boolean alreadyBound = next.stableBindings().stream().anyMatch(
                            value -> value.matches(evidence.provider(), group, evidence.advertisedId()));
                    if (!alreadyBound)
                        next = next.withStableBinding(binding);
                }
            }
        }
        replaceConfig(before, next);
    }

    public synchronized void editRouting(UnaryOperator<WorldGraphConfig.Snapshot> edit) throws IOException {
        if (clientService && DownloadManager.hasRoutingWork())
            throw new IOException("Stop capture and wait for pending exports before editing routes");
        try (var ignored = acquireGraphLease()) {
            if (store != null && !store.isCurrent())
                store = null;
            var before = open().snapshot();
            var next = Objects.requireNonNull(edit.apply(before));
            if (!before.nodes().equals(next.nodes()) || !before.locations().equals(next.locations())
                    || !before.pendingImports().equals(next.pendingImports()))
                throw new IOException("Routing edits cannot modify the on-disk graph");
            for (var entry : next.sourceSettings().entrySet()) {
                var old = before.sourceSettings().get(entry.getKey());
                String id = entry.getValue().defaultMirrorId();
                if (id != null && (old == null || !id.equals(old.defaultMirrorId()))
                        && targetFor(published, entry.getKey(), id) == null)
                    throw new IOException("Default mirror must be an available direct download target");
            }
            replaceConfig(before, next);
        }
        if (clientService)
            refreshAsync();
    }

    public synchronized void repairParent(String mirrorId, String parentId) throws IOException {
        repairParent(mirrorId, parentId, null);
    }

    public synchronized void repairParent(String mirrorId, Location localSource) throws IOException {
        repairParent(mirrorId, null, Objects.requireNonNull(localSource));
    }

    private void repairParent(String mirrorId, String parentId, Location localSource) throws IOException {
        if (clientService && DownloadManager.hasRoutingWork())
            throw new IOException("Stop capture and wait for pending exports before repairing lineage");
        try (var ignored = acquireGraphLease()) {
            refreshPublished();
            Snapshot graph = published.graph();
            Node child = graph.nodes().get(mirrorId);
            if (child == null || child.kind() != WorldGraph.Kind.MIRROR || !graph.isUnambiguous(mirrorId)
                    || !graph.missingParents().contains(child.parentId()))
                throw new IOException("This mirror does not have a repairable parent edge");
            Path folder = inventory.root(child.location().root()).resolve(child.location().folder());
            // A copied reservation must never give a populated save a new parent.
            requireEmptyClaimIfMetadataMissing(folder, child);
            if (localSource != null) {
                parentId = identifyRepairSource(localSource);
                graph = published.graph();
                if (!child.equals(graph.nodes().get(mirrorId)) || !graph.isUnambiguous(mirrorId))
                    throw new IOException("Mirror changed before repair");
            }
            Node parent = graph.nodes().get(parentId);
            if (parent == null || parent.kind() == WorldGraph.Kind.UNRESOLVED
                    || !graph.isUnambiguous(parentId) || graph.wouldCreateCycle(mirrorId, parentId))
                throw new IOException("This source cannot be the mirror's parent");
            try (var lock = WorldWriteLock.acquire(folder)) {
                repairParentOnDisk(folder, child, parentId);
            }
            var before = open().snapshot();
            var pending = new HashMap<>(before.pendingImports());
            pending.remove("upstream:" + mirrorId);
            publishConfig(before, before.nodes(), before.sourceSettings(), before.mirrorSettings(),
                    before.endpointRules(), pending);
            refreshPublished();
        }
    }

    private static boolean requireEmptyClaimIfMetadataMissing(Path folder, Node child) throws IOException {
        if (WorldMetadata.inspectSchema(folder) != WorldMetadata.Schema.MISSING)
            return false;
        if (!WorldRelocation.isEmptyReservation(folder, child.id()))
            throw new IOException("Mirror reservation is no longer empty: " + folder);
        return true;
    }

    private static void repairParentOnDisk(Path folder, Node child, String parentId) throws IOException {
        if (requireEmptyClaimIfMetadataMissing(folder, child)) {
            MirrorClaim.repairParent(folder, child.id(), child.parentId(), parentId);
            return;
        }
        Path file = folder.resolve(WorldMetadata.FILE_NAME);
        FileProof.Version stamp = FileProof.version(file);
        WorldMetadata metadata = WorldMetadata.loadIfPresent(folder).orElseThrow(
                () -> new IOException("Mirror metadata changed before repair: " + folder));
        if (!child.id().equals(metadata.nodeId) || !child.parentId().equals(metadata.parentNodeId)
                || child.kind() != metadata.kind || child.role() != metadata.role) {
            throw new IOException("Mirror changed before repair: " + folder);
        }
        metadata.parentNodeId = parentId;
        JsonSupport.compareAndWriteDocument(file, metadata, stamp);
    }

    private String identifyRepairSource(Location location) throws IOException {
        if (!published.untrackedLocalWorlds().contains(location))
            throw new IOException("The selected local save is no longer available");
        Path folder = inventory.localOriginal(location);
        String id;
        try (var ignored = WorldWriteLock.acquire(folder)) {
            inventory.localOriginal(location);
            if (WorldRelocation.journal(folder) != null
                    || NbtReadSupport.readWorldData(folder.resolve("level.dat"))
                            .getCompound("Data")
                            .isEmpty())
                throw new IOException("The selected local save cannot be used as a parent");
            id = LocalWorldIdentity.ensure(folder);
        }
        refreshPublished();
        Node parent = published.graph().nodes().get(id);
        if (parent == null || parent.kind() != WorldGraph.Kind.LOCAL || !location.equals(parent.location()))
            throw new IOException("The selected local save changed before repair");
        return id;
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

    public synchronized void refresh() throws IOException {
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
        published = new Published(result.graph(), config, Map.copyOf(targets), result.untrackedLocalWorlds());
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
        replaceConfig(before, next);
    }

    private void replaceConfig(WorldGraphConfig.Snapshot before, WorldGraphConfig.Snapshot next)
            throws IOException {
        try {
            if (!open().replace(before, next))
                throw new IOException("World graph changed before publication");
        } catch (IOException failure) {
            store = null;
            available = false;
            throw failure;
        }
        Published view = published;
        published = new Published(view.graph(), next, view.targets(), view.untrackedLocalWorlds());
    }

    private static SourceSettings settings(Map<String, SourceSettings> sources, String id) {
        return sources.getOrDefault(id, new SourceSettings(null, null, null, null, null));
    }

    private static String preferredMirror(SourceSettings settings) {
        if (settings == null)
            return null;
        return settings.defaultMirrorId() == null ? settings.inheritedMirrorId() : settings.defaultMirrorId();
    }

    private Path rootFor(ModConfig.SaveLocation location) {
        return inventory.root(Root.valueOf(location.name()));
    }
}
