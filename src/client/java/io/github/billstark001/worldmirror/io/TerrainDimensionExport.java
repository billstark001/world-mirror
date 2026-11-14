package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.conflict.ConflictContext;
import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.SeedTerrainBiomes;
import io.github.billstark001.worldmirror.download.SeedTerrainBlending;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkUpdate;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.Action;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.Destination;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.IndexStatus;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.WritePolicy;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.io.AnvilReader.RegionCoordinates;
import io.github.billstark001.worldmirror.io.ChunkExporter.WriteStamp;
import io.github.billstark001.worldmirror.merge.RegionMerge;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Writes and verifies one dimension under the exporter's world lease.
final class TerrainDimensionExport {
    record Result(Map<ChunkPos, WriteStamp> written, Map<ChunkPos, WriteStamp> settledWithoutWrite,
            Set<ChunkPos> unreadableChunks, boolean successful, long databaseLookupNs, long materializeNs,
            long chunkReadNs, long resolveMergeWriteNs, long flushNs, long verificationNs,
            long recoveredCaptureTime, boolean recoveredIndex) {}

    private record StagedChunk(WriteStamp stamp, byte[] digest) {}

    private static final class StagedRegion {
        final Map<ChunkPos, StagedChunk> chunks = new HashMap<>();
        final Map<ChunkPos, WriteStamp> reused = new HashMap<>();
        boolean failed, invalidHeader;
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

    private final Path worldFolder, regionDir;
    private final ResourceKey<Level> dimension;
    private final String dimensionId;
    private final Map<ChunkPos, ChunkListener.CapturedChunk> captures;
    private final Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers;
    private final ConflictResolver resolver;
    private final ChunkDatabase db;
    private final ChunkListener.DirtySnapshot snapshot;
    private final WorldMetadata metadata;
    private final boolean seeded, blend;

    private final Map<ChunkPos, WriteStamp> written = new HashMap<>();
    private final Map<ChunkPos, WriteStamp> settled = new HashMap<>();
    private final Set<ChunkPos> unreadable = new HashSet<>();
    private final TerrainTimings timings = new TerrainTimings();
    private final Map<RegionCoordinates, Set<ChunkPos>> occupancy = new HashMap<>();
    private Map<String, Integer> sourcePriorities;

    TerrainDimensionExport(Path worldFolder, Path regionDir, ResourceKey<Level> dimension,
            Map<ChunkPos, ChunkListener.CapturedChunk> captures,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers, ConflictResolver resolver,
            ChunkDatabase db, ChunkListener.DirtySnapshot snapshot, WorldMetadata metadata) {
        this.worldFolder = worldFolder;
        this.regionDir = regionDir;
        this.dimension = dimension;
        dimensionId = dimension.identifier().toString();
        this.captures = captures;
        this.containers = containers;
        this.resolver = resolver;
        this.db = db;
        this.snapshot = snapshot;
        this.metadata = metadata;
        seeded = metadata != null && metadata.generation.mode() == WorldMetadata.GenerationMode.SEEDED;
        blend = seeded && metadata.generation.blendTerrain() && dimension.equals(Level.OVERWORLD);
    }

    Result write(ExportProgress progress, long slowRegionThresholdMs) {
        Map<RegionCoordinates, List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>>> chunksByRegion =
                new HashMap<>();
        for (var entry : captures.entrySet()) {
            ChunkPos pos = entry.getKey();
            chunksByRegion
                    .computeIfAbsent(new RegionCoordinates(pos.getRegionX(), pos.getRegionZ()),
                            ignored -> new ArrayList<>())
                    .add(entry);
        }
        boolean successful = true;
        for (var entry : chunksByRegion.entrySet()) {
            progress.start(ExportProgress.Part.TERRAIN, dimension,
                    entry.getValue().stream().map(Map.Entry::getKey).toList());
            successful &= new RegionWrite(entry.getKey(), entry.getValue()).write(slowRegionThresholdMs);
        }
        return new Result(written, settled, Set.copyOf(unreadable), successful, timings.databaseLookupNs,
                timings.materializeNs, timings.chunkReadNs, timings.resolveMergeWriteNs, timings.flushNs,
                timings.verificationNs, timings.recoveredCaptureTime, timings.recoveredIndex);
    }

