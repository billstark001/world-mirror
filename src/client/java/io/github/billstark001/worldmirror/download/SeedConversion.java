package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.MirrorWorldgenCheck;
import io.github.billstark001.worldmirror.io.RegionWriteTransaction;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.SharedConstants;
import net.minecraft.util.DirectoryLock;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Offline seed changes are previewed and committed under the native save lease. */
public final class SeedConversion {
    private static final Path OWNED_PACK = Path.of("datapacks", MirrorWorldgenAssets.PACK_DIRECTORY);
    private static final Path DATABASE = SeedIndexSnapshot.DATABASE;

    public enum Operation { SET, RESEED, REMOVE, REGENERATE_BORDER }

    public enum Phase { CHECKING, SCANNING, VERIFYING, APPLYING, CANCELLING }

    public record ProgressSnapshot(Phase phase, int completed, int total) {
        public boolean canCancel() {
            return phase != Phase.APPLYING && phase != Phase.CANCELLING;
        }
    }

    /** A cancellation request takes effect at the next checkpoint before publication begins. */
    public static final class Progress {
        private final Consumer<ProgressSnapshot> listener;
        private volatile ProgressSnapshot snapshot = new ProgressSnapshot(Phase.CHECKING, 0, 0);

        public Progress() {
            this(ignored -> {});
        }

        public Progress(Consumer<ProgressSnapshot> listener) {
            this.listener = listener;
        }

        public ProgressSnapshot snapshot() {
            return snapshot;
        }

        public synchronized boolean cancel() {
            if (!snapshot.canCancel())
                return false;
            snapshot = new ProgressSnapshot(Phase.CANCELLING, 0, 0);
            return true;
        }

        void checkpoint() {
            if (snapshot.phase() == Phase.CANCELLING)
                throw new CancellationException();
        }

        void update(Phase phase, int completed, int total) {
            ProgressSnapshot next = new ProgressSnapshot(phase, completed, total);
            synchronized (this) {
                checkpoint();
                snapshot = next;
            }
            listener.accept(next);
        }
    }

    public record Request(Operation operation, long seed, boolean removeGenerated, boolean blendTerrain) {
        public static Request set(long seed, boolean blend) {
            return new Request(Operation.SET, seed, false, blend);
        }

        public static Request reseed(long seed, boolean blend) {
            return new Request(Operation.RESEED, seed, false, blend);
        }

        public static Request remove(boolean cleanup) {
            return new Request(Operation.REMOVE, 0, cleanup, false);
        }

        public static Request regenerateBorder() {
            return new Request(Operation.REGENERATE_BORDER, 0, true, true);
        }
    }

    public record Preview(int removed, int repairedBiomes, int blended, int protectedChunks,
            int unknownChunks, int samples, double meanHeightDifference) {
        public boolean warnsAboutTerrain() {
            return samples > 0 && meanHeightDifference >= 12;
        }
    }

    /** UI state read from native save data without assigning an identity. */
    public record TargetStatus(boolean seeded, boolean blendingEnabled, Long seed) {}

    private record Target(Path folder, String expectedNodeId, MirrorTarget existing, WorldMetadata metadata,
            MirrorClaim.Claim claim, boolean initializeNative) {}

    public static final class Plan {
        private final Target target;
        private final Request request;
        private final SeedTerrain.Plan terrain;
        private final Map<Path, String> proofs;
        private final Preview preview;

        private Plan(Target target, Request request, SeedTerrain.Plan terrain, Map<Path, String> proofs) {
            this.target = target;
            this.request = request;
            this.terrain = terrain;
            this.proofs = Map.copyOf(proofs);
            preview = new Preview(terrain.removed(), terrain.repaired(), terrain.blended(),
                    terrain.protectedChunks(), terrain.unknown(), terrain.samples(),
                    terrain.meanHeightDifference());
        }

        public Preview preview() {
            return preview;
        }

        public String nodeId() {
            return target.metadata().nodeId;
        }
    }

    private SeedConversion() {}

