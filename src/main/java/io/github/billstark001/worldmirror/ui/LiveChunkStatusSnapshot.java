package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.util.ChunkRegionIndex;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.ExportStatus;
import io.github.billstark001.worldmirror.download.ExportStatusStore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Small, viewport-scoped copy of live chunk status, with no chunk NBT. */
public final class LiveChunkStatusSnapshot {
    public static final LiveChunkStatusSnapshot EMPTY = new LiveChunkStatusSnapshot(Map.of(), Map.of());

    public enum State { CACHED, PENDING_WRITE, PENDING_CAPTURE }

    public record Record(int x, int z, long capturedAtMs, State state,
                         boolean hasSavedRecord) { }

    private final Map<Long, Record> records;
    private final ChunkBucketIndex index;
    private final Map<Long, ExportStatus.Chunk> exports;

    LiveChunkStatusSnapshot(Map<Long, Record> records, Map<Long, ExportStatus.Chunk> exports) {
        this.records = ChunkRegionIndex.copyCoordinates(records);
        this.exports = ChunkRegionIndex.copyCoordinates(exports);
        Map<Long, ChunkBucketIndex.Fact> facts = new Long2ObjectOpenHashMap<>();
        exports.forEach((key, status) -> facts.put(key,
                new ChunkBucketIndex.Fact(status.x(), status.z(), null, false, records.get(key), status)));
        records.forEach((key, record) -> facts.put(key,
                new ChunkBucketIndex.Fact(record.x(), record.z(), null, false, record, exports.get(key))));
        this.index = new ChunkBucketIndex(facts.values());
    }

    public static LiveChunkStatusSnapshot capture(ResourceKey<Level> dimension,
                                                   ChunkBounds range,
                                                   ChunkStatusSnapshot persistent, String source) {
        if (dimension == null) return EMPTY;
        Map<Long, Record> records = new Long2ObjectOpenHashMap<>();
        Map<Long, ExportStatus.Chunk> exports = new Long2ObjectOpenHashMap<>();
        ExportStatusStore.global().inRange(source, dimension, range.minX(), range.maxX(), range.minZ(), range.maxZ())
                .forEach(status -> exports.put(ChunkBounds.key(status.x(), status.z()), status));
        for (ChunkListener.MapChunkStatus chunk : ChunkListener.snapshotMapStatusInRange(
                dimension, range.minX(), range.maxX(), range.minZ(), range.maxZ())) {
            if (chunk.pendingWrite()) exports.compute(ChunkBounds.key(chunk.x(), chunk.z()), (key, old) ->
                    old == null ? ExportStatus.Chunk.queued(chunk.x(), chunk.z(), chunk.revision()) : old.withTerrain(chunk.revision()));
            var saved = persistent.getRecord(chunk.x(), chunk.z());
            if (!chunk.pendingWrite() && saved != null
                    && saved.updateTime() >= chunk.capturedAtMs()) continue;
            State state = chunk.pendingWrite() ? State.PENDING_WRITE : State.CACHED;
            records.put(ChunkBounds.key(chunk.x(), chunk.z()),
                    new Record(chunk.x(), chunk.z(), chunk.capturedAtMs(), state,
                            saved != null));
        }
        for (ChunkPos pos : DownloadManager.pendingChunkCaptures(
                dimension, range.minX(), range.maxX(), range.minZ(), range.maxZ())) {
            int x = pos.getMinBlockX() >> 4;
            int z = pos.getMinBlockZ() >> 4;
            records.put(ChunkBounds.key(x, z),
                    new Record(x, z, 0L, State.PENDING_CAPTURE,
                            persistent.getRecord(x, z) != null));
        }
        return new LiveChunkStatusSnapshot(records, exports);
    }

    public boolean isEmpty() {
        return records.isEmpty() && exports.isEmpty();
    }

    public Record getRecord(int x, int z) {
        return records.get(ChunkBounds.key(x, z));
    }

    public ExportStatus.Chunk exportAt(int x, int z) { return exports.get(ChunkBounds.key(x, z)); }

    public void forEachRecord(Consumer<Record> consumer) {
        records.values().forEach(consumer);
    }

    ChunkBucketIndex index() { return index; }
}
