package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.download.ExportWork.Frozen;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ExportProgress;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Writes a frozen capture to one verified mirror under its native save lock. */
final class MirrorExportTransaction {
    static final ChunkExporter.ExportTimings EMPTY_TIMINGS =
            new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0);

    @FunctionalInterface
    interface OutputTarget {
        Path claim() throws IOException;
    }

    private MirrorExportTransaction() {}

    record ExportOutcome(ChunkExporter.ExportResult result,
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten, Frozen remaining,
            boolean indexCommitted, boolean metadataCommitted, long indexMillis, Path changedSavedData) {
        ExportOutcome {
            Objects.requireNonNull(result);
            Objects.requireNonNull(remaining);
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> copy = new HashMap<>();
            durableWritten.forEach((dimension, receipts) -> copy.put(dimension, Map.copyOf(receipts)));
            durableWritten = Map.copyOf(copy);
        }
        boolean successful() {
            return result.chunkWritesSuccessful() && result.entityWritesSuccessful() && indexCommitted
                    && metadataCommitted;
        }
        static ExportOutcome failed(Frozen work) {
            return new ExportOutcome(new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(), Map.of(),
                                             false, false, EMPTY_TIMINGS, 0L, false),
                    Map.of(), work, false, false, 0L, null);
        }
    }

    /** The native transaction never mutates live capture state or waits for a game-thread task. */
    static ExportOutcome write(Frozen work, OutputTarget output, ConflictResolver resolver,
            long slowRegionThresholdMs, ExportProgress progress) {
        Path worldFolder;
        try {
            worldFolder = output.claim();
        } catch (Exception preparationFailure) {
            OperationFailures.report(work.context().session().id(), "output_claim", null, preparationFailure);
            return ExportOutcome.failed(work);
        }
        var target = work.context().session().target();
        String sourceId = target.sourceId();
        var snapshot = work.terrain();
        var entitySnapshot = work.entities();
        var containerSnapshot = work.containers();
        var worldSettings = work.context().session().settings().worldSettings();
        ChunkDatabase db = null;
        var result = ExportOutcome.failed(work).result();
        Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten = new HashMap<>();
        Frozen remaining = work;
        long latestDurableCaptureTime = work.durableCaptureTime(), indexMillis = 0L;
        boolean indexCommitted = false, metadataCommitted = false;
        boolean repairedIndex = false;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            MirrorMigrationPlan.Inspection readiness = MirrorMigrationCoordinator.inspect(worldFolder);
            if (!readiness.mayCreateOrWriteWithoutMigration()) {
                WMLogger.warn("Mirror requires an explicit upgrade or is not writable (" + readiness.state()
                        + "); export aborted without modifying it.");
                return ExportOutcome.failed(work);
            }

            boolean createFreshWorld = readiness.state() == MirrorMigrationPlan.State.NEW;
            var reservation = createFreshWorld ? MirrorClaim.read(worldFolder) : null;
            WorldMetadata metadata = readiness.metadata();
            boolean matches = createFreshWorld
                    ? reservation != null && target.nodeId().equals(reservation.nodeId())
                            && target.parentNodeId().equals(reservation.parentNodeId())
                            && sourceId.equals(reservation.sourceId())
                    : metadata != null && target.nodeId().equals(metadata.nodeId)
                            && target.parentNodeId().equals(metadata.parentNodeId)
                            && sourceId.equals(metadata.sourceId);
            if (!matches) {
                WMLogger.warn("Export target identity changed before the native lease was acquired: "
                        + target.nodeId());
                return ExportOutcome.failed(work);
            }
            if (createFreshWorld)
                metadata = WorldMetadata.create(target.nodeId(), WorldGraph.Kind.MIRROR,
                        target.parentNodeId(), WorldGraph.Role.SYNC, sourceId);
            boolean worldgenReady;
            if (!createFreshWorld) {
                worldgenReady = WorldStructureCreator.maintainWorld(worldFolder, sourceId);
            } else if (Files.notExists(worldFolder.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) {
                worldgenReady = WorldStructureCreator.createVoidWorld(worldFolder, sourceId, worldSettings);
            } else {
                // NEW can include a partially materialized, verified claim after a failed first export.
                worldgenReady = WorldStructureCreator.upgradeVoidWorld(worldFolder, sourceId);
            }
            if (!worldgenReady) {
                WMLogger.warn("World generation setup failed; export aborted before writing chunks.");
                return ExportOutcome.failed(work);
            }
            if (createFreshWorld) {
                metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(),
                        MirrorWorldgenAssets.ASSET_REVISION);
                if (!metadata.save(worldFolder)) {
                    return ExportOutcome.failed(work);
                }
                MirrorClaim.finish(worldFolder, target.nodeId());
            }

            try {
                db = ChunkDatabase.open(worldFolder, sourceId);
            } catch (SQLException e) {
                WMLogger.warn("Chunk database open failed; export aborted world=" + worldFolder, e);
                return ExportOutcome.failed(work);
            }
            result = ChunkExporter.exportChunks(worldFolder, snapshot, entitySnapshot, containerSnapshot,
                    resolver, db, slowRegionThresholdMs, progress);
            repairedIndex = result.recoveredIndex();
            latestDurableCaptureTime = Math.max(latestDurableCaptureTime, result.recoveredCaptureTime());

            indexCommitted = true;
            long durabilityStartedNs = System.nanoTime();
            for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> dimension :
                    result.unreadableChunks().entrySet()) {
                if (dimension.getValue().isEmpty())
                    continue;
                String dimensionId = dimension.getKey().identifier().toString();
                if (db.removeUnreadableUpdates(dimensionId, dimension.getValue())) {
                    repairedIndex = true;
                    WMLogger.warn("Removed stale durability claims for unreadable region chunks dimension="
                            + dimensionId + " chunks=" + dimension.getValue().size());
                } else {
                    indexCommitted = false;
                }
            }
            for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkExporter.WriteStamp>> dimension :
                    result.written().entrySet()) {
                String dimensionId = dimension.getKey().identifier().toString();
                Map<ChunkPos, Long> timestamps = new HashMap<>();
                Map<ChunkPos, Long> revisions = new HashMap<>();
                dimension.getValue().forEach((pos, stamp) -> {
                    timestamps.put(pos, stamp.capturedAtMs());
                    revisions.put(pos, stamp.revision());
                });
                if (db.recordUpdates(dimensionId, timestamps, "world_mirror")) {
                    durableWritten.put(dimension.getKey(), revisions);
                    for (long capturedAtMs : timestamps.values()) {
                        latestDurableCaptureTime = Math.max(latestDurableCaptureTime, capturedAtMs);
                    }
                } else {
                    indexCommitted = false;
                    WMLogger.warn("Durability-index commit failed for [" + dimensionId + "]; retaining "
                            + revisions.size() + " revision(s) for retry.");
                }
            }
            indexMillis = (System.nanoTime() - durabilityStartedNs) / 1_000_000L;
            remaining = work.remaining(result, durableWritten, latestDurableCaptureTime);
            metadataCommitted = metadata.markSyncComplete(worldFolder, latestDurableCaptureTime);
        } catch (Exception failure) {
            WMLogger.warn("Export pass failed trigger=" + work.context().trigger() + " world=" + worldFolder,
                    failure);
        } finally {
            if (db != null)
                db.close();
        }
        Path changedSavedData = repairedIndex || !durableWritten.isEmpty() ? worldFolder : null;
        return new ExportOutcome(result, durableWritten, remaining, indexCommitted, metadataCommitted,
                indexMillis, changedSavedData);
    }
}