    /** Claims an inventory category only. Seed adoption requires stronger checks. */
    public static boolean hasOrphanVoidGenerator(Path folder) {
        Path root = folder.toAbsolutePath().normalize();
        if (Files.notExists(root.resolve(OWNED_PACK), LinkOption.NOFOLLOW_LINKS))
            return false;
        try {
            requireAvailableFolder(root);
            if (!hasNoWorldMirrorIdentity(root))
                return false;
            var state = MirrorWorldgenCheck.inspect(root).state();
            return state == MirrorWorldgenCheck.State.CURRENT || state == MirrorWorldgenCheck.State.LEGACY;
        } catch (IOException unsupported) {
            return false;
        }
    }

    public static boolean isAdoptableVoid(Path folder) {
        Path root = folder.toAbsolutePath().normalize();
        if (!hasOrphanVoidGenerator(root))
            return false;
        try {
            if (!isCurrentOwnedVoid(root))
                return false;
            inspectOrphanNamespace(root, new Progress());
            return true;
        } catch (Exception unsupported) {
            return false;
        }
    }

    public static TargetStatus inspectTarget(Path folder, String expectedNodeId) throws Exception {
        return inspectTarget(folder, expectedNodeId, new Progress());
    }

    public static TargetStatus inspectTarget(Path folder, String expectedNodeId, Progress progress)
            throws Exception {
        progress.checkpoint();
        var target = inspectTarget(folder.toAbsolutePath().normalize(), expectedNodeId, null, progress, null);
        var generation = target.metadata() == null ? null : target.metadata().generation;
        boolean seeded = generation != null && generation.mode() == WorldMetadata.GenerationMode.SEEDED;
        var status = new TargetStatus(seeded, seeded && generation.blendTerrain(),
                seeded ? WorldStructureCreator.readSeed(target.folder()) : null);
        progress.checkpoint();
        WMLogger.debug("Read mirror seed settings: folder=" + folder + " seeded=" + seeded);
        return status;
    }

    public static Plan preview(MirrorTarget target, Request request) throws Exception {
        return preview(target.folder(), target.nodeId(), target, request, new Progress());
    }

    public static Plan preview(Path folder, String expectedNodeId, Request request) throws Exception {
        return preview(folder, expectedNodeId, request, new Progress());
    }

    public static Plan preview(Path folder, String expectedNodeId, Request request, Progress progress)
            throws Exception {
        return preview(folder.toAbsolutePath().normalize(), expectedNodeId, null, request, progress);
    }

