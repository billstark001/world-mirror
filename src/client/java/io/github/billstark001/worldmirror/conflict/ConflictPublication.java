package io.github.billstark001.worldmirror.conflict;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkUpdate;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.SeedCaptureReceipt;
import io.github.billstark001.worldmirror.io.TerrainChunkNbt;
import io.github.billstark001.worldmirror.legacy.LegacyBlockEntityRepair;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Applies a frozen conflict with its index receipt under the native save lease. */
final class ConflictPublication {
    private ConflictPublication() {}

    record Publication(Map<ChunkPos, CompoundTag> chunks, Map<ChunkPos, SeedCaptureReceipt.Receipt> receipts,
            Set<ChunkPos> rejected) {}

    static Publication prepare(MirrorTarget mirror, Map<ChunkPos, CompoundTag> snapshot) {
        var publication = new Publication(new HashMap<>(), new HashMap<>(), new HashSet<>());
        snapshot.forEach((pos, raw) -> {
            try {
                prepareChunk(mirror, publication, pos, raw);
            } catch (IOException | RuntimeException failure) {
                publication.rejected().add(pos);
                WMLogger.warnRateLimited("invalid-conflict:" + mirror.folder(), 30_000L,
                        "Cannot publish pending terrain world=" + mirror.folder() + " chunk=" + pos, failure);
            }
        });
        return publication;
    }

    private static void prepareChunk(
            MirrorTarget mirror, Publication publication, ChunkPos pos, CompoundTag raw) throws IOException {
        var receipt = SeedCaptureReceipt.read(raw, mirror.nodeId());
        if (receipt == null && SeedCaptureReceipt.hasReceipt(raw))
            throw new IOException("Conflict has an invalid or foreign capture receipt");
        TerrainChunkNbt.requireCoordinates(raw, pos);
        if ((receipt != null || raw.contains("DataVersion")) && !TerrainChunkNbt.hasSupportedDataVersion(raw))
            throw new IOException("Conflict has an unsupported terrain version");
        var cleaned = LegacyBlockEntityRepair.sanitizedSavedCapture(raw, pos);
        if (receipt != null) {
            if (!TerrainChunkNbt.coordinatesMatch(raw, pos))
                throw new IOException("Conflict receipt is missing terrain coordinates");
            if (cleaned != raw)
                SeedCaptureReceipt.write(cleaned, mirror.nodeId(), receipt);
            publication.receipts().put(pos, receipt);
        }
        publication.chunks().put(pos, cleaned);
    }

