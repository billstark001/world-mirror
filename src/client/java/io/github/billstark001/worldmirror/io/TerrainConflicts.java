package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.core.ChunkListener.CapturedChunk;
import io.github.billstark001.worldmirror.io.ChunkExporter.WriteStamp;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Buffers server terrain for one pending conflict region. */
final class TerrainConflicts {
    private static final long BATCH_BYTES = 32L * 1024 * 1024;
    private final Map<ChunkPos, CompoundTag> incoming = new HashMap<>();
    private final Map<ChunkPos, WriteStamp> receipts = new HashMap<>();
    private long bytes;

    void add(ChunkPos pos, CapturedChunk capture, String nodeId) {
        CompoundTag chunk = capture.nbt();
        if (nodeId != null)
            SeedCaptureReceipt.write(chunk, nodeId, capture.capturedAtMs());
        incoming.put(pos, chunk);
        receipts.put(pos, new WriteStamp(capture.revision(), capture.capturedAtMs()));
        bytes += chunk.sizeInBytes();
    }

    boolean needsFlush() {
        return bytes >= BATCH_BYTES;
    }

    int flush(Path folder, ResourceKey<Level> dimension, String nodeId, Map<ChunkPos, WriteStamp> settled) {
        if (incoming.isEmpty())
            return 0;
        try {
            var verified = ConflictManager.stageConflicts(folder, dimension, incoming, nodeId);
            verified.forEach(pos -> settled.put(pos, receipts.get(pos)));
            return verified.size();
        } finally {
            incoming.clear();
            receipts.clear();
            bytes = 0;
        }
    }

    int size() {
        return incoming.size();
    }
}
