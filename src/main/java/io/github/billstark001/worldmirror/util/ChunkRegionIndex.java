package io.github.billstark001.worldmirror.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiPredicate;

/** Spatial metadata index. Owners confine mutations or publish it after construction. */
public final class ChunkRegionIndex<V> {
    private final Map<Long, Map<Long, V>> regions = new HashMap<>();

    public boolean put(int x, int z, V value) {
        Objects.requireNonNull(value);
        V previous = regions.computeIfAbsent(key(x >> 5, z >> 5), ignored -> new HashMap<>())
                .put(key(x, z), value);
        return !Objects.equals(previous, value);
    }

    public boolean remove(int x, int z) {
        long regionKey = key(x >> 5, z >> 5);
        Map<Long, V> region = regions.get(regionKey);
        if (region == null || region.remove(key(x, z)) == null) return false;
        if (region.isEmpty()) regions.remove(regionKey);
        return true;
    }

    /** Returns the number of candidate records visited, for bounded-work diagnostics. */
    public int forEachInRange(int minX, int maxX, int minZ, int maxZ, Consumer<? super V> consumer) {
        return forEachInRange(minX, maxX, minZ, maxZ, (x, z) -> true, consumer);
    }

    /** Filters whole regions before visiting records, useful for a sparse set of cold buckets. */
    public int forEachInRange(int minX, int maxX, int minZ, int maxZ,
                              BiPredicate<Integer, Integer> regionFilter, Consumer<? super V> consumer) {
        if (minX > maxX || minZ > maxZ || regions.isEmpty()) return 0;
        int minRX = minX >> 5, maxRX = maxX >> 5;
        int minRZ = minZ >> 5, maxRZ = maxZ >> 5;
        long area = ((long) maxRX - minRX + 1L) * ((long) maxRZ - minRZ + 1L);
        int visited = 0;
        if (area > 4L * regions.size()) {
            for (var entry : regions.entrySet()) {
                int x = (int) (entry.getKey() >> 32), z = (int) (long) entry.getKey();
                if (x >= minRX && x <= maxRX && z >= minRZ && z <= maxRZ && regionFilter.test(x, z)) {
                    visited += visit(entry.getValue(), minX, maxX, minZ, maxZ, consumer);
                }
            }
        } else {
            for (int x = minRX; x <= maxRX; x++) {
                for (int z = minRZ; z <= maxRZ; z++) {
                    Map<Long, V> region = regions.get(key(x, z));
                    if (region != null && regionFilter.test(x, z)) visited += visit(region, minX, maxX, minZ, maxZ, consumer);
                }
            }
        }
        return visited;
    }

    public V get(int x, int z) {
        Map<Long, V> region = regions.get(key(x >> 5, z >> 5));
        return region == null ? null : region.get(key(x, z));
    }
    private int visit(Map<Long, V> region, int minX, int maxX, int minZ, int maxZ,
                      Consumer<? super V> consumer) {
        for (var entry : region.entrySet()) {
            int x = (int) (entry.getKey() >> 32), z = (int) (long) entry.getKey();
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) consumer.accept(entry.getValue());
        }
        return region.size();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