    private final class RegionWrite {
        private final RegionCoordinates coordinates;
        private final List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>> entries;
        private final Path file;
        private final RegionStorageInfo storageInfo =
                new RegionStorageInfo("world_mirror", dimension, "chunk");
        private final StagedRegion staged = new StagedRegion();
        private final Set<ChunkPos> indexedClean = new HashSet<>();
        private final Map<ChunkPos, ChunkDatabase.ChunkRecord> claims = new HashMap<>();
        private RegionFile writer;

        RegionWrite(RegionCoordinates coordinates,
                List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>> entries) {
            this.coordinates = coordinates;
            this.entries = entries;
            file = regionDir.resolve("r." + coordinates.x() + "." + coordinates.z() + ".mca");
        }

        boolean write(long slowRegionThresholdMs) {
            synchronized (McaWriteSupport.lockFor(file)) {
                long started = System.nanoTime();
                try {
                    FileProof.requirePlainPath(worldFolder, file);
                    var verified = RegionMerge.writeVerified(this::stage, this::verify);
                    if (staged.invalidHeader)
                        return false;
                    if (!verified.regionUsable())
                        entries.forEach(entry -> unreadable.add(entry.getKey()));
                    unreadable.addAll(verified.unreadable());
                    unreadable.removeAll(verified.written().keySet());
                    boolean reuseVerified = verified.regionUsable() && verifyReuseAfterFailedWrites();
                    for (var entry : entries)
                        if (unreadable.contains(entry.getKey()))
                            settled.remove(entry.getKey());
                    verified.written().forEach((pos, chunk) -> written.put(pos, chunk.stamp()));
                    if (!verified.complete()) {
                        WMLogger.warn("Region durability verification failed file=" + file + " verified="
                                + verified.written().size() + " failed=" + verified.unreadable().size()
                                + "; failed chunks retained for retry");
                    }
                    return !staged.failed && verified.complete() && reuseVerified;
                } catch (Exception e) {
                    entries.forEach(entry -> settled.remove(entry.getKey()));
                    entries.forEach(entry -> written.remove(entry.getKey()));
                    WMLogger.warn(
                            "Region write failed file=" + file + " retainedChunks=" + entries.size(), e);
                    return false;
                } finally {
                    long elapsed = (System.nanoTime() - started) / 1_000_000L;
                    if (slowRegionThresholdMs >= 0 && elapsed >= slowRegionThresholdMs)
                        WMLogger.info("[perf] slowRegion kind=chunk dimension=" + dimensionId
                                + " file=" + file.getFileName() + " candidates=" + entries.size()
                                + " written=" + staged.chunks.size() + " elapsedMs=" + elapsed);
                }
            }
        }

        private void loadIndexClaims() throws SQLException {
            long started = System.nanoTime();
            for (var row : db.queryRegion(dimensionId, coordinates.x(), coordinates.z())) {
                var pos = new ChunkPos(row.x(), row.z());
                if (captures.containsKey(pos))
                    claims.put(pos, row);
            }
            for (var entry : entries) {
                var pos = entry.getKey();
                var row = claims.get(pos);
                if (row == null)
                    continue;
                long capturedAtMs = entry.getValue().capturedAtMs();
                if (capturedAtMs <= row.updateTime()) {
                    indexedClean.add(pos);
                    continue;
                }
                if (sourcePriorities == null)
                    sourcePriorities = db.sourcePriorities(dimensionId);
                var coordinate = new ChunkIndexStore.ChunkCoordinate(row.x(), row.z());
                var incoming = new ChunkUpdate(dimensionId, coordinate, capturedAtMs, "world_mirror");
                var existing = new ChunkIndexStore.ChunkRecord(
                        row.x(), row.z(), row.updateTime(), row.updateSource());
                if (ChunkUpdatePolicy.assess(incoming, existing, sourcePriorities) == IndexStatus.BLOCKED)
                    indexedClean.add(pos);
            }
            timings.databaseLookupNs += System.nanoTime() - started;
        }

