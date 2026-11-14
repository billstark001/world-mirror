package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Environment(EnvType.CLIENT)
public class ChunkExporter {
    public record WriteStamp(long revision, long capturedAtMs) {}

    public record ExportTimings(long databaseLookupMs, long materializeMs, long chunkReadMs,
            long resolveMergeWriteMs, long flushMs, long verificationMs, long entityMs) {}

    public record ExportResult(Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> written,
            Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> settledWithoutWrite,
            Map<ResourceKey<Level>, Set<ChunkPos>> unreadableChunks,
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> entityRevisions, boolean chunkWritesSuccessful,
            boolean entityWritesSuccessful, ExportTimings timings, long recoveredCaptureTime,
            boolean recoveredIndex) {
        public int totalWritten() {
            return written.values().stream().mapToInt(Map::size).sum();
        }

        public Map<ResourceKey<Level>, Map<ChunkPos, Long>> writtenRevisions() {
            return revisions(written);
        }

        public Map<ResourceKey<Level>, Map<ChunkPos, Long>> settledRevisions() {
            return revisions(settledWithoutWrite);
        }

        private static Map<ResourceKey<Level>, Map<ChunkPos, Long>> revisions(
                Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> source) {
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = new HashMap<>();
            source.forEach((dimension, stamps) -> {
                Map<ChunkPos, Long> byPos = new HashMap<>();
                stamps.forEach((pos, stamp) -> byPos.put(pos, stamp.revision()));
                result.put(dimension, byPos);
            });
            return result;
        }
    }

