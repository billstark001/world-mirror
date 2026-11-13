package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Duplicate;
import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Role;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.MirrorSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.PendingImport;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WorldRecovery;
import io.github.billstark001.worldmirror.legacy.LegacyGraphImport;
import io.github.billstark001.worldmirror.legacy.LegacyMirrorClaim;
import io.github.billstark001.worldmirror.legacy.LegacyRouting;
import io.github.billstark001.worldmirror.legacy.LegacySourceIds;
import io.github.billstark001.worldmirror.legacy.LegacyWorldMetadata;
import io.github.billstark001.worldmirror.legacy.LegacyWorldRecovery;
import io.github.billstark001.worldmirror.legacy.WorldMetadataUpgrade;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Disk inventory, graph publication, and selection of the current source's direct download target. */
public final class WorldGraphService {
    private static final String GRAPH_LOCK = ".world-graph.lock";
    private static final String BLOCKED_SOURCE = "blocked-source:";

    private static final class ClientService {
        static final WorldGraphService INSTANCE = new WorldGraphService(FabricLoader.getInstance().getGameDir(),
                FabricLoader.getInstance().getConfigDir().resolve("worldmirror"));
    }

    private record Candidate(Location location, Path path, WorldMetadata metadata, LegacyWorldMetadata legacy) {}
    private record RemoteEvidence(String indexSource, String parentId) {}
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

