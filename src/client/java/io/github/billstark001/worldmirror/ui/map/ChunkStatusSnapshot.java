package io.github.billstark001.worldmirror.ui.map;

import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;
import net.minecraft.world.level.ChunkPos;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Immutable source data. Spatial queries and bucket memoization belong to its index. */
public final class ChunkStatusSnapshot {
    public static final ChunkStatusSnapshot EMPTY = new ChunkStatusSnapshot(Set.of(), Set.of());
    private final ChunkBucketIndex index;
    private final int recordCount;
    private final int conflictCount;

    public ChunkStatusSnapshot(Collection<ChunkRecord> records, Collection<ChunkPos> conflicts) {
        Map<Long, ChunkBucketIndex.Fact> facts = new Long2ObjectOpenHashMap<>();
        for (ChunkRecord record : records) facts.put(ChunkBounds.key(record.x(), record.z()),
                new ChunkBucketIndex.Fact(record.x(), record.z(), record, false, null, null));
        this.recordCount = facts.size();
        int count = 0;
        for (ChunkPos pos : conflicts) {
            int x = pos.getRegionX() * 32 + pos.getRegionLocalX(), z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
            long key = ChunkBounds.key(x, z);
            var previous = facts.get(key);
            if (previous != null && previous.conflict()) continue;
            count++;
            facts.put(key, new ChunkBucketIndex.Fact(x, z, previous == null ? null : previous.saved(), true, null, null));
        }
        this.index = new ChunkBucketIndex(facts.values());
        this.conflictCount = count;
    }
    int recordCount() { return recordCount; }
    ChunkBucketIndex index() { return index; }
    public ChunkRecord getRecord(int x, int z) {
        var fact = index.get(x, z);
        return fact == null ? null : fact.saved();
    }
    public boolean hasConflict(int x, int z) {
        var fact = index.get(x, z);
        return fact != null && fact.conflict();
    }
    boolean matchesData(Collection<ChunkRecord> records, Collection<ChunkPos> conflicts) {
        if (records.size() != recordCount() || conflicts.size() != conflictCount) return false;
        for (var record : records) if (!record.equals(getRecord(record.x(), record.z()))) return false;
        for (ChunkPos pos : conflicts) {
            if (!hasConflict(pos.getRegionX() * 32 + pos.getRegionLocalX(), pos.getRegionZ() * 32 + pos.getRegionLocalZ())) return false;
        }
        return true;
    }
}