    private static Plan preview(Path root, String expectedNodeId, MirrorTarget existing, Request request,
            Progress progress) throws Exception {
        progress.checkpoint();
        rejectBusyTarget(root, expectedNodeId);
        requireAvailableFolder(root);
        Plan plan;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(root, progress::checkpoint);
                var index = ChunkDatabase.lockForReplacement(root, progress::checkpoint);
                var snapshot = SeedIndexSnapshot.open(root, progress::checkpoint)) {
            progress.checkpoint();
            var target = inspectTarget(root, expectedNodeId, null, progress, snapshot);
            if (existing != null && !existing.equals(target.existing()))
                throw new IOException("The selected mirror identity changed");
            plan = inspect(target, request, progress, snapshot);
        }
        WMLogger.info("Previewed mirror seed operation: folder=" + root + " operation=" + request.operation()
                + summary(plan.preview()));
        return plan;
    }

    public static Path apply(Plan approved) throws Exception {
        return apply(approved, new Progress());
    }

    public static Path apply(Plan approved, Progress progress) throws Exception {
        var target = approved.target;
        progress.checkpoint();
        rejectBusyTarget(target.folder(), target.expectedNodeId());
        Path root = target.folder();
        requireAvailableFolder(root);
        Path backup;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(root, progress::checkpoint);
                var index = ChunkDatabase.lockForReplacement(root, progress::checkpoint);
                var snapshot = SeedIndexSnapshot.open(root, progress::checkpoint)) {
            progress.checkpoint();
            var verified = inspectTarget(
                    root, target.expectedNodeId(), target.metadata().nodeId, progress, snapshot);
            if (!sameTarget(target, verified))
                throw new IOException("The selected mirror changed after preview");
            Plan fresh = inspect(verified, approved.request, progress, snapshot);
            if (!fresh.proofs.equals(approved.proofs) || !fresh.terrain.equals(approved.terrain))
                throw new IOException("The mirror changed after preview. Preview the operation again.");
            progress.update(Phase.APPLYING, 0, 0);
            WMLogger.info("Applying mirror seed operation: folder=" + root
                    + " operation=" + approved.request.operation());
            backup = publish(fresh, snapshot);
        }
        WMLogger.info("Finished mirror seed operation: folder=" + root
                + " operation=" + approved.request.operation() + " changed=" + (backup != null)
                + summary(approved.preview()));
        return backup;
    }

    private static String summary(Preview preview) {
        return " removed=" + preview.removed() + " biomeRepairs=" + preview.repairedBiomes()
                + " blended=" + preview.blended() + " protected=" + preview.protectedChunks()
                + " unknown=" + preview.unknownChunks();
    }

    private static void rejectBusyTarget(Path root, String nodeId) throws IOException {
        var live = DownloadManager.liveCaptureTarget();
        if (live != null && (root.equals(live.folder()) || live.nodeId().equals(nodeId))
                && DownloadManager.hasRoutingWork())
            throw new IOException("Stop downloading and wait for writes to this mirror.");
    }

    private static boolean sameTarget(Target left, Target right) {
        return java.util.Objects.equals(left.existing(), right.existing())
                && java.util.Objects.equals(left.claim(), right.claim())
                && left.initializeNative() == right.initializeNative()
                && left.metadata().sourceId.equals(right.metadata().sourceId);
    }

    private static void requireAvailableFolder(Path root) throws IOException {
        FileProof.requirePlainDirectory(root.getParent());
        FileProof.requirePlainDirectory(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(root.resolve(WorldRelocation.JOURNAL), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The selected world is unavailable");
    }

    private static boolean isOrphanVoid(Path root) throws IOException {
        return hasNoWorldMirrorIdentity(root) && isCurrentOwnedVoid(root);
    }

    private static boolean hasNoWorldMirrorIdentity(Path root) throws IOException {
        return WorldMetadata.inspectSchema(root) == WorldMetadata.Schema.MISSING
                && Files.notExists(root.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(root.resolve(LocalWorldIdentity.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                && !MirrorMigrationCoordinator.needsRecovery(root)
                && Files.isRegularFile(root.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS);
    }

    private static Target inspectTarget(Path root, String nodeId, String plannedId, Progress progress,
            SeedIndexSnapshot snapshot) throws Exception {
        requireAvailableFolder(root);
        if (MirrorMigrationCoordinator.needsRecovery(root))
            throw new IOException("The selected world requires recovery before seed changes");
        var schema = WorldMetadata.inspectSchema(root);
        if (schema == WorldMetadata.Schema.CURRENT) {
            var metadata = WorldMetadata.loadIfPresent(root).orElseThrow();
            if (!metadata.nodeId.equals(nodeId))
                throw new IOException("The selected mirror identity changed");
            var target = new MirrorTarget(root, metadata.nodeId, metadata.sourceId);
            return new Target(root, nodeId, target, metadata, null, false);
        }
        if (schema != WorldMetadata.Schema.MISSING
                || !Files.notExists(root.resolve(LocalWorldIdentity.FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The selected world is not an unregistered World Mirror save");
        var claim = MirrorClaim.read(root);
        if (claim != null && claim.nodeId().equals(nodeId)) {
            boolean initializeNative = isUnmaterializedClaim(root);
            if (!initializeNative && !isCurrentOwnedVoid(root))
                throw new IOException("The unfinished mirror has unverified world generation");
            var metadata = WorldMetadata.create(nodeId, WorldGraph.Kind.MIRROR, claim.parentNodeId(),
                    WorldGraph.Role.SYNC, claim.sourceId());
            return new Target(root, nodeId, null, metadata, claim, initializeNative);
        }
        if (nodeId != null || !isOrphanVoid(root))
            throw new IOException("The selected world is not an unregistered World Mirror save");
        if (snapshot == null) {
            inspectOrphanNamespace(root, progress);
            return new Target(root, null, null, null, null, false);
        }
        String namespace = snapshot.orphanNamespace();
        String id = plannedId == null ? "snapshot:" + UUID.randomUUID() : plannedId;
        var metadata = WorldMetadata.create(id, WorldGraph.Kind.SNAPSHOT, null, WorldGraph.Role.SNAPSHOT,
                namespace == null ? id : namespace);
        return new Target(root, null, null, metadata, null, false);
    }

    private static void inspectOrphanNamespace(Path root, Progress progress) throws Exception {
        if (Files.notExists(root.resolve(DATABASE), LinkOption.NOFOLLOW_LINKS))
            return;
        try (var world = WorldWriteLock.acquire(root, progress::checkpoint);
                var index = ChunkDatabase.lockForReplacement(root, progress::checkpoint);
                var snapshot = SeedIndexSnapshot.open(root, progress::checkpoint)) {
            snapshot.orphanNamespace();
        }
    }

    private static boolean isUnmaterializedClaim(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (name.equals(MirrorClaim.FILE_NAME) || name.equals(DirectoryLock.LOCK_FILE))
                    continue;
                // A failed attempt may have already durably published its rollback archive.
                if (!name.equals("backups"))
                    return false;
                FileProof.requirePlainDirectory(path);
            }
            return true;
        }
    }

    private static boolean isCurrentOwnedVoid(Path root) throws IOException {
        return Files.isRegularFile(root.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)
                && WorldStructureCreator.hasCanonicalVoidGeneration(root)
                && WorldStructureCreator.hasUnmodifiedVoidAssets(root);
    }

    private static Plan inspect(Target target, Request request, Progress progress, SeedIndexSnapshot snapshot)
            throws Exception {
        progress.checkpoint();
        Path root = target.folder();
        WorldMetadata metadata = target.metadata();
        if (target.existing() == null && request.operation() != Operation.SET)
            throw new IOException("The selected world has no assigned seed");
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        if (metadata.hasFutureWorldgenSchema()
                || metadata.hasFutureWorldgenAssets(version, MirrorWorldgenAssets.ASSET_REVISION))
            throw new IOException("This mirror was saved by a newer generation format.");
        boolean seeded = metadata.generation.mode() == WorldMetadata.GenerationMode.SEEDED;
        if (request.operation() == Operation.SET) {
            if (seeded)
                throw new IOException("This mirror already has a seed. Use Change Seed.");
            if (target.existing() != null
                    && (MirrorMigrationPlan.inspect(root, version, true).state()
                                    != MirrorMigrationPlan.State.CURRENT
                            || !WorldStructureCreator.hasCanonicalVoidGeneration(root)))
                throw new IOException(
                        "Open the mirror and complete its world generation upgrade before setting a seed.");
        } else if (!seeded) {
            throw new IOException("This mirror has no assigned seed.");
        } else if (!WorldStructureCreator.matchesSeededGeneration(
                           root, metadata.generation.settingsDigest())) {
            throw new IOException(
                    "The assigned native world generation settings changed outside World Mirror.");
        } else if (request.operation() == Operation.REGENERATE_BORDER
                && !metadata.generation.blendTerrain()) {
            throw new IOException("This mirror does not have terrain blending enabled.");
        }
        if (seeded && target.existing() != null)
            target.existing().requireCurrent();
        var terrain = SeedTerrain.scan(root, metadata, request.operation(), request.seed(),
                request.removeGenerated(), request.blendTerrain(), progress, snapshot.root());
        progress.update(Phase.VERIFYING, 0, 0);
        Map<Path, String> proofs = new LinkedHashMap<>(terrain.proofs());
        proofs.putAll(snapshot.proofs());
        SeedTerrain.proof(root, root.resolve(WorldMetadata.FILE_NAME), proofs, progress);
        if (target.claim() != null)
            SeedTerrain.proof(root, root.resolve(MirrorClaim.FILE_NAME), proofs, progress);
        for (Path path : WorldStructureCreator.generationFiles(root))
            SeedTerrain.proof(root, root.resolve(path), proofs, progress);
        for (Path file : packFiles(root)) SeedTerrain.proof(root, file, proofs, progress);
        progress.checkpoint();
        return new Plan(target, request, terrain, proofs);
    }

    private static Path publish(Plan plan, SeedIndexSnapshot snapshot) throws Exception {
        Path root = plan.target.folder();
        var operation = plan.request.operation();
        var metadata = plan.target.metadata();
        boolean assigning = operation == Operation.SET || operation == Operation.RESEED;
        UUID blendEpoch = assigning ? UUID.randomUUID() : metadata.generation.seedEpoch();
        try (var tx = new FileTransaction(root)) {
            Path staged = tx.stagingDirectory();
            Map<Path, Path> writes = new LinkedHashMap<>();
            Set<Path> deletes = new HashSet<>();
            Map<String, Set<ChunkPos>> removed = new HashMap<>();
            for (var entry : plan.terrain.edits().entrySet()) {
                var region = entry.getKey();
                var changes = entry.getValue();
                stageRegion(root, staged, region, region.file(), writes, deletes,
                        (pos, chunk)
                                -> SeedTerrain.transform(
                                        pos, chunk, changes.get(pos), region.dimension(), blendEpoch));
                Set<ChunkPos> gone = new HashSet<>();
                changes.forEach((pos, edit) -> {
                    if (edit.delete())
                        gone.add(pos);
                });
                if (gone.isEmpty())
                    continue;
                if (operation != Operation.SET)
                    removed.computeIfAbsent(
                                   region.dimension().identifier().toString(), ignored -> new HashSet<>())
                            .addAll(gone);
                for (String kind : new String[] {"entities", "poi"}) {
                    Path file = SeedTerrain.sibling(region, kind);
                    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
                        continue;
                    var existing = positions(file, region);
                    var auxiliary = new HashSet<>(gone);
                    auxiliary.retainAll(existing);
                    if (!auxiliary.isEmpty())
                        stageRegion(root, staged, region, file, writes, deletes,
                                (pos, chunk) -> auxiliary.contains(pos) ? null : chunk);
                }
            }
            if (snapshot.root() != null
                    && ChunkDatabase.stageWithoutSeedGenerated(
                            snapshot.root(), staged, metadata.sourceId, removed)) {
                writes.put(DATABASE, staged.resolve(DATABASE));
                for (String suffix : new String[] {"-wal", "-shm", "-journal"}) {
                    Path sidecar = Path.of(DATABASE.toString() + suffix);
                    if (Files.exists(root.resolve(sidecar), LinkOption.NOFOLLOW_LINKS))
                        deletes.add(sidecar);
                }
            }
            if (operation != Operation.REGENERATE_BORDER) {
                stageNativeFiles(plan.target, staged, writes);
                int version = SharedConstants.getCurrentVersion().dataVersion().version();
                if (assigning) {
                    String digest = operation == Operation.SET
                            ? WorldStructureCreator.stageSeededGeneration(staged, plan.request.seed())
                            : WorldStructureCreator.stageReseededGeneration(
                                      staged, metadata.generation.settingsDigest(), plan.request.seed());
                    metadata.markSeededGeneration(version, digest, blendEpoch, plan.request.blendTerrain());
                    for (Path pack : packFiles(root)) deletes.add(root.relativize(pack));
                } else {
                    WorldStructureCreator.stageVoidGeneration(staged);
                    metadata.markWorldgenCurrent(version, MirrorWorldgenAssets.ASSET_REVISION);
                    for (Path pack : packFiles(staged)) writes.put(staged.relativize(pack), pack);
                }
                if (!metadata.save(staged))
                    throw new IOException("Could not stage mirror metadata");
                writes.put(Path.of(WorldMetadata.FILE_NAME), staged.resolve(WorldMetadata.FILE_NAME));
                if (plan.target.claim() != null)
                    deletes.add(Path.of(MirrorClaim.FILE_NAME));
            }
            if (writes.isEmpty() && deletes.isEmpty())
                return null;
            var touched = new HashSet<>(writes.keySet());
            touched.addAll(deletes);
            var expected = new HashMap<>(plan.proofs);
            // SQLite uses a transient shared-memory file, which is not a data source.
            Path shm = Path.of(DATABASE.toString() + "-shm");
            if (deletes.contains(shm))
                expected.put(shm, FileProof.sha256(root.resolve(shm)));
            MirrorMigrationCoordinator.verifyOriginals(root, touched, expected);
            Path originals = Files.createDirectory(staged.resolve("conversion-backup"));
            var files = touched.stream().filter(expected::containsKey).sorted().map(root::resolve).toList();
            var backupProof = MirrorMigrationCoordinator.copyTouchedFiles(root, originals, files);
            for (var entry : backupProof.entrySet()) {
                if (!entry.getValue().equals(expected.get(entry.getKey())))
                    throw new IOException("The mirror changed while preparing the backup");
            }
            Path backup = MirrorMigrationCoordinator.backupTouchedFiles(root, originals, backupProof);
            MirrorMigrationCoordinator.verifyOriginals(root, touched, expected);
            tx.commit(writes, deletes);
            if (operation == Operation.SET)
                retireEmptyPack(root);
            return backup;
        }
    }

    private static void stageNativeFiles(Target target, Path staged, Map<Path, Path> writes)
            throws IOException {
        Path root = target.folder();
        if (target.initializeNative()) {
            if (!WorldStructureCreator.createVoidWorld(
                        staged, root.getFileName().toString(), WorldSettingsSnapshot.defaults()))
                throw new IOException("Cannot initialize the reserved mirror");
            try (var paths = Files.walk(staged)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path relative = staged.relativize(file);
                    if (!relative.startsWith(OWNED_PACK)
                            && !relative.equals(Path.of(DirectoryLock.LOCK_FILE)))
                        writes.put(relative, file);
                }
            }
        } else {
            for (Path relative : WorldStructureCreator.generationFiles(root)) {
                Path file = staged.resolve(relative);
                Files.createDirectories(file.getParent());
                FileProof.copyVerified(root.resolve(relative), file);
                writes.put(relative, file);
            }
        }
    }

    // Only payload files are transactional. Empty directories carry no pack data and can be retired after commit.
    private static void retireEmptyPack(Path root) {
        Path pack = root.resolve(OWNED_PACK);
        try {
            FileProof.requirePlainPath(root, pack);
            FileProof.requirePlainDirectory(pack);
            if (Files.notExists(pack, LinkOption.NOFOLLOW_LINKS))
                return;
            try (var paths = Files.walk(pack)) {
                for (var directory : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                        continue;
                    FileProof.requirePlainPath(root, directory);
                    try {
                        Files.deleteIfExists(directory);
                    } catch (DirectoryNotEmptyException occupied) {
                        // Another writer installed a file. Leave it alone.
                    }
                }
            }
        } catch (IOException failure) {
            WMLogger.warn(
                    "Seed conversion committed; empty data pack directories could not be retired: " + pack,
                    failure);
        }
    }

    private static void stageRegion(Path root, Path staged, SeedTerrain.Region region, Path source,
            Map<Path, Path> writes, Set<Path> deletes, RegionWriteTransaction.Transform transform)
            throws IOException {
        Path relative = root.relativize(source);
        Path destination = staged.resolve(relative);
        RegionWriteTransaction.stage(
                source, destination, region.dimension(), region.x(), region.z(), Set.of(), transform);
        writes.put(relative, destination);
        var prior = positions(source, region);
        for (ChunkPos pos : prior) {
            Path originalPayload = SeedTerrain.external(source, pos);
            Path stagedPayload = SeedTerrain.external(destination, pos);
            Path key = root.relativize(originalPayload);
            if (Files.isRegularFile(stagedPayload, LinkOption.NOFOLLOW_LINKS))
                writes.put(key, stagedPayload);
            else if (Files.exists(originalPayload, LinkOption.NOFOLLOW_LINKS))
                deletes.add(key);
        }
    }

    private static Set<ChunkPos> positions(Path file, SeedTerrain.Region region) throws IOException {
        try (AnvilReader.Reader reader = AnvilReader.open(file, region.x(), region.z())) {
            return reader.positions();
        }
    }

    private static List<Path> packFiles(Path root) throws IOException {
        Path pack = root.resolve(OWNED_PACK);
        FileProof.requirePlainPath(root, pack);
        if (Files.notExists(pack, LinkOption.NOFOLLOW_LINKS))
            return List.of();
        FileProof.requirePlainDirectory(pack);
        try (var walk = Files.walk(pack)) {
            var files = new ArrayList<Path>();
            for (Path path : walk.toList()) {
                FileProof.requirePlainPath(root, path);
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    FileProof.requirePlainFile(path);
                    files.add(path);
                }
            }
            return files;
        }
    }
}