    /**
     * Exports all chunks in the snapshot to the given world folder.
     * <p>
     * Each dimension is exported to the directory required by the target
     * Minecraft save format.
     * Only "dirty" chunks are written — i.e. chunks whose {@link ChunkListener.CapturedChunk#capturedAtMs()}
     * is newer than their last recorded write time, and whose update source is not
     * outranked by a higher-priority source in {@code db}.
     * <p>
     * Previously written block-entity data is merged back before overwrite, then
     * container overlays are applied through {@link BlockEntityNbtSupport}.
     * <p>
     * This method is safe to call from a background thread.
     *
     * @param worldFolder    Root directory of the mirror world.
     * @param snapshot       Dirty reference snapshot produced by {@link ChunkListener#snapshotDirtyState()}.
     * @param entitySnapshot Immutable snapshot produced by {@link EntityTracker#snapshot()}.
     * @param containerSnapshot Immutable snapshot produced by {@code ContainerTracker.snapshotSavedData()}.
     * @param resolver       Conflict resolver for chunks that already exist on disk.
     * @param db             Chunk database for dirty-check and priority enforcement.
     * @param slowRegionThresholdMs Slow-region diagnostics threshold, or -1 to disable.
     * @param progress Observes region/dimension write starts; cannot grant acknowledgements or abort writes.
     * @return Exact revisions durably written, plus revisions settled without a write.
     */
    public static ExportResult exportChunks(Path worldFolder, ChunkListener.DirtySnapshot snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot,
            Map<ResourceKey<Level>, Map<BlockPos, net.minecraft.nbt.CompoundTag>> containerSnapshot,
            ConflictResolver resolver, ChunkDatabase db, long slowRegionThresholdMs, ExportProgress progress)
            throws Exception {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            return exportChunksLocked(worldFolder, snapshot, entitySnapshot, containerSnapshot, resolver, db,
                    slowRegionThresholdMs, progress);
        }
    }

    private static ExportResult exportChunksLocked(Path worldFolder, ChunkListener.DirtySnapshot snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot,
            Map<ResourceKey<Level>, Map<BlockPos, net.minecraft.nbt.CompoundTag>> containerSnapshot,
            ConflictResolver resolver, ChunkDatabase db, long slowRegionThresholdMs, ExportProgress progress)
            throws Exception {
        Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> allWritten = new HashMap<>();
        Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> allSettled = new HashMap<>();
        Map<ResourceKey<Level>, Set<ChunkPos>> allUnreadable = new HashMap<>();
        boolean chunkWritesSuccessful = true;
        long recoveredCaptureTime = 0L;
        boolean recoveredIndex = false;
        long databaseLookupNs = 0L, materializeNs = 0L, chunkReadNs = 0L;
        long resolveMergeWriteNs = 0L, flushNs = 0L, verificationNs = 0L, entityNs = 0L;

        var metadata = WorldMetadata.loadIfPresent(worldFolder).orElse(null);
        for (ResourceKey<Level> dimension : snapshot.chunks().keySet()) {
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks =
                    snapshot.chunks().getOrDefault(dimension, Map.of());

            TerrainDimensionExport.Result dimensionResult;
            try {
                Path regionDir = WorldSaveLayout.regionDirectory(worldFolder, dimension);
                FileProof.requirePlainPath(worldFolder, regionDir);
                Files.createDirectories(regionDir);
                dimensionResult = new TerrainDimensionExport(worldFolder, regionDir, dimension, dimChunks,
                        containerSnapshot, resolver, db, snapshot, metadata)
                                          .write(progress, slowRegionThresholdMs);
            } catch (Exception failure) {
                chunkWritesSuccessful = false;
                WMLogger.warn("Cannot prepare terrain dimension=" + dimension.identifier()
                                + "; other dimensions and entities continue",
                        failure);
                continue;
            }
            chunkWritesSuccessful &= dimensionResult.successful();
            allWritten.put(dimension, dimensionResult.written());
            allSettled.put(dimension, dimensionResult.settledWithoutWrite());
            allUnreadable.put(dimension, dimensionResult.unreadableChunks());
            databaseLookupNs += dimensionResult.databaseLookupNs();
            materializeNs += dimensionResult.materializeNs();
            chunkReadNs += dimensionResult.chunkReadNs();
            resolveMergeWriteNs += dimensionResult.resolveMergeWriteNs();
            flushNs += dimensionResult.flushNs();
            verificationNs += dimensionResult.verificationNs();
            recoveredCaptureTime = Math.max(recoveredCaptureTime, dimensionResult.recoveredCaptureTime());
            recoveredIndex |= dimensionResult.recoveredIndex();
        }
        long entityStartedNs = System.nanoTime();
        Map<ResourceKey<Level>, Map<ChunkPos, Long>> entityRevisions =
                writeEntities(worldFolder, entitySnapshot, progress, db, metadata);
        entityNs = System.nanoTime() - entityStartedNs;
        boolean entityWritesSuccessful = entitySnapshot.entrySet().stream().allMatch(entry -> {
            Map<ChunkPos, Long> written = entityRevisions.get(entry.getKey());
            return written != null && written.size() == entry.getValue().size();
        });
        if (recoveredIndex)
            WMLogger.info(
                    "Recovered chunk index entries from seed replacement receipts world=" + worldFolder);
        return new ExportResult(allWritten, allSettled, allUnreadable, entityRevisions, chunkWritesSuccessful,
                entityWritesSuccessful,
                new ExportTimings(toMillis(databaseLookupNs), toMillis(materializeNs), toMillis(chunkReadNs),
                        toMillis(resolveMergeWriteNs), toMillis(flushNs), toMillis(verificationNs),
                        toMillis(entityNs)),
                recoveredCaptureTime, recoveredIndex);
    }

    private static Map<ResourceKey<Level>, Map<ChunkPos, Long>> writeEntities(Path worldFolder,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> snapshot,
            ExportProgress progress, ChunkDatabase db, WorldMetadata metadata) {
        if (metadata == null || snapshot.isEmpty())
            return EntityRegionWriter.write(worldFolder, snapshot, progress);
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> eligible = new HashMap<>();
        Map<ResourceKey<Level>, Map<ChunkPos, Long>> revisions = new HashMap<>();
        snapshot.forEach((dimension, updates) -> {
            Path regionDirectory;
            try {
                regionDirectory = WorldSaveLayout.regionDirectory(worldFolder, dimension);
            } catch (RuntimeException failure) {
                WMLogger.warn("Cannot resolve entity terrain dimension " + dimension.identifier(), failure);
                return;
            }
            Map<AnvilReader.RegionCoordinates, List<ChunkPos>> regions = new HashMap<>();
            for (ChunkPos pos : updates.keySet())
                regions.computeIfAbsent(new AnvilReader.RegionCoordinates(pos.getRegionX(), pos.getRegionZ()),
                               ignored -> new ArrayList<>())
                        .add(pos);
            regions.forEach((region, positions) -> {
                Path file = regionDirectory.resolve("r." + region.x() + "." + region.z() + ".mca");
                try {
                    FileProof.requirePlainPath(worldFolder, file);
                    Map<ChunkPos, String> sources = new HashMap<>();
                    for (var row : db.queryRegion(dimension.identifier().toString(), region.x(), region.z()))
                        sources.put(new ChunkPos(row.x(), row.z()), row.updateSource());
                    try (var reader = AnvilReader.open(file, region.x(), region.z())) {
                        for (ChunkPos pos : positions) {
                            var update = updates.get(pos);
                            String source = sources.get(pos);
                            if (hasProtectedSeedTerrain(reader, pos, source, metadata.nodeId)) {
                                revisions.computeIfAbsent(dimension, ignored -> new HashMap<>())
                                        .put(pos, update.revision());
                                continue;
                            }
                            eligible.computeIfAbsent(dimension, ignored -> new HashMap<>()).put(pos, update);
                        }
                    }
                } catch (Exception failure) {
                    WMLogger.warnRateLimited("seed-entity-guard-" + dimension.identifier(), 30_000L,
                            "Could not verify seed terrain before entity writes to " + file, failure);
                }
            });
        });
        EntityRegionWriter.write(worldFolder, eligible, progress)
                .forEach((dimension, written)
                                 -> revisions.computeIfAbsent(dimension, ignored -> new HashMap<>())
                                         .putAll(written));
        return Map.copyOf(revisions);
    }

    private static boolean hasProtectedSeedTerrain(
            AnvilReader.Reader reader, ChunkPos pos, String source, String nodeId) throws IOException {
        if (source != null && !ChunkIndexStore.SEED_GENERATED.equals(source))
            return false;
        var terrain = reader.read(pos);
        return terrain != null && (source != null || SeedChunkProvenance.hasMarker(terrain))
                && SeedCaptureReceipt.read(terrain, nodeId) == null;
    }

    private static long toMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
