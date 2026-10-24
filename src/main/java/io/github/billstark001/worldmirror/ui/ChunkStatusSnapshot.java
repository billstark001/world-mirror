package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;
import net.minecraft.world.level.ChunkPos;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Immutable source data. Spatial queries and bucket memoization belong to its index. */
public final class ChunkStatusSnapshot {
    public static final ChunkStatusSnapshot EMPTY = new ChunkStatusSnapshot(List.of(), Set.of());
    private final ChunkBucketIndex index;
    private final List<ChunkRecord> records;
    private final int conflictCount;

    public ChunkStatusSnapshot(Collection<ChunkRecord> records, Collection<ChunkPos> conflicts) {
        Map<Long, ChunkBucketIndex.Fact> facts = new HashMap<>();
        for (ChunkRecord record : records) facts.put(ChunkBounds.key(record.x(), record.z()),
                new ChunkBucketIndex.Fact(record.x(), record.z(), record, false, null));
        int count = 0;
        for (ChunkPos pos : conflicts) {
            int x = pos.getRegionX() * 32 + pos.getRegionLocalX(), z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
            long key = ChunkBounds.key(x, z);
            var previous = facts.get(key);
            if (previous != null && previous.conflict()) continue;
            count++;
            facts.put(key, new ChunkBucketIndex.Fact(x, z, previous == null ? null : previous.saved(), true, null));
        }
        this.records = facts.values().stream().map(ChunkBucketIndex.Fact::saved).filter(java.util.Objects::nonNull).toList();
        this.index = new ChunkBucketIndex(facts.values());
        this.conflictCount = count;
    }
    public int recordCount() { return records.size(); }
    public Collection<ChunkRecord> records() { return records; }
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
    public void forEachRecordInRange(int minX, int maxX, int minZ, int maxZ, Consumer<ChunkRecord> consumer) {
        index.forEach(new ChunkBounds(minX, maxX, minZ, maxZ), fact -> {
            if (fact.saved() != null) consumer.accept(fact.saved());
        });
    }
    public void forEachConflictInRange(int minX, int maxX, int minZ, int maxZ, Consumer<ChunkPos> consumer) {
        forEachConflictCoordinatesInRange(minX, maxX, minZ, maxZ, (x, z) -> consumer.accept(new ChunkPos(x, z)));
    }
    public void forEachConflictCoordinatesInRange(int minX, int maxX, int minZ, int maxZ,
                                                  java.util.function.BiConsumer<Integer, Integer> consumer) {
        index.forEach(new ChunkBounds(minX, maxX, minZ, maxZ), fact -> {
            if (fact.conflict()) consumer.accept(fact.x(), fact.z());
        });
    }
}
