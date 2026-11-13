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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Inventories both world roots and applies recoverable graph repairs under the graph lease. */
final class WorldGraphInventory {
    private static final String BLOCKED_SOURCE = "blocked-source:";

    private record
            Candidate(Location location, Path path, WorldMetadata metadata, LegacyWorldMetadata legacy) {}
    private record RemoteEvidence(String indexSource, String parentId) {}
    record Result(Snapshot graph, Map<String, String> indexSources, List<Location> untrackedLocalWorlds,
            List<Location> orphanedMirrors) {}

    private final Path game;
    private final Path configDirectory;
    private final Supplier<ModConfig.SaveLocation> defaultSaveLocation;

    WorldGraphInventory(
            Path game, Path configDirectory, Supplier<ModConfig.SaveLocation> defaultSaveLocation) {
        this.game = game;
        this.configDirectory = configDirectory;
        this.defaultSaveLocation = defaultSaveLocation;
    }

    static boolean hasDeferredSource(WorldGraphConfig.Snapshot config, String sourceId) {
        return config.pendingImports().containsKey(BLOCKED_SOURCE + sourceId);
    }

    Path root(Root root) {
        return game.resolve(root == Root.SAVES ? "saves" : "downloaded_worlds");
    }

    Location locationOf(Path folder) {
        return new Location(folder.getParent().equals(root(Root.SAVES)) ? Root.SAVES : Root.DOWNLOADED,
                folder.getFileName().toString());
    }

    /** Checks a local original before a caller assigns or reuses its persistent identity. */
    Path localOriginal(Location location) throws IOException {
        if (location.root() != Root.SAVES)
            throw new IOException("Local original must be in the saves directory: " + location.folder());
        Path saves = root(Root.SAVES);
        Path folder = saves.resolve(location.folder());
        requirePlainRoot(saves);
        requirePlainRoot(folder);
        requireLocalOriginalFiles(folder);
        if (SeedConversion.hasOrphanVoidGenerator(folder))
            throw new IOException(
                    "Recover the mirror metadata before using this save as an upstream: " + folder);
        return folder;
    }

