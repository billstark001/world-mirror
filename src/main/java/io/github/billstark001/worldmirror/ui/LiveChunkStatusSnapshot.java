package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Small, viewport-scoped copy of live chunk status, with no chunk NBT. */
public final class LiveChunkStatusSnapshot {
    public static final LiveChunkStatusSnapshot EMPTY = new LiveChunkStatusSnapshot(Map.of());

    public enum State { CACHED, PENDING_WRITE, PENDING_CAPTURE }

    public record Record(int x, int z, long capturedAtMs, State state,
                         boolean hasSavedRecord) { }

    private final Map<Long, Record> records;
    private final Map<Integer, Map<Long, Record>> bucketsBySize = new HashMap<>();

    private LiveChunkStatusSnapshot(Map<Long, Record> records) {
        this.records = Map.copyOf(records);
    }

    public static LiveChunkStatusSnapshot capture(ResourceKey<Level> dimension,
                                                   ChunkMapView.ChunkRange range,
                                                   ChunkStatusSnapshot persistent) {
        if (dimension == null) return EMPTY;
        Map<Long, Record> records = new HashMap<>();
        for (ChunkListener.MapChunkStatus chunk : ChunkListener.snapshotMapStatusInRange(
                dimension, range.minX(), range.maxX(), range.minZ(), range.maxZ())) {
            var saved = persistent.getRecord(chunk.x(), chunk.z());
            if (!chunk.pendingWrite() && saved != null
                    && saved.updateTime() >= chunk.capturedAtMs()) continue;
            State state = chunk.pendingWrite() ? State.PENDING_WRITE : State.CACHED;
            records.put(ChunkStatusSnapshot.chunkKey(chunk.x(), chunk.z()),
                    new Record(chunk.x(), chunk.z(), chunk.capturedAtMs(), state,
                            saved != null));
        }
        for (ChunkPos pos : DownloadManager.pendingChunkCaptures(
                dimension, range.minX(), range.maxX(), range.minZ(), range.maxZ())) {
            int x = pos.getMinBlockX() >> 4;
            int z = pos.getMinBlockZ() >> 4;
            records.put(ChunkStatusSnapshot.chunkKey(x, z),
                    new Record(x, z, 0L, State.PENDING_CAPTURE,
                            persistent.getRecord(x, z) != null));
        }
        return new LiveChunkStatusSnapshot(records);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public Record getRecord(int x, int z) {
        return records.get(ChunkStatusSnapshot.chunkKey(x, z));
    }

    public void forEachRecord(Consumer<Record> consumer) {
        records.values().forEach(consumer);
    }

    /** Reuse low-zoom bucket selection for every frame of this immutable snapshot. */
    public synchronized Map<Long, Record> bucketRecords(int bucketSize) {
        if (bucketSize == 1) return records;
        return bucketsBySize.computeIfAbsent(bucketSize, size -> {
            Map<Long, Record> buckets = new HashMap<>();
            for (Record record : records.values()) {
                long key = ChunkStatusSnapshot.chunkKey(
                        Math.floorDiv(record.x(), size), Math.floorDiv(record.z(), size));
                Record previous = buckets.get(key);
                if (previous == null || record.state().ordinal() > previous.state().ordinal()
                        || (record.state() == previous.state()
                        && (!record.hasSavedRecord() && previous.hasSavedRecord()
                        || record.hasSavedRecord() == previous.hasSavedRecord()
                        && record.capturedAtMs() > previous.capturedAtMs()))) {
                    buckets.put(key, record);
                }
            }
            return Map.copyOf(buckets);
        });
    }
}