        private boolean verifyReuseAfterFailedWrites() throws IOException {
            // RegionMerge skips reopening if nothing was staged. A stream may still have
            // touched the header before failing, so validate it before indexing reused entries.
            if (writer != null && staged.chunks.isEmpty()) {
                var after = RegionFileIntegrity.inspect(file, coordinates.x(), coordinates.z());
                if (!after.usable()) {
                    entries.forEach(entry -> unreadable.add(entry.getKey()));
                    return false;
                }
                unreadable.addAll(after.invalidChunks());
            }
            staged.reused.forEach((pos, stamp) -> {
                if (!unreadable.contains(pos))
                    written.put(pos, stamp);
            });
            return true;
        }

        private boolean shouldSkip(ChunkPos pos, ChunkListener.CapturedChunk capture) throws SQLException {
            return db.shouldSkipUpdate(dimensionId, pos, "world_mirror", capture.capturedAtMs());
        }

        private Map<ChunkPos, StagedChunk> stage() throws IOException {
            try (var region = AnvilReader.inspect(file, coordinates.x(), coordinates.z());
                    var auxiliary =
                            new ChunkAuxiliaryData(worldFolder, file, coordinates.x(), coordinates.z())) {
                if (!prepareToStage(region.inspection()))
                    return Map.of();
                for (var entry : entries) {
                    ChunkPos pos = entry.getKey();
                    try {
                        stageChunk(region, auxiliary, pos, entry.getValue());
                    } catch (Exception failure) {
                        staged.failed = true;
                        WMLogger.warnRateLimited("chunk-process-" + dimensionId, 30_000L,
                                "Chunk processing failed dimension=" + dimensionId + " chunk=" + pos
                                        + "; retained for retry",
                                failure);
                    }
                }
            } finally {
                if (writer != null) {
                    long started = System.nanoTime();
                    try {
                        // Native close pads and forces the file. An extra flush would force it twice.
                        writer.close();
                    } finally {
                        timings.flushNs += System.nanoTime() - started;
                    }
                }
            }
            return staged.chunks;
        }

        private boolean prepareToStage(RegionFileIntegrity.Inspection inspection) throws IOException {
            if (!inspection.usable() || !inspection.invalidChunks().isEmpty()) {
                if (!inspection.usable())
                    entries.forEach(entry -> unreadable.add(entry.getKey()));
                unreadable.addAll(inspection.invalidChunks());
                WMLogger.warn("Region validation failed before write file=" + file + " reason="
                        + inspection.failure() + " invalidChunks=" + inspection.invalidChunks().size()
                        + "; retainedChunks=" + entries.size());
                staged.invalidHeader = true;
                return false;
            }
            if (blend)
                occupancy.put(coordinates, inspection.presentChunks());
            try {
                loadIndexClaims();
            } catch (SQLException failure) {
                throw new IOException("Cannot read the terrain index", failure);
            }
            return true;
        }

        private RegionMerge.Verification<ChunkPos> verify(Map<ChunkPos, StagedChunk> expected) {
            long started = System.nanoTime();
            try {
                return verifyWrittenRegion(file, coordinates.x(), coordinates.z(), expected);
            } finally {
                timings.verificationNs += System.nanoTime() - started;
            }
        }