    private static void requireLocalOriginalFiles(Path folder) throws IOException {
        Path level = folder.resolve("level.dat");
        FileProof.requirePlainFile(level);
        if (!Files.isReadable(level) || WorldMetadata.inspectSchema(folder) != WorldMetadata.Schema.MISSING
                || !Files.notExists(folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Local original unavailable: " + folder);
    }

    static void requirePlainRoot(Path path) throws IOException {
        FileProof.requirePlainDirectory(path);
        if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) {
            throw new IOException("Invalid world root: " + path);
        }
    }

    void refresh(WorldGraphConfig store, Consumer<Result> publish) throws IOException {
        FileTransaction.recover(configDirectory);
        WorldGraphConfig.Snapshot before = store.snapshot();
        LegacyGraphImport migration =
                LegacyGraphImport.open(configDirectory, before.pendingImports(), store.exists());
        LegacyRouting legacy = migration.routing();
        List<Path> folders = folders();
        Set<Path> deferred = recoverRelocations(folders, before, legacy);
        if (!deferred.isEmpty() && migration.hasLegacyFile()) {
            throw new IOException(
                    "Pending relocation must recover before archiving the legacy configuration");
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
            if (deferred.contains(folder)
                    || !Files.isRegularFile(folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS))
                continue;
            String oldSource = LegacyMirrorClaim.readSource(folder);
            if (oldSource != null)
                legacyClaims.put(folder, oldSource);
        }
        Set<String> oldSourceIds = new HashSet<>(legacy.entries().keySet());
        oldSourceIds.addAll(legacyClaims.values());
        Map<String, String> importedParents = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.legacy() != null)
                oldSourceIds.add(candidate.legacy().sourceId());
            WorldMetadata metadata = candidate.metadata();
            if (metadata != null && metadata.sourceId.startsWith("server:") && metadata.parentNodeId != null
                    && metadata.parentNodeId.startsWith("remote:")) {
                importedParents.putIfAbsent(metadata.sourceId, metadata.parentNodeId);
            }
        }
        Map<String, String> remoteIds = LegacySourceIds.importRemoteIds(oldSourceIds, importedParents, rules);
        if (!store.exists())
            restoreRemoteRules(candidates, folders, deferred, rules);
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
                            ? allocations.get(key)
                            : LegacySourceIds.legacyNodeId(old);
                    String parentId = LegacySourceIds.legacyParent(old, remoteIds, knownLegacyIds);
                    metadata = WorldMetadataUpgrade.upgrade(candidate.path(), nodeId, parentId, old);
                    pending.remove(key);
                    if (parentId != null && parentId.startsWith("unresolved:")) {
                        pending.put("upstream:" + nodeId,
                                new PendingImport(legacy.entryFor(old.sourceId()), candidate.location(),
                                        "upstream_unresolved"));
                    }
                }
                addMirror(observed, byLocation,
                        new Candidate(candidate.location(), candidate.path(), metadata, null));
                if (metadata.parentNodeId != null && metadata.parentNodeId.startsWith("remote:")) {
                    observed.add(new Node(metadata.parentNodeId, null, null, Kind.REMOTE, null));
                }
            } catch (IOException | RuntimeException failure) {
                var evidence = new PendingImport(old == null ? null : legacy.entryFor(old.sourceId()),
                        candidate.location(), "metadata_upgrade_deferred");
                pending.put(key, evidence);
                if (old != null && old.kind() == Kind.MIRROR && old.role() == Role.SYNC) {
                    pending.put(BLOCKED_SOURCE + LegacySourceIds.legacyParent(old, remoteIds, knownLegacyIds),
                            evidence);
                }
                WMLogger.warnRateLimited("graph-migrate:" + candidate.path(), 30_000,
                        "World metadata upgrade deferred: " + candidate.path(), failure);
            }
        }
        List<Location> orphanedMirrors = new ArrayList<>();
        List<Location> untrackedLocalWorlds = scanLocalWorldsAndClaims(folders, deferred, legacy,
                legacyClaims, remoteIds, knownLegacyIds, observed, byLocation, pending, orphanedMirrors);
        rules.values()
                .stream()
                .flatMap(rule -> rule.sourceIds().stream())
                .distinct()
                .forEach(id -> observed.add(new Node(id, null, null, Kind.REMOTE, null)));
        Map<String, Location> affinity = new HashMap<>(before.locations());
        Map<Location, String> oldOwners = new HashMap<>();
        for (Candidate candidate : candidates) {
            oldOwners.put(candidate.location(),
                    candidate.legacy() == null ? candidate.metadata().sourceId
                                               : candidate.legacy().sourceId());
        }
        migration.applyAffinity(oldOwners, observed, affinity, defaultSaveLocation);
        Snapshot graph = repairDuplicates(observed, affinity, byLocation, pending);
        Map<String, SourceSettings> sources = new HashMap<>(before.sourceSettings());
        Map<String, MirrorSettings> mirrors = new HashMap<>(before.mirrorSettings());
        migration.applySettings(remoteIds, graph, location -> {
            Candidate candidate = byLocation.get(location);
            if (candidate != null)
                return candidate.metadata().sourceId;
            MirrorClaim.Claim claim = MirrorClaim.read(root(location.root()).resolve(location.folder()));
            return claim == null ? null : claim.sourceId();
        }, WorldGraphInventory::locationKey, sources, mirrors, pending, defaultSaveLocation);
        migration.verifyUnchanged();
        Map<String, Node> cache = new HashMap<>(graph.nodes());
        cache.keySet().removeIf(id -> !graph.isUnambiguous(id));
        Map<String, Location> locations = new HashMap<>(before.locations());
        graph.nodes().forEach((id, node) -> {
            if (node.location() != null)
                locations.put(id, node.location());
        });
        var next = new WorldGraphConfig.Snapshot(WorldGraphConfig.SCHEMA, cache, locations, sources, mirrors,
                rules, before.stableBindings(), pending);
        if (!store.replace(before, next))
            throw new IOException("World graph changed before publication");

        Map<String, String> indexSources = new HashMap<>();
        for (Node node : graph.nodes().values()) {
            if (node.kind() != Kind.MIRROR || node.role() != Role.SYNC || node.parentId() == null
                    || !graph.isUnambiguous(node.id()))
                continue;
            Path folder = root(node.location().root()).resolve(node.location().folder());
            Candidate candidate = byLocation.get(node.location());
            MirrorClaim.Claim claim = candidate == null ? MirrorClaim.read(folder) : null;
            if (candidate != null || claim != null) {
                String indexSource = candidate != null ? candidate.metadata().sourceId : claim.sourceId();
                indexSources.put(node.id(), indexSource);
            }
        }
        publish.accept(new Result(
                graph, Map.copyOf(indexSources), untrackedLocalWorlds, List.copyOf(orphanedMirrors)));
        migration.complete(allocations, pending.keySet());
    }

    private List<Candidate> scanMetadata(List<Path> folders, Set<Path> deferred,
            Map<String, String> allocations, Map<String, PendingImport> pending) {
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
                    try (WorldWriteLock ignored = WorldWriteLock.acquire(folder)) {
                    }
                }
                var schema = WorldMetadata.inspectSchema(folder);
                if (schema == WorldMetadata.Schema.CURRENT) {
                    WorldMetadata metadata = WorldMetadata.loadIfPresent(folder).orElseThrow();
                    candidates.add(new Candidate(location, folder, metadata, null));
                    pending.remove(key);
                } else if (LegacyWorldMetadata.hasLegacyHeader(folder)) {
                    LegacyWorldMetadata metadata = LegacyWorldMetadata.read(folder);
                    if (metadata == null)
                        throw new IOException("Legacy metadata changed: " + folder);
                    candidates.add(new Candidate(location, folder, null, metadata));
                    if (metadata.mirrorId() == null || metadata.mirrorId().isBlank()) {
                        allocations.computeIfAbsent(key,
                                ignored
                                -> (metadata.kind() == Kind.SNAPSHOT ? "snapshot:" : "mirror:")
                                        + UUID.randomUUID());
                    }
                } else if (schema != WorldMetadata.Schema.MISSING) {
                    pending.put(key,
                            new PendingImport(
                                    null, location, "metadata_" + schema.name().toLowerCase(Locale.ROOT)));
                }
            } catch (IOException | RuntimeException failure) {
                pending.put(key, new PendingImport(null, location, "world_unavailable"));
                WMLogger.warnRateLimited(
                        "graph-scan:" + folder, 30_000, "World inventory deferred: " + folder, failure);
            }
        }
        return candidates;
    }

    private List<Location> scanLocalWorldsAndClaims(List<Path> folders, Set<Path> deferred,
            LegacyRouting legacy, Map<Path, String> legacyClaims, Map<String, String> remoteIds,
            Map<String, String> knownLegacyIds, List<Node> observed, Map<Location, Candidate> byLocation,
            Map<String, PendingImport> pending, List<Location> orphanedMirrors) {
        List<Location> untracked = new ArrayList<>();
        Map<String, List<WorldMetadata>> completed = new HashMap<>();
        for (Candidate candidate : byLocation.values()) {
            WorldMetadata metadata = candidate.metadata();
            completed.computeIfAbsent(metadata.nodeId, ignored -> new ArrayList<>()).add(metadata);
        }
        for (Path folder : folders) {
            if (deferred.contains(folder))
                continue;
            Location location = locationOf(folder);
            String key = locationKey(location);
            if (byLocation.containsKey(location))
                continue;
            PendingImport previous = pending.get(key);
            if (previous != null && !previous.reason().equals("claim_upgrade_deferred")
                    && !previous.reason().equals("local_identity_deferred")
                    && !previous.reason().equals("duplicate_identity_deferred")
                    && !previous.reason().equals("relocation_deferred")
                    && !previous.reason().equals("invalid_mirror_claim"))
                continue;
            try {
                MirrorClaim.Claim claim = MirrorClaim.read(folder);
                if (claim == null) {
                    String oldSource = legacyClaims.get(folder);
                    if (oldSource != null) {
                        String parent = LegacySourceIds.legacyParent(oldSource, remoteIds, knownLegacyIds);
                        try {
                            claim = LegacyMirrorClaim.upgrade(folder, oldSource, parent);
                        } catch (IOException failure) {
                            var evidence = new PendingImport(
                                    legacy.entryFor(oldSource), location, "claim_upgrade_deferred");
                            pending.put(key, evidence);
                            pending.put(BLOCKED_SOURCE + parent, evidence);
                            continue;
                        }
                    }
                }
                if (claim == null
                        && !Files.notExists(
                                folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                    pending.put(key, new PendingImport(null, location, "invalid_mirror_claim"));
                    continue;
                }
                if (claim != null) {
                    List<WorldMetadata> owners = completed.getOrDefault(claim.nodeId(), List.of());
                    String parent = claim.parentNodeId();
                    String indexSource = claim.sourceId();
                    boolean completedElsewhere =
                            owners.stream().anyMatch(owner -> owner.sourceId.equals(indexSource));
                    if (completedElsewhere && WorldRelocation.isEmptyReservation(folder, claim.nodeId())) {
                        pending.remove(key);
                        continue;
                    }
                    pending.remove(key);
                    observed.add(
                            new Node(claim.nodeId(), claim.parentNodeId(), location, Kind.MIRROR, Role.SYNC));
                    if (parent != null && parent.startsWith("remote:")) {
                        observed.add(new Node(claim.parentNodeId(), null, null, Kind.REMOTE, null));
                    }
                    continue;
                }
                if (!Files.isRegularFile(folder.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
                    continue;
                if (SeedConversion.hasOrphanVoidGenerator(folder)) {
                    if (SeedConversion.isAdoptableVoid(folder)) {
                        orphanedMirrors.add(location);
                        pending.remove(key);
                    }
                    continue;
                }
                if (location.root() != Root.SAVES)
                    continue;
                requireLocalOriginalFiles(folder);
                String id = LocalWorldIdentity.read(folder);
                pending.remove(key);
                if (id == null)
                    untracked.add(location);
                else
                    observed.add(new Node(id, null, location, Kind.LOCAL, null));
            } catch (IOException | RuntimeException failure) {
                pending.put(key, new PendingImport(null, location, "local_identity_deferred"));
            }
        }
        return List.copyOf(untracked);
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
                    if (!metadata.nodeId.equals(copy.id())
                            || !Objects.equals(metadata.parentNodeId, copy.parentId())) {
                        throw new IOException("Mirror identity changed during duplicate repair");
                    }
                    metadata.nodeId = replacement;
                    JsonSupport.compareAndWriteDocument(file, metadata, proof);
                    byLocation.put(copy.location(), new Candidate(copy.location(), folder, metadata, null));
                }
                observed.remove(copy);
                observed.add(
                        new Node(replacement, copy.parentId(), copy.location(), copy.kind(), copy.role()));
                pending.remove(key);
            } catch (IOException | RuntimeException failure) {
                pending.put(key, new PendingImport(null, copy.location(), "duplicate_identity_deferred"));
            }
        }
        return WorldGraph.reconcile(observed, affinity);
    }

    private static String locationKey(Location location) {
        return location.root().name() + "/" + location.folder();
    }

    private static void addMirror(
            List<Node> nodes, Map<Location, Candidate> byLocation, Candidate candidate) {
        WorldMetadata metadata = candidate.metadata();
        nodes.add(new Node(
                metadata.nodeId, metadata.parentNodeId, candidate.location(), metadata.kind, metadata.role));
        byLocation.put(candidate.location(), candidate);
    }

    private static void restoreRemoteRules(List<Candidate> candidates, List<Path> folders, Set<Path> deferred,
            Map<String, EndpointRule> rules) {
        Set<RemoteEvidence> evidence = new HashSet<>();
        for (Candidate candidate : candidates) {
            WorldMetadata metadata = candidate.metadata();
            if (metadata != null)
                evidence.add(new RemoteEvidence(metadata.sourceId, metadata.parentNodeId));
        }
        for (Path folder : folders) {
            if (deferred.contains(folder))
                continue;
            MirrorClaim.Claim claim = MirrorClaim.read(folder);
            if (claim != null)
                evidence.add(new RemoteEvidence(claim.sourceId(), claim.parentNodeId()));
        }
        for (RemoteEvidence source : evidence) {
            if (!source.indexSource().startsWith("server:") || source.parentId() == null
                    || !source.parentId().startsWith("remote:"))
                continue;
            try {
                String endpoint = WorldGraphConfig.normalizeEndpoint(
                        source.indexSource().substring("server:".length()));
                WorldGraphConfig.addEndpoint(rules, endpoint, source.parentId());
            } catch (IllegalArgumentException invalid) {
                // Existing metadata edges remain in the graph even if the old address cannot be used to connect.
            }
        }
    }

    private Set<Path> recoverRelocations(
            List<Path> folders, WorldGraphConfig.Snapshot config, LegacyRouting legacy) throws IOException {
        Set<Path> deferred = new HashSet<>();
        Set<Path> handled = new HashSet<>();
        Set<Path> allowed = Set.of(root(Root.SAVES), root(Root.DOWNLOADED));
        for (Path folder : folders) {
            if (handled.contains(folder))
                continue;
            WorldRelocation.Journal journal;
            try {
                journal = WorldRelocation.journal(folder);
            } catch (IOException | RuntimeException failure) {
                deferred.add(folder);
                WMLogger.warnRateLimited("graph-relocation:" + folder, 30_000,
                        "Invalid relocation journal: " + folder, failure);
                continue;
            }
            if (journal == null)
                continue;
            handled.add(journal.source());
            handled.add(journal.target());
            try {
                Location location = journal.routingWitness().find(config.locations(),
                        oldSource -> legacy.relocationWitness(oldSource, defaultSaveLocation));
                if (location == null)
                    throw new IOException("Relocation commit location unavailable");
                WorldRelocation.recover(folder, root(location.root()).resolve(location.folder()), allowed);
            } catch (IOException | RuntimeException failure) {
                deferred.add(journal.source());
                deferred.add(journal.target());
                WMLogger.warnRateLimited("graph-relocation:" + folder, 30_000,
                        "World relocation recovery deferred: " + folder, failure);
            }
        }
        return deferred;
    }

    private List<Path> folders() throws IOException {
        List<Path> result = new ArrayList<>();
        for (Root kind : Root.values()) {
            Path base = root(kind);
            if (Files.notExists(base, LinkOption.NOFOLLOW_LINKS))
                continue;
            requirePlainRoot(base);
            try (var entries = Files.list(base)) {
                for (Path path : entries.sorted().toList()) {
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        continue;
                    try {
                        requirePlainRoot(path);
                        locationOf(path);
                    } catch (IOException | IllegalArgumentException invalid) {
                        WMLogger.warnRateLimited("graph-directory:" + path, 30_000,
                                "World directory unavailable: " + path, invalid);
                        continue;
                    }
                    result.add(path);
                }
            }
        }
        return result;
    }
}