    private final Path game;
    private final Path configDirectory;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final java.util.concurrent.Executor worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WM-WorldGraph");
        thread.setDaemon(true);
        return thread;
    });
    private WorldGraphConfig store;
    private volatile Published published = new Published(WorldGraph.reconcile(List.of(), Map.of()),
            WorldGraphConfig.Snapshot.empty(), Map.of());
    private volatile boolean available;
    private volatile Source source;
    private String observedAddress;

    WorldGraphService(Path game, Path configDirectory) {
        this.game = game.toAbsolutePath().normalize();
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
    }

    public static WorldGraphService instance() { return ClientService.INSTANCE; }

    public void refreshAsync() {
        requested.set(true);
        if (!queued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                while (requested.getAndSet(false)) {
                    try { refresh(); }
                    catch (IOException | RuntimeException failure) {
                        WMLogger.warnRateLimited("world-graph", 30_000, "World graph refresh failed", failure);
                    }
                }
            } finally {
                queued.set(false);
                if (requested.get()) refreshAsync();
            }
        });
    }

    public boolean available() { return available; }
    public Snapshot graph() { return published.graph(); }
    public Source source() { return source; }

    /** Checks game-thread capture against the observation that owns the current session. */
    public boolean observationIsCurrent(Minecraft client) {
        Source current = source;
        if (current == null) return false;
        return client.getSingleplayerServer() != null ? current.endpoint() == null
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
                source = id == null ? null : new Source(id, client.getSingleplayerServer().getWorldData().getLevelName(), null);
            } else {
                source = observeAddress(client);
            }
        } catch (IOException | IllegalArgumentException failure) {
            source = null;
            WMLogger.warnRateLimited("world-graph-source", 30_000, "Could not identify the open world", failure);
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
        if (client.getSingleplayerServer() != null || (current != null && current.endpoint() == null)) return false;
        String address = currentAddress(client);
        if (Objects.equals(observedAddress, address)) return false;
        Source observed = observeAddress(client);
        if (Objects.equals(current, observed)) return false;
        source = observed;
        return true;
    }

    private static String currentAddress(Minecraft client) {
        var server = client.getCurrentServer();
        return server == null ? null : server.ip;
    }

    private Source observeAddress(Minecraft client) {
        observedAddress = currentAddress(client);
        if (observedAddress == null) return null;
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
        if (observation == null) return null;
        if (observation.nodeId() != null) return observation.nodeId();
        EndpointRule rule = config.endpointRules().get(observation.endpoint());
        if (rule == null) return WorldGraphConfig.deterministicId("remote", observation.endpoint());
        if (rule.idPolicy() == IdPolicy.REQUIRE_BOUND_ID) return null;
        if (rule.defaultSourceId() != null) return rule.defaultSourceId();
        return rule.sourceIds().size() == 1 ? rule.sourceIds().iterator().next() : null;
    }

    public Target target(Source observation) {
        if (!available) return null;
        Published view = published;
        String sourceId = sourceNode(observation, view.config());
        if (sourceId == null) return null;
        SourceSettings settings = view.config().sourceSettings().get(sourceId);
        return view.graph().defaultMirror(sourceId, settings == null ? null : settings.defaultMirrorId())
                .map(node -> view.targets().get(node.id())).orElse(null);
    }

    public Path preview(Source observation) {
        if (observation == null || !available) return null;
        Target target = target(observation);
        if (target != null) return target.folder();
        String sourceId = sourceNode(observation);
        if (sourceId == null) return null;
        SourceSettings settings = published.config().sourceSettings().get(sourceId);
        return rootFor(settings == null || settings.saveLocation() == null
                ? ModConfig.get().defaultSaveLocation : settings.saveLocation()).resolve(
                settings == null || settings.preferredFolderName() == null
                        ? MirrorFolders.generate(observation.label()) : settings.preferredFolderName());
    }

    public ModConfig.SaveLocation saveLocation(String sourceNodeId) {
        SourceSettings settings = sourceNodeId == null ? null : published.config().sourceSettings().get(sourceNodeId);
        return settings == null || settings.saveLocation() == null ? ModConfig.get().defaultSaveLocation : settings.saveLocation();
    }

    public ModConfig.ConflictStrategy conflictStrategy(String nodeId, String parentId) {
        WorldGraphConfig.Snapshot config = published.config();
        MirrorSettings mirror = nodeId == null ? null : config.mirrorSettings().get(nodeId);
        if (mirror != null && mirror.conflictStrategy() != null) return mirror.conflictStrategy();
        SourceSettings settings = parentId == null ? null : config.sourceSettings().get(parentId);
        return settings != null && settings.conflictStrategy() != null ? settings.conflictStrategy()
                : ModConfig.get().defaultConflictStrategy;
    }

    public synchronized void setConflictStrategy(String nodeId, String parentId, ModConfig.ConflictStrategy strategy) throws IOException {
        try (var ignored = acquireGraphLease()) {
            saveConflictStrategy(nodeId, parentId, strategy);
        }
    }

    private void saveConflictStrategy(String nodeId, String parentId, ModConfig.ConflictStrategy strategy) throws IOException {
        WorldGraphConfig.Snapshot before = open().snapshot();
        Map<String, MirrorSettings> mirrors = new HashMap<>(before.mirrorSettings());
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        if (nodeId != null) {
            if (strategy == null) mirrors.remove(nodeId);
            else mirrors.put(nodeId, new MirrorSettings(strategy));
        } else if (parentId != null) {
            SourceSettings old = settings(sources, parentId);
            sources.put(parentId, new SourceSettings(old.defaultMirrorId(), old.saveLocation(), old.preferredFolderName(), strategy));
        } else throw new IOException("No source selected");
        publishConfig(before, before.nodes(), sources, mirrors, before.endpointRules(), before.pendingImports());
    }

    public synchronized void setSaveLocation(Source observation, ModConfig.SaveLocation location) throws IOException {
        try (var ignored = acquireGraphLease()) {
            saveLocationPreference(observation, location);
        }
    }

    private void saveLocationPreference(Source observation, ModConfig.SaveLocation location) throws IOException {
        WorldGraphConfig.Snapshot before = open().snapshot();
        String sourceId = sourceNode(observation, before);
        if (sourceId == null) throw new IOException("No source selected");
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        SourceSettings old = settings(sources, sourceId);
        sources.put(sourceId, new SourceSettings(old.defaultMirrorId(), location, old.preferredFolderName(), old.conflictStrategy()));
        publishConfig(before, before.nodes(), sources, before.mirrorSettings(), before.endpointRules(), before.pendingImports());
    }

    synchronized Target prepare(Source observation) throws IOException {
        return prepare(observation, () -> true);
    }

    synchronized Target prepare(Source observation, BooleanSupplier stillCurrent) throws IOException {
        try (var ignored = acquireGraphLease()) {
            refreshPublished();
            if (!stillCurrent.getAsBoolean()) throw new IOException("Connection changed during mirror preparation");
            return prepareTarget(observation, stillCurrent);
        }
    }

    private Target prepareTarget(Source observation, BooleanSupplier stillCurrent) throws IOException {
        String parent = sourceNode(observation);
        if (parent == null) throw new IOException("No unambiguous source selected");
        Snapshot graph = published.graph();
        if (!graph.isUnambiguous(parent)) {
            throw new IOException("Source identity is ambiguous: " + parent);
        }
        if (observation.endpoint() == null && !graph.nodes().containsKey(parent)) throw new IOException("Local source unavailable: " + parent);
        Target selected = target(observation);
        WorldGraphConfig.Snapshot before = open().snapshot();
        SourceSettings old = settings(before.sourceSettings(), parent);
        if (selected != null) {
            if (old.defaultMirrorId() == null) recordDefault(before, parent, selected.nodeId(), observation.endpoint());
            return selected;
        }
        if (old.defaultMirrorId() != null) throw new IOException("Default mirror unavailable: " + old.defaultMirrorId());
        if (before.pendingImports().containsKey(BLOCKED_SOURCE + parent)) {
            throw new IOException("An existing mirror is still awaiting metadata upgrade");
        }
        if (graph.duplicates().stream().anyMatch(copy -> parent.equals(copy.original().parentId()))) {
            throw new IOException("A copied mirror is still being reconciled");
        }
        Path base = rootFor(old.saveLocation() == null ? ModConfig.get().defaultSaveLocation : old.saveLocation());
        Files.createDirectories(base);
        requirePlainRoot(base);
        String name = old.preferredFolderName() == null ? MirrorFolders.generate(observation.label()) : old.preferredFolderName();
        if (!stillCurrent.getAsBoolean()) throw new IOException("Connection changed during mirror preparation");
        Path folder = MirrorFolders.claim(base, name);
        String nodeId = "mirror:" + UUID.randomUUID();
        String indexSource = observation.endpoint() == null ? parent : "server:" + observation.endpoint();
        try {
            MirrorClaim.create(folder, nodeId, parent, indexSource);
        } catch (IOException failure) {
            try { Files.delete(folder); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        recordDefault(before, parent, nodeId, observation.endpoint());
        refreshPublished();
        return new Target(nodeId, parent, indexSource, folder);
    }

    private void recordDefault(WorldGraphConfig.Snapshot before, String parent, String target, String endpoint) throws IOException {
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        SourceSettings old = settings(sources, parent);
        sources.put(parent, new SourceSettings(target, old.saveLocation(), old.preferredFolderName(), old.conflictStrategy()));
        Map<String, EndpointRule> rules = new HashMap<>(before.endpointRules());
        if (endpoint != null) WorldGraphConfig.addEndpoint(rules, endpoint, parent);
        publishConfig(before, before.nodes(), sources, before.mirrorSettings(), rules, before.pendingImports());
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
                || !current.sourceId().equals(target.sourceId())) throw new IOException("Mirror unavailable");
        Path base = rootFor(location);
        Files.createDirectories(base);
        requirePlainRoot(base);
        Path from = current.folder();
        if (from.getParent().equals(base)) return new MoveResult(from, from);
        Path desired = base.resolve(from.getFileName());
        Path destination = WorldRelocation.isEmptyReservation(desired, current.nodeId()) ? desired
                : MirrorFolders.available(base, from.getFileName().toString());
        WorldGraphConfig.Snapshot before = open().snapshot();
        WorldRelocation.move(from, destination, current, () -> {
            Node existing = before.nodes().get(current.nodeId());
            if (existing == null) return false;
            Map<String, Node> nodes = new HashMap<>(before.nodes());
            nodes.put(existing.id(), new Node(existing.id(), existing.parentId(), locationOf(destination),
                    existing.kind(), existing.role()));
            Map<String, Location> locations = new HashMap<>(before.locations());
            locations.put(existing.id(), locationOf(destination));
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
        requirePlainRoot(configDirectory);
        var channel = FileChannel.open(configDirectory.resolve(GRAPH_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lease = channel.tryLock();
            if (lease == null) throw new IOException("World graph operation is already running");
            // The channel holds the lease until the entire graph operation closes it.
            return channel;
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("World graph operation is already running", failure);
            }
            throw failure;
        }
    }

    private void refreshPublished() throws IOException {
        try {
            if (store != null && !store.isCurrent()) store = null;
            refreshFromDisk();
            available = true;
        } catch (IOException | RuntimeException failure) {
            available = false;
            throw failure;
        }
    }

    private WorldGraphConfig open() throws IOException {
        if (store == null) store = WorldGraphConfig.open(configDirectory.resolve(WorldGraphConfig.FILE_NAME));
        return store;
    }

    private void publishConfig(WorldGraphConfig.Snapshot before, Map<String, Node> nodes,
                               Map<String, SourceSettings> sources, Map<String, MirrorSettings> mirrors,
                               Map<String, EndpointRule> rules, Map<String, PendingImport> pending) throws IOException {
        publishConfig(before, nodes, before.locations(), sources, mirrors, rules, pending);
    }

    private void publishConfig(WorldGraphConfig.Snapshot before, Map<String, Node> nodes, Map<String, Location> locations,
                               Map<String, SourceSettings> sources, Map<String, MirrorSettings> mirrors,
                               Map<String, EndpointRule> rules, Map<String, PendingImport> pending) throws IOException {
        var next = new WorldGraphConfig.Snapshot(WorldGraphConfig.SCHEMA, nodes, locations, sources, mirrors,
                rules, before.stableBindings(), pending);
        try {
            if (!open().replace(before, next)) throw new IOException("World graph changed before publication");
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

    private Path rootFor(ModConfig.SaveLocation location) { return root(Root.valueOf(location.name())); }

    private Path root(Root root) {
        return game.resolve(root == Root.SAVES ? "saves" : "downloaded_worlds");
    }

    private Location locationOf(Path folder) {
        return new Location(folder.getParent().equals(root(Root.SAVES)) ? Root.SAVES : Root.DOWNLOADED,
                folder.getFileName().toString());
    }

    private static void requirePlainRoot(Path path) throws IOException {
        FileProof.requirePlainDirectory(path);
        if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) {
            throw new IOException("Invalid world root: " + path);
        }
    }
    private void refreshFromDisk() throws IOException {
        FileTransaction.recover(configDirectory);
        WorldGraphConfig.Snapshot before = open().snapshot();
        LegacyGraphImport migration = LegacyGraphImport.open(configDirectory, before.pendingImports(), open().exists());
        LegacyRouting legacy = migration.routing();
        List<Path> folders = folders();
        Set<Path> deferred = recoverRelocations(folders, before, legacy);
        if (!deferred.isEmpty() && migration.hasLegacyFile()) {
            throw new IOException("Pending relocation must recover before archiving the legacy configuration");
        }
        folders = folders();

        Map<String, PendingImport> pending = new HashMap<>(before.pendingImports());
        pending.keySet().removeIf(key -> key.startsWith(BLOCKED_SOURCE));
        Map<String, String> allocations = migration.allocations();
        List<Candidate> candidates = scanMetadata(folders, deferred, allocations, pending);
        migration.pin(allocations);

        Map<String, EndpointRule> rules = new HashMap<>(before.endpointRules());
        Map<Path, String> legacyClaims = new HashMap<>();
        for (Path folder : folders) {
            if (deferred.contains(folder) || !Files.isRegularFile(folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) continue;
            String oldSource = LegacyMirrorClaim.readSource(folder);
            if (oldSource != null) legacyClaims.put(folder, oldSource);
        }
        Set<String> oldSourceIds = new HashSet<>(legacy.entries().keySet());
        oldSourceIds.addAll(legacyClaims.values());
        Map<String, String> importedParents = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.legacy() != null) oldSourceIds.add(candidate.legacy().sourceId());
            WorldMetadata metadata = candidate.metadata();
            if (metadata != null && metadata.sourceId.startsWith("server:") && metadata.parentNodeId != null
                    && metadata.parentNodeId.startsWith("remote:")) {
                importedParents.putIfAbsent(metadata.sourceId, metadata.parentNodeId);
            }
        }
        Map<String, String> remoteIds = LegacySourceIds.importRemoteIds(oldSourceIds, importedParents, rules);
        if (!open().exists()) restoreRemoteRules(candidates, folders, deferred, rules);
        Map<String, String> knownLegacyIds = new HashMap<>();
        for (Candidate candidate : candidates) {
            LegacyWorldMetadata metadata = candidate.legacy();
            if (metadata != null && metadata.mirrorId() != null && !metadata.mirrorId().isBlank()) {
                knownLegacyIds.putIfAbsent(metadata.mirrorId(), LegacySourceIds.legacyNodeId(metadata));
            } else if (candidate.metadata() != null) {
                String id = candidate.metadata().nodeId;
                knownLegacyIds.putIfAbsent(id.substring(id.indexOf(':') + 1), id);
            }
        }
        List<Node> observed = new ArrayList<>();
        Map<Location, Candidate> byLocation = new HashMap<>();
        for (Candidate candidate : candidates) {
            String key = locationKey(candidate.location());
            WorldMetadata metadata = candidate.metadata();
            LegacyWorldMetadata old = candidate.legacy();
            try {
                if (old != null) {
                    String nodeId = old.mirrorId() == null || old.mirrorId().isBlank()
                            ? allocations.get(key) : LegacySourceIds.legacyNodeId(old);
                    String parentId = LegacySourceIds.legacyParent(old, remoteIds, knownLegacyIds);
                    metadata = WorldMetadataUpgrade.upgrade(candidate.path(), nodeId, parentId, old);
                    pending.remove(key);
                    if (parentId != null && parentId.startsWith("unresolved:")) {
                        pending.put("upstream:" + nodeId, new PendingImport(legacy.entryFor(old.sourceId()),
                                candidate.location(), "upstream_unresolved"));
                    }
                }
                addMirror(observed, byLocation, new Candidate(candidate.location(), candidate.path(), metadata, null));
                if (metadata.parentNodeId != null && metadata.parentNodeId.startsWith("remote:")) {
                    observed.add(new Node(metadata.parentNodeId, null, null, Kind.REMOTE, null));
                }
            } catch (IOException | RuntimeException failure) {
                var evidence = new PendingImport(old == null ? null : legacy.entryFor(old.sourceId()),
                        candidate.location(), "metadata_upgrade_deferred");
                pending.put(key, evidence);
                if (old != null && old.kind() == Kind.MIRROR && old.role() == Role.SYNC) {
                    pending.put(BLOCKED_SOURCE + LegacySourceIds.legacyParent(old, remoteIds, knownLegacyIds), evidence);
                }
                WMLogger.warnRateLimited("graph-migrate:" + candidate.path(), 30_000, "World metadata upgrade deferred: " + candidate.path(), failure);
            }
        }
        scanLocalWorldsAndClaims(folders, deferred, legacy, legacyClaims, remoteIds,
                knownLegacyIds, observed, byLocation, pending);
        rules.values().stream().flatMap(rule -> rule.sourceIds().stream()).distinct()
                .forEach(id -> observed.add(new Node(id, null, null, Kind.REMOTE, null)));
        Map<String, Location> affinity = new HashMap<>(before.locations());
        Map<Location, String> oldOwners = new HashMap<>();
        for (Candidate candidate : candidates) {
            oldOwners.put(candidate.location(), candidate.legacy() == null
                    ? candidate.metadata().sourceId : candidate.legacy().sourceId());
        }
        migration.applyAffinity(oldOwners, observed, affinity, () -> ModConfig.get().defaultSaveLocation);
        Snapshot graph = repairDuplicates(observed, affinity, byLocation, pending);
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        Map<String, MirrorSettings> mirrors = new HashMap<>(before.mirrorSettings());
        migration.applySettings(remoteIds, graph, location -> {
            Candidate candidate = byLocation.get(location);
            if (candidate != null) return candidate.metadata().sourceId;
            MirrorClaim.Claim claim = MirrorClaim.read(root(location.root()).resolve(location.folder()));
            return claim == null ? null : claim.sourceId();
        }, WorldGraphService::locationKey, sources, mirrors, pending, () -> ModConfig.get().defaultSaveLocation);
        migration.verifyUnchanged();
        Map<String, Node> cache = new HashMap<>(graph.nodes());
        cache.keySet().removeIf(id -> !graph.isUnambiguous(id));
        Map<String, Location> locations = new HashMap<>(before.locations());
        graph.nodes().forEach((id, node) -> {
            if (node.location() != null) locations.put(id, node.location());
        });
        publishConfig(before, cache, locations, sources, mirrors, rules, pending);

        Map<String, Target> targets = new HashMap<>();
        for (Node node : graph.nodes().values()) {
            if (node.kind() != Kind.MIRROR || node.role() != Role.SYNC || !graph.isUnambiguous(node.id())) continue;
            Path folder = root(node.location().root()).resolve(node.location().folder());
            Candidate candidate = byLocation.get(node.location());
            MirrorClaim.Claim claim = candidate == null ? MirrorClaim.read(folder) : null;
            if (candidate != null || claim != null) {
                String indexSource = candidate != null ? candidate.metadata().sourceId : claim.sourceId();
                targets.put(node.id(), new Target(node.id(), node.parentId(), indexSource, folder));
            }
        }
        published = new Published(graph, open().snapshot(), Map.copyOf(targets));
        migration.complete(allocations, pending.keySet());
    }

    private List<Candidate> scanMetadata(List<Path> folders, Set<Path> deferred, Map<String, String> allocations,
                                         Map<String, PendingImport> pending) {
        List<Candidate> candidates = new ArrayList<>();
        for (Path folder : folders) {
            Location location = locationOf(folder);
            String key = locationKey(location);
            if (deferred.contains(folder)) {
                pending.put(key, new PendingImport(null, location, "relocation_deferred"));
                continue;
            }
            try {
                if (WorldRecovery.required(folder) || LegacyWorldRecovery.hasPending(folder)) {
                    try (WorldWriteLock ignored = WorldWriteLock.acquire(folder)) { }
                }
                var schema = WorldMetadata.inspectSchema(folder);
                if (schema == WorldMetadata.Schema.CURRENT) {
                    WorldMetadata metadata = WorldMetadata.loadIfPresent(folder).orElseThrow();
                    candidates.add(new Candidate(location, folder, metadata, null));
                    pending.remove(key);
                } else if (LegacyWorldMetadata.hasLegacyHeader(folder)) {
                    LegacyWorldMetadata metadata = LegacyWorldMetadata.read(folder);
                    if (metadata == null) throw new IOException("Legacy metadata changed: " + folder);
                    candidates.add(new Candidate(location, folder, null, metadata));
                    if (metadata.mirrorId() == null || metadata.mirrorId().isBlank()) {
                        allocations.computeIfAbsent(key, ignored -> (metadata.kind() == Kind.SNAPSHOT ? "snapshot:" : "mirror:") + UUID.randomUUID());
                    }
                } else if (schema != WorldMetadata.Schema.MISSING) {
                    pending.put(key, new PendingImport(null, location, "metadata_" + schema.name().toLowerCase(Locale.ROOT)));
                }
            } catch (IOException | RuntimeException failure) {
                pending.put(key, new PendingImport(null, location, "world_unavailable"));
                WMLogger.warnRateLimited("graph-scan:" + folder, 30_000, "World inventory deferred: " + folder, failure);
            }
        }
        return candidates;
    }

    private void scanLocalWorldsAndClaims(List<Path> folders, Set<Path> deferred,
                                         LegacyRouting legacy, Map<Path, String> legacyClaims, Map<String, String> remoteIds,
                                         Map<String, String> knownLegacyIds, List<Node> observed,
                                         Map<Location, Candidate> byLocation, Map<String, PendingImport> pending) {
        Map<String, List<WorldMetadata>> completed = new HashMap<>();
        for (Candidate candidate : byLocation.values()) {
            WorldMetadata metadata = candidate.metadata();
            completed.computeIfAbsent(metadata.nodeId, ignored -> new ArrayList<>()).add(metadata);
        }
        for (Path folder : folders) {
            if (deferred.contains(folder)) continue;
            Location location = locationOf(folder);
            String key = locationKey(location);
            if (byLocation.containsKey(location)) continue;
            PendingImport previous = pending.get(key);
            if (previous != null && !previous.reason().equals("claim_upgrade_deferred")
                    && !previous.reason().equals("local_identity_deferred") && !previous.reason().equals("relocation_deferred")
                    && !previous.reason().equals("invalid_mirror_claim")) continue;
            try {
                MirrorClaim.Claim claim = MirrorClaim.read(folder);
                if (claim == null) {
                    String oldSource = legacyClaims.get(folder);
                    if (oldSource != null) {
                        String parent = LegacySourceIds.legacyParent(oldSource, remoteIds, knownLegacyIds);
                        try {
                            claim = LegacyMirrorClaim.upgrade(folder, oldSource, parent);
                        } catch (IOException failure) {
                            var evidence = new PendingImport(legacy.entryFor(oldSource), location, "claim_upgrade_deferred");
                            pending.put(key, evidence);
                            pending.put(BLOCKED_SOURCE + parent, evidence);
                            continue;
                        }
                    }
                }
                if (claim == null && !Files.notExists(folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                    pending.put(key, new PendingImport(null, location, "invalid_mirror_claim"));
                    continue;
                }
                if (claim != null) {
                    List<WorldMetadata> owners = completed.getOrDefault(claim.nodeId(), List.of());
                    String parent = claim.parentNodeId();
                    String indexSource = claim.sourceId();
                    boolean completedElsewhere = owners.stream().anyMatch(owner ->
                            Objects.equals(owner.parentNodeId, parent) && owner.sourceId.equals(indexSource));
                    if (completedElsewhere && WorldRelocation.isEmptyReservation(folder, claim.nodeId())) {
                        pending.remove(key);
                        continue;
                    }
                    pending.remove(key);
                    observed.add(new Node(claim.nodeId(), claim.parentNodeId(), location, Kind.MIRROR, Role.SYNC));
                    if (claim.parentNodeId().startsWith("remote:")) {
                        observed.add(new Node(claim.parentNodeId(), null, null, Kind.REMOTE, null));
                    }
                    continue;
                }
                if (WorldMetadata.inspectSchema(folder) != WorldMetadata.Schema.MISSING
                        || !Files.isRegularFile(folder.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)
                        || location.root() != Root.SAVES) continue;
                String id = LocalWorldIdentity.read(folder);
                if (id == null) {
                    try (WorldWriteLock ignored = WorldWriteLock.acquire(folder)) {
                        if (WorldMetadata.inspectSchema(folder) != WorldMetadata.Schema.MISSING) continue;
                        id = LocalWorldIdentity.ensure(folder);
                    }
                }
                pending.remove(key);
                observed.add(new Node(id, null, location, Kind.LOCAL, null));
            } catch (IOException | RuntimeException failure) {
                pending.put(key, new PendingImport(null, location, "local_identity_deferred"));
            }
        }
    }

    private Snapshot repairDuplicates(List<Node> observed, Map<String, Location> affinity,
                                      Map<Location, Candidate> byLocation, Map<String, PendingImport> pending) {
        Snapshot graph = WorldGraph.reconcile(observed, affinity);
        for (Duplicate duplicate : graph.duplicates()) {
            Node copy = duplicate.copy();
            Path folder = root(copy.location().root()).resolve(copy.location().folder());
            String key = locationKey(copy.location());
            try (WorldWriteLock ignored = WorldWriteLock.acquire(folder)) {
                String replacement = copy.id().substring(0, copy.id().indexOf(':') + 1) + UUID.randomUUID();
                if (copy.kind() == Kind.LOCAL) {
                    LocalWorldIdentity.reassign(folder, copy.id(), replacement);
                } else if (WorldMetadata.inspectSchema(folder) == WorldMetadata.Schema.MISSING) {
                    MirrorClaim.reassign(folder, copy.id(), copy.parentId(), replacement);
                } else {
                    Path file = folder.resolve(WorldMetadata.FILE_NAME);
                    FileProof.Version proof = FileProof.version(file);
                    WorldMetadata metadata = WorldMetadata.loadIfPresent(folder).orElseThrow();
                    if (!metadata.nodeId.equals(copy.id()) || !Objects.equals(metadata.parentNodeId, copy.parentId())) {
                        throw new IOException("Mirror identity changed during duplicate repair");
                    }
                    metadata.nodeId = replacement;
                    JsonSupport.compareAndWriteDocument(file, metadata, proof);
                    byLocation.put(copy.location(), new Candidate(copy.location(), folder, metadata, null));
                }
                observed.remove(copy);
                observed.add(new Node(replacement, copy.parentId(), copy.location(), copy.kind(), copy.role()));
                pending.remove(key);
            } catch (IOException | RuntimeException failure) {
                pending.put(key, new PendingImport(null, copy.location(), "duplicate_identity_deferred"));
            }
        }
        return WorldGraph.reconcile(observed, affinity);
    }

    private static String locationKey(Location location) { return location.root().name() + "/" + location.folder(); }

    private static void addMirror(List<Node> nodes, Map<Location, Candidate> byLocation, Candidate candidate) {
        WorldMetadata metadata = candidate.metadata();
        nodes.add(new Node(metadata.nodeId, metadata.parentNodeId, candidate.location(), metadata.kind, metadata.role));
        byLocation.put(candidate.location(), candidate);
    }

    private static void restoreRemoteRules(List<Candidate> candidates, List<Path> folders, Set<Path> deferred,
                                           Map<String, EndpointRule> rules) {
        Set<RemoteEvidence> evidence = new HashSet<>();
        for (Candidate candidate : candidates) {
            WorldMetadata metadata = candidate.metadata();
            if (metadata != null) evidence.add(new RemoteEvidence(metadata.sourceId, metadata.parentNodeId));
        }
        for (Path folder : folders) {
            if (deferred.contains(folder)) continue;
            MirrorClaim.Claim claim = MirrorClaim.read(folder);
            if (claim != null) evidence.add(new RemoteEvidence(claim.sourceId(), claim.parentNodeId()));
        }
        for (RemoteEvidence source : evidence) {
            if (!source.indexSource().startsWith("server:") || source.parentId() == null
                    || !source.parentId().startsWith("remote:")) continue;
            try {
                String endpoint = WorldGraphConfig.normalizeEndpoint(source.indexSource().substring("server:".length()));
                WorldGraphConfig.addEndpoint(rules, endpoint, source.parentId());
            } catch (IllegalArgumentException invalid) {
                // Existing metadata edges remain in the graph even if the old address cannot be used to connect.
            }
        }
    }

    private Set<Path> recoverRelocations(List<Path> folders, WorldGraphConfig.Snapshot config,
                                         LegacyRouting legacy) throws IOException {
        Set<Path> deferred = new HashSet<>();
        Set<Path> handled = new HashSet<>();
        Set<Path> allowed = Set.of(root(Root.SAVES), root(Root.DOWNLOADED));
        for (Path folder : folders) {
            if (handled.contains(folder)) continue;
            WorldRelocation.Journal journal;
            try { journal = WorldRelocation.journal(folder); }
            catch (IOException | RuntimeException failure) {
                deferred.add(folder);
                WMLogger.warnRateLimited("graph-relocation:" + folder, 30_000, "Invalid relocation journal: " + folder, failure);
                continue;
            }
            if (journal == null) continue;
            handled.add(journal.source());
            handled.add(journal.target());
            try {
                Location location = journal.routingWitness().find(config.locations(),
                        oldSource -> legacy.relocationWitness(oldSource, () -> ModConfig.get().defaultSaveLocation));
                if (location == null) throw new IOException("Relocation commit location unavailable");
                WorldRelocation.recover(folder, root(location.root()).resolve(location.folder()), allowed);
            } catch (IOException | RuntimeException failure) {
                deferred.add(journal.source());
                deferred.add(journal.target());
                WMLogger.warnRateLimited("graph-relocation:" + folder, 30_000, "World relocation recovery deferred: " + folder, failure);
            }
        }
        return deferred;
    }

    private List<Path> folders() throws IOException {
        List<Path> result = new ArrayList<>();
        for (Root kind : Root.values()) {
            Path base = root(kind);
            if (Files.notExists(base, LinkOption.NOFOLLOW_LINKS)) continue;
            requirePlainRoot(base);
            try (var entries = Files.list(base)) {
                for (Path path : entries.sorted().toList()) {
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        requirePlainRoot(path);
                        locationOf(path);
                    } catch (IOException | IllegalArgumentException invalid) {
                        WMLogger.warnRateLimited("graph-directory:" + path, 30_000, "World directory unavailable: " + path, invalid);
                        continue;
                    }
                    result.add(path);
                }
            }
        }
        return result;
    }
}
