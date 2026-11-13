package io.github.billstark001.worldmirror.ui.map;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.ExportStatus;
import io.github.billstark001.worldmirror.download.ExportStatusStore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Map;

/** Small, viewport-scoped copy of live chunk status, with no chunk NBT. */
public final class LiveChunkStatusSnapshot {
    public static final LiveChunkStatusSnapshot EMPTY = new LiveChunkStatusSnapshot(
            new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>());

    public enum State { CACHED, PENDING_WRITE, PENDING_CAPTURE }

    public record Record(int x, int z, long capturedAtMs, State state,
                         boolean hasSavedRecord) { }

    private final Long2ObjectMap<Record> records;
    private final ChunkBucketIndex index;
    private final Long2ObjectMap<ExportStatus.Chunk> exports;

    LiveChunkStatusSnapshot(Map<Long, Record> records, Map<Long, ExportStatus.Chunk> exports) {
        this(new Long2ObjectOpenHashMap<>(records), new Long2ObjectOpenHashMap<>(exports));
    }

    /** Takes ownership of the maps, which are retained privately for point lookups. */
    private LiveChunkStatusSnapshot(Long2ObjectOpenHashMap<Record> records,
                                    Long2ObjectOpenHashMap<ExportStatus.Chunk> exports) {
        this.records = records;
        this.exports = exports;
        var facts = new ArrayList<ChunkBucketIndex.Fact>(Math.max(records.size(), exports.size()));
        for (var entry : Long2ObjectMaps.fastIterable(exports)) {
            if (records.containsKey(entry.getLongKey())) continue;
            var status = entry.getValue();
            facts.add(new ChunkBucketIndex.Fact(status.x(), status.z(), null, false, null, status));
        }
        for (var entry : Long2ObjectMaps.fastIterable(records)) {
            var record = entry.getValue();
            facts.add(new ChunkBucketIndex.Fact(record.x(), record.z(), null, false, record, exports.get(entry.getLongKey())));
        }
        this.index = new ChunkBucketIndex(facts);
    }

    public static LiveChunkStatusSnapshot capture(ResourceKey<Level> dimension,
                                                   ChunkBounds range,
                                                   ChunkStatusSnapshot persistent, String nodeId, boolean includeLive) {
        if (dimension == null) return EMPTY;
        var records = new Long2ObjectOpenHashMap<Record>();
        var exports = new Long2ObjectOpenHashMap<ExportStatus.Chunk>();
        ExportStatusStore.global().inRange(nodeId, dimension, range.minX(), range.maxX(), range.minZ(), range.maxZ())
                .forEach(status -> exports.put(ChunkBounds.key(status.x(), status.z()), status));
        if (!includeLive) return exports.isEmpty() ? EMPTY : new LiveChunkStatusSnapshot(records, exports);
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
        return records.isEmpty() && exports.isEmpty() ? EMPTY : new LiveChunkStatusSnapshot(records, exports);
    }

    public Record getRecord(int x, int z) {
        return records.get(ChunkBounds.key(x, z));
    }

    public ExportStatus.Chunk exportAt(int x, int z) { return exports.get(ChunkBounds.key(x, z)); }

    ChunkBucketIndex index() { return index; }
}