    static Set<ChunkPos> write(MirrorTarget mirror, ConflictManager.ConflictRegion region, Path target,
            Publication publication) throws IOException {
        try {
            if (publication.chunks().isEmpty())
                return publication.rejected();
            if (publication.receipts().isEmpty())
                return publish(mirror, region, target, publication, null);
            try (var db = ChunkDatabase.open(mirror.folder(), mirror.sourceId())) {
                db.syncCommits();
                return publish(mirror, region, target, publication, db);
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot verify the conflict publication index", failure);
        }
    }

    private static Set<ChunkPos> publish(MirrorTarget mirror, ConflictManager.ConflictRegion region,
            Path target, Publication publication, ChunkDatabase db) throws IOException, SQLException {
        Map<ChunkPos, CompoundTag> physical = new HashMap<>();
        Set<ChunkPos> protectedPositions = new HashSet<>(publication.rejected());
        var dim = region.dimension().identifier().toString();
        Map<ChunkPos, ChunkDatabase.ChunkRecord> rows = new HashMap<>();
        if (db != null)
            for (var row : db.queryRegion(dim, region.x(), region.z()))
                rows.put(new ChunkPos(row.x(), row.z()), row);
        Map<String, Integer> priorities = db == null ? Map.of() : db.sourcePriorities(dim);
        long latestCaptureTime = 0;
        try (var reader = AnvilReader.open(target, region.x(), region.z())) {
            for (var entry : publication.chunks().entrySet()) {
                var pos = entry.getKey();
                var receipt = publication.receipts().get(pos);
                var row = rows.get(pos);
                var saved = reader.read(pos);
                if (blocked(saved, entry.getValue(), receipt, row, priorities, dim)) {
                    protectedPositions.add(pos);
                    continue;
                }
                if (receipt != null && receipt.source().equals("world_mirror"))
                    latestCaptureTime = Math.max(latestCaptureTime, receipt.time());
                if (receipt != null && row != null && matches(row, receipt))
                    continue; // A committed publication can be retired after a native resave.
                if (!entry.getValue().equals(saved))
                    physical.put(pos, entry.getValue());
            }
        }
        publication.receipts().keySet().removeAll(protectedPositions);
        publication.receipts().entrySet().removeIf(entry -> {
            var row = rows.get(entry.getKey());
            return row != null && matches(row, entry.getValue());
        });
        McaWriteSupport.writeChunks(target, region.dimension(), physical);
        if (db != null)
            publishIndex(db, region, publication.receipts());
        if (latestCaptureTime > 0)
            publishSyncTime(mirror, latestCaptureTime);
        if (!protectedPositions.isEmpty())
            WMLogger.warnRateLimited("protected-conflicts:" + region.file(), 30_000L,
                    "Retained conflicting captures where publication could not be verified file="
                            + region.file() + " chunks=" + protectedPositions.size());
        return protectedPositions;
    }

    private static void publishSyncTime(MirrorTarget mirror, long latestCaptureTime) throws IOException {
        var metadata =
                WorldMetadata.loadIfPresent(mirror.folder())
                        .orElseThrow(
                                ()
                                        -> new IOException(
                                                "Cannot read mirror metadata after conflict publication"));
        if (latestCaptureTime > metadata.lastSyncTime
                && !metadata.markSyncComplete(mirror.folder(), latestCaptureTime))
            throw new IOException("Cannot record the applied conflict capture time");
    }

    private static boolean blocked(CompoundTag saved, CompoundTag incoming,
            SeedCaptureReceipt.Receipt receipt, ChunkDatabase.ChunkRecord row,
            Map<String, Integer> priorities, String dim) {
        if (saved != null
                && saved.getIntOr("DataVersion", 0)
                        > SharedConstants.getCurrentVersion().dataVersion().version())
            return true;
        if (receipt == null || row == null)
            return false;
        if (matches(row, receipt))
            return saved == null
                    || (!incoming.equals(saved) && !SeedCaptureReceipt.sameAttachment(saved, incoming));
        var coordinate = new ChunkIndexStore.ChunkCoordinate(row.x(), row.z());
        var update = new ChunkUpdate(dim, coordinate, receipt.time(), receipt.source());
        var existing =
                new ChunkIndexStore.ChunkRecord(row.x(), row.z(), row.updateTime(), row.updateSource());
        return ChunkUpdatePolicy.assess(update, existing, priorities)
                != ChunkUpdatePolicy.IndexStatus.ELIGIBLE;
    }

    private static void publishIndex(ChunkDatabase db, ConflictManager.ConflictRegion region,
            Map<ChunkPos, SeedCaptureReceipt.Receipt> receipts) throws IOException, SQLException {
        if (receipts.isEmpty())
            return;
        var dim = region.dimension().identifier().toString();
        Map<String, Map<ChunkPos, Long>> bySource = new HashMap<>();
        receipts.forEach((pos, receipt)
                                 -> bySource.computeIfAbsent(receipt.source(), ignored -> new HashMap<>())
                                         .put(pos, receipt.time()));
        for (var entry : bySource.entrySet())
            if (!db.recordUpdates(dim, entry.getValue(), entry.getKey()))
                throw new IOException("Cannot publish the applied conflict to the chunk index");
        int matched = 0;
        for (var row : db.queryRegion(dim, region.x(), region.z())) {
            var receipt = receipts.get(new ChunkPos(row.x(), row.z()));
            if (receipt != null && matches(row, receipt))
                matched++;
        }
        if (matched != receipts.size())
            throw new IOException("Could not verify all applied captures in the chunk index");
    }

    private static boolean matches(ChunkDatabase.ChunkRecord row, SeedCaptureReceipt.Receipt receipt) {
        return row.updateTime() == receipt.time() && row.updateSource().equals(receipt.source());
    }
}
