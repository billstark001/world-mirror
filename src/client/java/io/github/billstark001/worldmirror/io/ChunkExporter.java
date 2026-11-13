package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.conflict.ConflictContext;
import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.SeedTerrain;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.Action;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.Destination;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.IndexStatus;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.WritePolicy;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.merge.RegionMerge;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private record DimensionResult(Map<ChunkPos, WriteStamp> written,
            Map<ChunkPos, WriteStamp> settledWithoutWrite, Set<ChunkPos> unreadableChunks, boolean successful,
            long databaseLookupNs, long materializeNs, long chunkReadNs, long resolveMergeWriteNs,
            long flushNs, long verificationNs, long recoveredCaptureTime, boolean recoveredIndex) {}

    private record StagedChunk(WriteStamp stamp, byte[] digest) {}

    private static final class StagedRegion {
        final Map<ChunkPos, StagedChunk> chunks = new HashMap<>();
        boolean failed;
    }

    private static final class TerrainTimings {
        long databaseLookupNs;
        long materializeNs;
        long chunkReadNs;
        long resolveMergeWriteNs;
        long flushNs;
        long verificationNs;
        long recoveredCaptureTime;
        boolean recoveredIndex;
    }

    private record RegionKey(int x, int z) {}

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

            DimensionResult dimensionResult;
            try {
                Path regionDir = WorldSaveLayout.regionDirectory(worldFolder, dimension);
                FileProof.requirePlainPath(worldFolder, regionDir);
                Files.createDirectories(regionDir);
                dimensionResult = exportDimensionChunks(regionDir, dimChunks, containerSnapshot, resolver, db,
                        dimension, worldFolder, snapshot, slowRegionThresholdMs, progress, metadata);
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
            Map<RegionKey, List<ChunkPos>> regions = new HashMap<>();
            for (ChunkPos pos : updates.keySet())
                regions.computeIfAbsent(new RegionKey(pos.getRegionX(), pos.getRegionZ()),
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
                            if (source == null || ChunkIndexStore.SEED_GENERATED.equals(source)) {
                                var terrain = reader.read(pos);
                                if (terrain != null
                                        && (source != null || SeedChunkProvenance.hasMarker(terrain))
                                        && SeedCaptureReceipt.read(terrain, metadata.nodeId) == null) {
                                    revisions.computeIfAbsent(dimension, ignored -> new HashMap<>())
                                            .put(pos, update.revision());
                                    continue;
                                }
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

    // ── Per-dimension export ──────────────────────────────────────────────────

    private static DimensionResult exportDimensionChunks(Path regionDir,
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks,
            Map<ResourceKey<Level>, Map<BlockPos, net.minecraft.nbt.CompoundTag>> containerSnapshot,
            ConflictResolver resolver, ChunkDatabase db, ResourceKey<Level> dimension, Path worldFolder,
            ChunkListener.DirtySnapshot snapshot, long slowRegionThresholdMs, ExportProgress progress,
            WorldMetadata metadata) {
        Map<RegionKey, List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>>> chunksByRegion =
                new HashMap<>();
        for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : dimChunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            chunksByRegion
                    .computeIfAbsent(
                            new RegionKey(pos.getRegionX(), pos.getRegionZ()), ignored -> new ArrayList<>())
                    .add(entry);
        }

        Map<ChunkPos, WriteStamp> written = new HashMap<>();
        Map<ChunkPos, WriteStamp> settled = new HashMap<>();
        Set<ChunkPos> unreadable = new HashSet<>();
        String dimStr = dimension.identifier().toString();
        TerrainTimings timings = new TerrainTimings();
        boolean successful = true;
        boolean seeded =
                metadata != null && metadata.generation.mode() == WorldMetadata.GenerationMode.SEEDED;
        boolean blend = seeded && metadata.generation.blendTerrain() && dimension.equals(Level.OVERWORLD);
        Set<ChunkPos> incomingPositions = blend ? dimChunks.keySet() : Set.of();
        Map<RegionKey, Set<ChunkPos>> occupancy = new HashMap<>();

        for (Map.Entry<RegionKey, List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>>> regionEntry :
                chunksByRegion.entrySet()) {
            progress.start(ExportProgress.Part.TERRAIN, dimension,
                    regionEntry.getValue().stream().map(Map.Entry::getKey).toList());
            int regionX = regionEntry.getKey().x();
            int regionZ = regionEntry.getKey().z();
            Path regionFile = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");

            synchronized (McaWriteSupport.lockFor(regionFile)) {
                long regionStartedNs = System.nanoTime();
                RegionStorageInfo storageInfo = new RegionStorageInfo("world_mirror", dimension, "chunk");
                StagedRegion staged = new StagedRegion();
                Set<ChunkPos> indexedClean = new HashSet<>();
                try {
                    FileProof.requirePlainPath(worldFolder, regionFile);
                    RegionFileIntegrity.Inspection before =
                            RegionFileIntegrity.inspect(regionFile, regionX, regionZ);
                    if (!before.usable() || !before.invalidChunks().isEmpty()) {
                        successful = false;
                        if (!before.usable())
                            regionEntry.getValue().forEach(entry -> unreadable.add(entry.getKey()));
                        unreadable.addAll(before.invalidChunks());
                        WMLogger.warn("Region validation failed before write file=" + regionFile + " reason="
                                + before.failure() + " invalidChunks=" + before.invalidChunks().size()
                                + "; retainedChunks=" + regionEntry.getValue().size());
                        continue;
                    }
                    unreadable.addAll(before.invalidChunks());
                    Set<ChunkPos> generatedClaims = new HashSet<>();
                    Set<ChunkPos> unindexedCandidates = new HashSet<>();
                    if (metadata != null) {
                        regionEntry.getValue().forEach(entry -> unindexedCandidates.add(entry.getKey()));
                        for (var row : db.queryRegion(dimStr, regionX, regionZ)) {
                            var position = new ChunkPos(row.x(), row.z());
                            unindexedCandidates.remove(position);
                            if (ChunkIndexStore.SEED_GENERATED.equals(row.updateSource()))
                                generatedClaims.add(position);
                        }
                    }
                    if (blend)
                        occupancy.put(new RegionKey(regionX, regionZ), before.presentChunks());

                    for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : regionEntry.getValue()) {
                        ChunkPos pos = entry.getKey();
                        ChunkListener.CapturedChunk captured = entry.getValue();
                        long lookupStartedNs = System.nanoTime();
                        boolean skip =
                                db.shouldSkipUpdate(dimStr, pos, "world_mirror", captured.capturedAtMs());
                        timings.databaseLookupNs += System.nanoTime() - lookupStartedNs;
                        if (skip)
                            indexedClean.add(pos);
                    }

                    var result = RegionMerge.writeVerified(
                            ()
                                    -> {
                                try (RegionFile vanillaRegion =
                                                new RegionFile(storageInfo, regionFile, regionDir, false)) {
                                    for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry :
                                            regionEntry.getValue()) {
                                        ChunkPos chunkPos = entry.getKey();
                                        ChunkListener.CapturedChunk captured = entry.getValue();
                                        try {
                                            long stageStartedNs = System.nanoTime();
                                            net.minecraft.nbt.CompoundTag localNbt;
                                            try {
                                                localNbt = McaWriteSupport.readChunk(vanillaRegion, chunkPos);
                                            } catch (Exception invalidStoredChunk) {
                                                unreadable.add(chunkPos);
                                                throw invalidStoredChunk;
                                            }
                                            timings.chunkReadNs += System.nanoTime() - stageStartedNs;
                                            boolean generatedClaim = generatedClaims.contains(chunkPos);
                                            if (localNbt != null && metadata != null
                                                    && (generatedClaim
                                                            || unindexedCandidates.contains(chunkPos))) {
                                                var receipt =
                                                        SeedCaptureReceipt.read(localNbt, metadata.nodeId);
                                                if (receipt != null) {
                                                    if (!db.recordUpdates(dimStr,
                                                                Map.of(chunkPos, receipt.time()),
                                                                receipt.source()))
                                                        throw new IOException(
                                                                "Could not repair captured seed index receipt");
                                                    timings.recoveredIndex = true;
                                                    if ("world_mirror".equals(receipt.source()))
                                                        timings.recoveredCaptureTime = Math.max(
                                                                timings.recoveredCaptureTime, receipt.time());
                                                }
                                                if (receipt != null
                                                        && (receipt.time() >= captured.capturedAtMs()
                                                                || db.shouldSkipUpdate(dimStr, chunkPos,
                                                                        "world_mirror",
                                                                        captured.capturedAtMs()))) {
                                                    settled.put(chunkPos, stamp(captured));
                                                    continue;
                                                }
                                                if (receipt != null)
                                                    generatedClaim = false;
                                            }
                                            var generated = localNbt == null
                                                    ? null
                                                    : SeedChunkProvenance.read(localNbt);
                                            boolean seedCandidate =
                                                    generatedClaim || unindexedCandidates.contains(chunkPos);
                                            boolean replaceGenerated = seedCandidate && generated != null
                                                    && SeedChunkProvenance.isUnmodified(localNbt, generated)
                                                    && SeedTerrain.hasNoAuxiliaryData(
                                                            worldFolder, dimension, chunkPos);
                                            if (localNbt != null && !replaceGenerated
                                                    && (generatedClaim
                                                            || (seedCandidate
                                                                    && SeedChunkProvenance.hasMarker(
                                                                            localNbt)))) {
                                                settled.put(chunkPos, stamp(captured));
                                                continue;
                                            }
                                            // The SQLite claim is insufficient if the MCA
                                            // entry was deleted or no longer decodes.
                                            Action action = ChunkUpdatePolicy.decide(WritePolicy.LIVE_SYNC,
                                                    localNbt == null ? Destination.MISSING
                                                                     : Destination.PRESENT,
                                                    indexedClean.contains(chunkPos) ? IndexStatus.BLOCKED
                                                                                    : IndexStatus.ELIGIBLE);
                                            if (action == Action.PRESERVE || action == Action.REPAIR_INDEX) {
                                                if (action == Action.PRESERVE) {
                                                    settled.put(chunkPos, stamp(captured));
                                                } else {
                                                    // Let the coordinator clear the invalid
                                                    // claim first. A later pass can then
                                                    // record the repaired capture's actual
                                                    // timestamp without a stale UPSERT
                                                    // preserving a newer, lost timestamp.
                                                    staged.failed = true;
                                                    unreadable.add(chunkPos);
                                                    WMLogger.warnRateLimited(
                                                            "missing-indexed-chunk-" + dimension.identifier(),
                                                            30_000L,
                                                            "Durability index references unavailable MCA entry dimension="
                                                                    + dimension.identifier()
                                                                    + " chunk=" + chunkPos
                                                                    + "; retaining capture for repair after index cleanup");
                                                }
                                                continue;
                                            }
                                            stageStartedNs = System.nanoTime();
                                            ChunkListener.CapturedChunk materialized =
                                                    snapshot.materialize(dimension, chunkPos, captured);
                                            timings.materializeNs += System.nanoTime() - stageStartedNs;
                                            net.minecraft.nbt.CompoundTag chunkNbt = materialized.nbt();
                                            stageStartedNs = System.nanoTime();
                                            SeedChunkProvenance.strip(chunkNbt);
                                            if (seeded)
                                                SeedTerrain.repairCapturedBiomes(chunkNbt, dimension);
                                            BlockEntityNbtSupport.applyContainerOverlays(
                                                    dimension, chunkNbt, containerSnapshot);
                                            boolean existsLocally = localNbt != null && !replaceGenerated;
                                            if (!resolver.shouldWriteChunk(new ConflictContext(chunkPos,
                                                        existsLocally, chunkNbt, dimension, worldFolder))) {
                                                settled.put(chunkPos, stamp(materialized));
                                                timings.resolveMergeWriteNs +=
                                                        System.nanoTime() - stageStartedNs;
                                                continue;
                                            }

                                            if (existsLocally && !materialized.authoritativeEmpty()) {
                                                BlockEntityNbtSupport.mergeChunkBlockEntities(
                                                        chunkNbt, localNbt);
                                            }
                                            BlockEntityNbtSupport.applyContainerOverlays(
                                                    dimension, chunkNbt, containerSnapshot);
                                            if (blend && !chunkNbt.contains(SeedTerrainDigest.BLENDING_DATA)
                                                    && chunkNbt.getStringOr("Status", "")
                                                            .matches("(minecraft:)?full")
                                                    && ((localNbt != null
                                                                && SeedTerrain.ownsBlend(localNbt,
                                                                        metadata.generation.seedEpoch()))
                                                            || bordersUnwritten(regionDir, chunkPos,
                                                                    incomingPositions, occupancy)))
                                                SeedTerrain.markBlend(
                                                        chunkNbt, metadata.generation.seedEpoch());
                                            if (replaceGenerated)
                                                SeedCaptureReceipt.write(
                                                        chunkNbt, metadata.nodeId, captured.capturedAtMs());
                                            NbtWriteSupport.validateStrings(chunkNbt);
                                            byte[] digest = NbtWriteSupport.writeChunkAndDigest(chunkNbt,
                                                    vanillaRegion.getChunkDataOutputStream(chunkPos));
                                            staged.chunks.put(
                                                    chunkPos, new StagedChunk(stamp(materialized), digest));
                                            timings.resolveMergeWriteNs += System.nanoTime() - stageStartedNs;
                                        } catch (Exception e) {
                                            staged.failed = true;
                                            WMLogger.warnRateLimited(
                                                    "chunk-process-" + dimension.identifier(), 30_000L,
                                                    "Chunk processing failed dimension="
                                                            + dimension.identifier() + " chunk=" + chunkPos
                                                            + "; retained for retry",
                                                    e);
                                        }
                                    }
                                    if (!staged.chunks.isEmpty()) {
                                        long flushStartedNs = System.nanoTime();
                                        vanillaRegion.flush();
                                        timings.flushNs += System.nanoTime() - flushStartedNs;
                                    }
                                }
                                return staged.chunks;
                            },
                            expected -> {
                                long started = System.nanoTime();
                                try {
                                    return verifyWrittenRegion(
                                            storageInfo, regionFile, regionDir, regionX, regionZ, expected);
                                } finally {
                                    timings.verificationNs += System.nanoTime() - started;
                                }
                            });
                    successful &= !staged.failed && result.complete();
                    if (!result.regionUsable()) {
                        regionEntry.getValue().forEach(entry -> unreadable.add(entry.getKey()));
                        regionEntry.getValue().forEach(entry -> settled.remove(entry.getKey()));
                    }
                    unreadable.addAll(result.unreadable());
                    unreadable.removeAll(result.written().keySet());
                    result.written().forEach((pos, chunk) -> written.put(pos, chunk.stamp()));
                    if (!result.complete()) {
                        WMLogger.warn("Region durability verification failed file=" + regionFile
                                + " verified=" + result.written().size() + " failed="
                                + result.unreadable().size() + "; failed chunks retained for retry");
                    }
                } catch (Exception e) {
                    successful = false;
                    regionEntry.getValue().forEach(entry -> settled.remove(entry.getKey()));
                    regionEntry.getValue().forEach(entry -> written.remove(entry.getKey()));
                    WMLogger.warn("Region write failed file=" + regionFile
                                    + " retainedChunks=" + regionEntry.getValue().size(),
                            e);
                } finally {
                    logSlowRegion(dimension, regionFile, regionEntry.getValue().size(), staged.chunks.size(),
                            regionStartedNs, slowRegionThresholdMs);
                }
            }
        }

        return new DimensionResult(written, settled, Set.copyOf(unreadable), successful,
                timings.databaseLookupNs, timings.materializeNs, timings.chunkReadNs,
                timings.resolveMergeWriteNs, timings.flushNs, timings.verificationNs,
                timings.recoveredCaptureTime, timings.recoveredIndex);
    }

    private static RegionMerge.Verification<ChunkPos> verifyWrittenRegion(RegionStorageInfo storageInfo,
            Path regionFile, Path regionDirectory, int regionX, int regionZ,
            Map<ChunkPos, StagedChunk> staged) {
        Set<ChunkPos> verified = new HashSet<>();
        Set<ChunkPos> unreadable = new HashSet<>(staged.keySet());
        boolean regionUsable = false;
        try {
            RegionFileIntegrity.Inspection inspection =
                    RegionFileIntegrity.inspect(regionFile, regionX, regionZ);
            if (!inspection.usable()) {
                return new RegionMerge.Verification<>(false, Set.of(), unreadable);
            }
            unreadable.addAll(inspection.invalidChunks());
            try (RegionFile reader = new RegionFile(storageInfo, regionFile, regionDirectory, false)) {
                regionUsable = true;
                for (ChunkPos pos : staged.keySet()) {
                    if (inspection.invalidChunks().contains(pos))
                        continue;
                    try {
                        try (var input = reader.getChunkDataInputStream(pos)) {
                            if (input != null
                                    && NbtReadSupport.verifyChunk(input, staged.get(pos).digest())) {
                                verified.add(pos);
                                unreadable.remove(pos);
                            }
                        }
                    } catch (Exception ignored) {
                        // The summary warning contains the region and failure count.
                    }
                }
            }
        } catch (Exception ignored) {
            // Treat an inspection/open failure as a failed durability check.
        }
        return new RegionMerge.Verification<>(regionUsable, verified, unreadable);
    }

    private static boolean bordersUnwritten(
            Path regionDir, ChunkPos pos, Set<ChunkPos> candidates, Map<RegionKey, Set<ChunkPos>> occupancy) {
        int x = pos.getRegionX() * 32 + pos.getRegionLocalX();
        int z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0)
                    continue;
                var neighbor = new ChunkPos(x + dx, z + dz);
                var key = new RegionKey(neighbor.getRegionX(), neighbor.getRegionZ());
                if (candidates.contains(neighbor))
                    continue;
                var present = occupancy.get(key);
                if (present == null) {
                    var path = regionDir.resolve("r." + key.x() + "." + key.z() + ".mca");
                    try {
                        present = McaWriteSupport.listChunkPositions(path, key.x(), key.z());
                    } catch (IOException unreadable) {
                        continue; // An unreadable neighboring region is not proof of a hole.
                    }
                    occupancy.put(key, present);
                }
                if (!present.contains(neighbor))
                    return true;
            }
        }
        return false;
    }

    private static WriteStamp stamp(ChunkListener.CapturedChunk captured) {
        return new WriteStamp(captured.revision(), captured.capturedAtMs());
    }

    private static long toMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static void logSlowRegion(ResourceKey<Level> dimension, Path file, int candidates, int written,
            long startedNs, long thresholdMs) {
        if (thresholdMs < 0L)
            return;
        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
        if (elapsedMs < thresholdMs)
            return;
        WMLogger.info("[perf] slowRegion kind=chunk"
                + " dimension=" + dimension.identifier() + " file=" + file.getFileName()
                + " candidates=" + candidates + " written=" + written + " elapsedMs=" + elapsedMs);
    }
}