        private void stageChunk(AnvilReader.Reader region, ChunkAuxiliaryData auxiliary, ChunkPos pos,
                ChunkListener.CapturedChunk captured) throws Exception {
            TerrainChunkNbt.requireCoordinates(captured.nbt(), pos);
            CompoundTag local = readChunk(region, pos);
            var claim = claims.get(pos);
            boolean generatedClaim = metadata != null && claim != null
                    && ChunkIndexStore.SEED_GENERATED.equals(claim.updateSource());
            boolean unindexed = metadata != null && claim == null;
            if (local != null && (generatedClaim || unindexed)) {
                var receipt = SeedCaptureReceipt.read(local, metadata.nodeId);
                if (receipt != null) {
                    settleReceipt(pos, receipt.source(), receipt.time());
                    if (receipt.time() >= captured.capturedAtMs() || shouldSkip(pos, captured)) {
                        settled.put(pos, stamp(captured));
                        return;
                    }
                    generatedClaim = false;
                }
            }
            boolean seedCandidate = generatedClaim || unindexed;
            var generated = local == null ? null : SeedChunkProvenance.read(local);
            boolean replaceGenerated = seedCandidate && generated != null
                    && SeedChunkProvenance.isUnmodified(local, generated) && !auxiliary.hasData(pos);
            boolean protectedSeed = local != null && !replaceGenerated
                    && (generatedClaim || (seedCandidate && SeedChunkProvenance.hasMarker(local)));
            Action action = ChunkUpdatePolicy.decide(WritePolicy.LIVE_SYNC,
                    local == null ? Destination.MISSING : Destination.PRESENT,
                    indexedClean.contains(pos) ? IndexStatus.BLOCKED : IndexStatus.ELIGIBLE);
            if (action == Action.PRESERVE) {
                settled.put(pos, stamp(captured));
                return;
            }
            if (action == Action.REPAIR_INDEX) {
                // Clear the stale claim in the coordinator before the next write can record its timestamp.
                staged.failed = true;
                unreadable.add(pos);
                WMLogger.warnRateLimited("missing-indexed-chunk-" + dimensionId, 30_000L,
                        "Durability index references unavailable MCA entry dimension=" + dimensionId
                                + " chunk=" + pos + "; retaining capture for repair after index cleanup");
                return;
            }
            if (protectedSeed) {
                settled.put(pos, stamp(captured));
                return;
            }
            if (local != null
                    && local.getIntOr("DataVersion", 0)
                            > net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version())
                throw new IOException("Refusing to replace terrain saved by a newer Minecraft version");
            materializeAndWrite(pos, captured, local, replaceGenerated);
        }

        private CompoundTag readChunk(AnvilReader.Reader region, ChunkPos pos) throws IOException {
            long started = System.nanoTime();
            try {
                CompoundTag local = region.read(pos);
                if (local != null)
                    TerrainChunkNbt.requireCoordinates(local, pos);
                timings.chunkReadNs += System.nanoTime() - started;
                return local;
            } catch (IOException | RuntimeException failure) {
                unreadable.add(pos);
                throw failure;
            }
        }

        private void settleReceipt(ChunkPos pos, String source, long time) throws IOException {
            if (!db.recordUpdates(dimensionId, Map.of(pos, time), source))
                throw new IOException("Could not repair captured seed index receipt");
            timings.recoveredIndex = true;
            if ("world_mirror".equals(source))
                timings.recoveredCaptureTime = Math.max(timings.recoveredCaptureTime, time);
        }

