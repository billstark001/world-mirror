package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Offline seed changes are previewed and committed under the native save lease. */
public final class SeedConversion {
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

    public static final class Plan {
        private final SeedConversionTarget target;
        private final Request request;
        private final SeedTerrain.Plan terrain;
        private final Map<Path, String> proofs;
        private final Preview preview;

        private Plan(SeedConversionTarget target, Request request, SeedTerrain.Plan terrain,
                Map<Path, String> proofs) {
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

    public static TargetStatus inspectTarget(Path folder, String expectedNodeId) throws Exception {
        return inspectTarget(folder, expectedNodeId, new Progress());
    }

    public static TargetStatus inspectTarget(Path folder, String expectedNodeId, Progress progress)
            throws Exception {
        progress.checkpoint();
        var target = SeedConversionTarget.inspect(
                folder.toAbsolutePath().normalize(), expectedNodeId, null, progress, null);
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
        SeedConversionTarget.requireAvailableFolder(root);
        Plan plan;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(root, progress::checkpoint);
                var index = ChunkDatabase.lockForReplacement(root, progress::checkpoint);
                var snapshot = SeedIndexSnapshot.open(root, progress::checkpoint)) {
            progress.checkpoint();
            var target = SeedConversionTarget.inspect(root, expectedNodeId, null, progress, snapshot);
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
        SeedConversionTarget.requireAvailableFolder(root);
        Path backup;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(root, progress::checkpoint);
                var index = ChunkDatabase.lockForReplacement(root, progress::checkpoint);
                var snapshot = SeedIndexSnapshot.open(root, progress::checkpoint)) {
            progress.checkpoint();
            var verified = SeedConversionTarget.inspect(
                    root, target.expectedNodeId(), target.metadata().nodeId, progress, snapshot);
            if (!target.sameIdentity(verified))
                throw new IOException("The selected mirror changed after preview");
            Plan fresh = inspect(verified, approved.request, progress, snapshot);
            if (!fresh.proofs.equals(approved.proofs) || !fresh.terrain.equals(approved.terrain))
                throw new IOException("The mirror changed after preview. Preview the operation again.");
            progress.update(Phase.APPLYING, 0, 0);
            WMLogger.info("Applying mirror seed operation: folder=" + root
                    + " operation=" + approved.request.operation());
            backup = SeedConversionFiles.publish(
                    fresh.target, fresh.request, fresh.terrain, fresh.proofs, snapshot);
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

    private static Plan inspect(SeedConversionTarget target, Request request, Progress progress,
            SeedIndexSnapshot snapshot) throws Exception {
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
        SeedTerrainFiles.proof(root, root.resolve(WorldMetadata.FILE_NAME), proofs, progress);
        if (target.claim() != null)
            SeedTerrainFiles.proof(root, root.resolve(MirrorClaim.FILE_NAME), proofs, progress);
        for (Path path : WorldStructureCreator.generationFiles(root))
            SeedTerrainFiles.proof(root, root.resolve(path), proofs, progress);
        for (Path file : SeedConversionFiles.packFiles(root))
            SeedTerrainFiles.proof(root, file, proofs, progress);
        progress.checkpoint();
        return new Plan(target, request, terrain, proofs);
    }
}
