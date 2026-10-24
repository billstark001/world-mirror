package io.github.billstark001.worldmirror.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.concurrent.ConcurrentHashMap;

/** Spatial metadata index. Owners confine mutations or publish it after construction. */
public final class ChunkRegionIndex<V> {
    private final Map<Long, Map<Long, V>> regions;

    public ChunkRegionIndex() { this(new Long2ObjectOpenHashMap<>()); }
    private ChunkRegionIndex(Map<Long, Map<Long, V>> regions) { this.regions = regions; }

    /** Packed coordinates have collision-heavy Long.hashCode values (x XOR z).
     * Minecraft's fastutil map mixes all 64 bits; Map.copyOf's linear probes can
     * otherwise turn a large dense/diagonal viewport copy into quadratic work.
     */
    public static <V> Map<Long, V> copyCoordinates(Map<Long, V> coordinates) {
        return coordinates.isEmpty() ? Map.of() : Collections.unmodifiableMap(new Long2ObjectOpenHashMap<>(coordinates));
    }

    /** Single-writer staging with immutable region pages for nonblocking viewport reads.
     * Each changed region is copied once per publication, never the whole history.
     * Readers can observe adjacent publication batches while a range is traversed;
     * every individual value and page is immutable and remains safe to retain.
     */
    public static final class Publication<V> {
        private final ChunkRegionIndex<V> visible = new ChunkRegionIndex<>(new ConcurrentHashMap<>());
        private final Map<Long, Map<Long, V>> staged = new Long2ObjectOpenHashMap<>();
        private long copiedRecords;

        /** Writer-only lookup, including unpublished changes. */
        public V get(int x, int z) {
            Map<Long, V> page = staged.get(key(x >> 5, z >> 5));
            return page == null ? visible.get(x, z) : page.get(key(x, z));
        }
        public void put(int x, int z, V value) {
            Objects.requireNonNull(value);
            staged.computeIfAbsent(key(x >> 5, z >> 5), region -> {
                Map<Long, V> old = visible.regions.get(region);
                return old == null ? new Long2ObjectOpenHashMap<>() : new Long2ObjectOpenHashMap<>(old);
            }).put(key(x, z), value);
        }
        public void publish() {
            staged.forEach((region, page) -> {
                visible.regions.put(region, copyCoordinates(page));
                copiedRecords += page.size();
            });
            staged.clear();
        }
        /** Readers never touch writer-owned staging maps or acquire its monitor. */
        public int forEachInRange(int minX, int maxX, int minZ, int maxZ, Consumer<? super V> consumer) {
            return visible.forEachInRange(minX, maxX, minZ, maxZ, consumer);
        }
        long copiedRecords() { return copiedRecords; }
    }

    public boolean put(int x, int z, V value) {
        Objects.requireNonNull(value);
        V previous = regions.computeIfAbsent(key(x >> 5, z >> 5), ignored -> new Long2ObjectOpenHashMap<>())
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