        private void materializeAndWrite(ChunkPos pos, ChunkListener.CapturedChunk capture, CompoundTag local,
                boolean replaceGenerated) throws Exception {
            long started = System.nanoTime();
            var materialized = snapshot.materialize(dimension, pos, capture);
            timings.materializeNs += System.nanoTime() - started;
            CompoundTag chunk = materialized.nbt();
            started = System.nanoTime();
            SeedChunkProvenance.strip(chunk);
            if (seeded)
                SeedTerrainBiomes.repairCapturedBiomes(chunk, dimension);
            BlockEntityNbtSupport.removeIncompatible(chunk, pos);
            BlockEntityNbtSupport.applyContainerOverlays(dimension, chunk, containers);
            boolean existsLocally = local != null && !replaceGenerated;
            if (!resolver.shouldWriteChunk(
                        new ConflictContext(pos, existsLocally, chunk, dimension, worldFolder))) {
                settled.put(pos, stamp(materialized));
                timings.resolveMergeWriteNs += System.nanoTime() - started;
                return;
            }
            if (existsLocally && !materialized.authoritativeEmpty())
                BlockEntityNbtSupport.mergeChunkBlockEntities(chunk, local, pos, dimension, containers);
            if (blend && !chunk.contains(SeedTerrainDigest.BLENDING_DATA)
                    && (SeedTerrainBlending.ownsBlend(local, metadata.generation.seedEpoch())
                            || bordersUnwritten(regionDir, pos, captures.keySet(), occupancy))
                    && SeedTerrainBlending.canDonateBlend(chunk))
                SeedTerrainBlending.markBlend(chunk, metadata.generation.seedEpoch());
            if (replaceGenerated)
                SeedCaptureReceipt.write(chunk, metadata.nodeId, capture.capturedAtMs());
            if (existsLocally && TerrainChunkNbt.equivalent(local, chunk)) {
                staged.reused.put(
                        pos, new WriteStamp(materialized.revision(), materialized.capturedAtMs(), false));
            } else {
                NbtWriteSupport.validateStrings(chunk);
                if (writer == null)
                    writer = new RegionFile(storageInfo, file, regionDir, false);
                var output = writer.getChunkDataOutputStream(pos);
                try {
                    byte[] digest = NbtWriteSupport.writeChunkAndDigest(chunk, output);
                    staged.chunks.put(pos, new StagedChunk(stamp(materialized), digest));
                } catch (IOException | RuntimeException failure) {
                    unreadable.add(pos);
                    throw failure;
                }
            }
            timings.resolveMergeWriteNs += System.nanoTime() - started;
        }
    }

    private static RegionMerge.Verification<ChunkPos> verifyWrittenRegion(
            Path regionFile, int regionX, int regionZ, Map<ChunkPos, StagedChunk> staged) {
        Set<ChunkPos> verified = new HashSet<>();
        Set<ChunkPos> unreadable = new HashSet<>(staged.keySet());
        boolean regionUsable = false;
        try (var reader = AnvilReader.inspect(regionFile, regionX, regionZ)) {
            var inspection = reader.inspection();
            if (!inspection.usable()) {
                return new RegionMerge.Verification<>(false, Set.of(), unreadable);
            }
            unreadable.addAll(inspection.invalidChunks());
            regionUsable = true;
            for (ChunkPos pos : staged.keySet()) {
                if (inspection.invalidChunks().contains(pos))
                    continue;
                if (matchesStagedChunk(reader, pos, staged.get(pos))) {
                    verified.add(pos);
                    unreadable.remove(pos);
                }
            }
        } catch (Exception ignored) {
            // Treat an inspection/open failure as a failed durability check.
        }
        return new RegionMerge.Verification<>(regionUsable, verified, unreadable);
    }

    private static boolean matchesStagedChunk(AnvilReader.Reader reader, ChunkPos pos, StagedChunk staged) {
        try {
            return reader.matches(pos, staged.digest());
        } catch (Exception ignored) {
            // The summary warning contains the region and failure count.
            return false;
        }
    }

    private static boolean bordersUnwritten(Path regionDir, ChunkPos pos, Set<ChunkPos> candidates,
            Map<RegionCoordinates, Set<ChunkPos>> occupancy) {
        int x = pos.getRegionX() * 32 + pos.getRegionLocalX();
        int z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0)
                    continue;
                var neighbor = new ChunkPos(x + dx, z + dz);
                var key = new RegionCoordinates(neighbor.getRegionX(), neighbor.getRegionZ());
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
}
