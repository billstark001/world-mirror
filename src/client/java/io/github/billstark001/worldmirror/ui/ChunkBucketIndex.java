package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;
import io.github.billstark001.worldmirror.download.ExportStatus;
import io.github.billstark001.worldmirror.util.ChunkRegionIndex;

import java.util.Collection;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Immutable spatial facts with a bounded, color-independent cache of complete buckets. */
final class ChunkBucketIndex {
    private static final int MAX_CACHED_BUCKETS = 16_384;
    private final ChunkRegionIndex<Fact> facts = new ChunkRegionIndex<>();
    private final boolean empty;
    private final Map<BucketKey, Summary> buckets = new LinkedHashMap<>(128, 0.75F, true);
    private long visits;

    record Fact(int x, int z, ChunkRecord saved, boolean conflict, LiveChunkStatusSnapshot.Record live, ExportStatus.Chunk export) { }
    private record BucketKey(int size, int x, int z) { }

    /** Preserve independent winners for latest-record MAP and priority-based SESSION. */
    record Summary(ChunkRecord newest, long newestMirrorTime, boolean historicalCoverage,
                   boolean conflict, LiveChunkStatusSnapshot.Record live,
                   long newestLiveTime, boolean pendingCapture, ExportStatus.Counts exports) {
        static final Summary EMPTY = new Summary(null, Long.MIN_VALUE, false, false, null, Long.MIN_VALUE, false, ExportStatus.Counts.EMPTY);
        boolean isEmpty() { return newest == null && !conflict && live == null && exports.total() == 0; }

        Summary merge(Summary other) {
            return new Summary(newer(newest, other.newest), Math.max(newestMirrorTime, other.newestMirrorTime),
                    historicalCoverage || other.historicalCoverage, conflict || other.conflict,
                    preferredLive(live, other.live), Math.max(newestLiveTime, other.newestLiveTime),
                    pendingCapture || other.pendingCapture, exports.plus(other.exports));
        }
    }

    ChunkBucketIndex(Collection<Fact> entries) {
        empty = entries.isEmpty();
        for (Fact fact : entries) facts.put(fact.x(), fact.z(), fact);
    }

    Fact get(int x, int z) { return facts.get(x, z); }

    void forEach(ChunkBounds range, Consumer<Fact> consumer) {
        facts.forEachInRange(range.minX(), range.maxX(), range.minZ(), range.maxZ(), consumer);
    }

    /** Partial edge buckets are computed from their exact intersection and never cached. */
    synchronized Map<Long, Summary> query(ChunkBucketLayout layout) {
        if (empty || layout.bounds().isEmpty()) return Map.of();
        Map<Long, Summary> result = new Long2ObjectOpenHashMap<>();
        Map<Long, Builder> cold = new Long2ObjectOpenHashMap<>();
        ChunkBounds range = layout.bounds();
        int size = layout.size();
        if (size == 1) {
            visits += facts.forEachInRange(range.minX(), range.maxX(), range.minZ(), range.maxZ(),
                    fact -> result.put(ChunkBounds.key(fact.x(), fact.z()), summarize(fact)));
            return ChunkRegionIndex.copyCoordinates(result);
        }
        for (long x = Math.floorDiv(range.minX(), size); x <= Math.floorDiv(range.maxX(), size); x++) {
            for (long z = Math.floorDiv(range.minZ(), size); z <= Math.floorDiv(range.maxZ(), size); z++) {
                int bx = (int) x, bz = (int) z;
                long key = ChunkBounds.key(bx, bz);
                if (range.contains(layout.bucket(bx, bz))) {
                    Summary cached = buckets.get(new BucketKey(size, bx, bz));
                    if (cached == null) cold.put(key, new Builder());
                    else if (!cached.isEmpty()) result.put(key, cached);
                } else {
                    Builder builder = new Builder();
                    ChunkBounds clipped = layout.clipped(bx, bz);
                    long width = (long) clipped.maxX() - clipped.minX() + 1;
                    long height = (long) clipped.maxZ() - clipped.minZ() + 1;
                    if (height > 0 && width <= 1024L / height) {
                        for (long px = clipped.minX(); px <= clipped.maxX(); px++) {
                            for (long pz = clipped.minZ(); pz <= clipped.maxZ(); pz++) {
                                visits++;
                                Fact fact = facts.get((int) px, (int) pz);
                                if (fact != null) builder.add(fact);
                            }
                        }
                    } else {
                        visits += facts.forEachInRange(clipped.minX(), clipped.maxX(), clipped.minZ(), clipped.maxZ(), builder::add);
                    }
                    Summary summary = builder.summary;
                    if (!summary.isEmpty()) result.put(key, summary);
                }
            }
        }
        if (!cold.isEmpty()) {
            // A diagonal pan creates an L-shaped cold set. Filter whole regions instead
            // of rescanning the warm interior inside that set's bounding rectangle.
            visits += facts.forEachInRange(range.minX(), range.maxX(), range.minZ(), range.maxZ(),
                    (rx, rz) -> intersectsColdRegion(rx, rz, size, cold), fact -> {
                        Builder builder = cold.get(ChunkBounds.key(Math.floorDiv(fact.x(), size), Math.floorDiv(fact.z(), size)));
                        if (builder != null) builder.add(fact);
                    });
            cold.forEach((key, builder) -> {
                Summary summary = builder.summary;
                buckets.put(new BucketKey(size, ChunkBounds.x(key), ChunkBounds.z(key)), summary);
                if (!summary.isEmpty()) result.put(key, summary);
            });
            while (buckets.size() > MAX_CACHED_BUCKETS) buckets.remove(buckets.keySet().iterator().next());
        }
        return ChunkRegionIndex.copyCoordinates(result);
    }

    static Map<Long, Summary> combine(ChunkBucketIndex saved, ChunkBucketIndex live, ChunkBucketLayout layout) {
        Map<Long, Summary> combined = new Long2ObjectOpenHashMap<>(saved.query(layout));
        live.query(layout).forEach((key, summary) -> combined.merge(key, summary, Summary::merge));
        return combined;
    }

    private static boolean intersectsColdRegion(int rx, int rz, int size, Map<Long, Builder> cold) {
        int minX = Math.floorDiv(rx * 32, size), maxX = Math.floorDiv(rx * 32 + 31, size);
        int minZ = Math.floorDiv(rz * 32, size), maxZ = Math.floorDiv(rz * 32 + 31, size);
        for (long x = minX; x <= maxX; x++) {
            for (long z = minZ; z <= maxZ; z++) if (cold.containsKey(ChunkBounds.key((int) x, (int) z))) return true;
        }
        return false;
    }

    synchronized long visits() { return visits; }
    synchronized int cachedBuckets() { return buckets.size(); }

    private static Summary summarize(Fact fact) {
        if (fact == null) return Summary.EMPTY;
        ChunkRecord saved = fact.saved();
        var live = fact.live();
        boolean mirror = saved != null && "world_mirror".equals(saved.updateSource());
        boolean replay = saved != null && "replay_import".equals(saved.updateSource());
        return new Summary(saved, mirror ? saved.updateTime() : Long.MIN_VALUE, mirror || replay,
                fact.conflict(), live, live == null ? Long.MIN_VALUE : live.capturedAtMs(),
                live != null && live.state() == LiveChunkStatusSnapshot.State.PENDING_CAPTURE,
                fact.export() == null ? ExportStatus.Counts.EMPTY : fact.export().counts());
    }

    private static ChunkRecord newer(ChunkRecord a, ChunkRecord b) {
        if (a == null) return b;
        if (b == null) return a;
        int comparison = Long.compare(a.updateTime(), b.updateTime());
        if (comparison == 0) comparison = Integer.compare(a.x(), b.x());
        if (comparison == 0) comparison = Integer.compare(a.z(), b.z());
        if (comparison == 0) comparison = a.updateSource().compareTo(b.updateSource());
        return comparison >= 0 ? a : b;
    }

    private static LiveChunkStatusSnapshot.Record preferredLive(LiveChunkStatusSnapshot.Record a, LiveChunkStatusSnapshot.Record b) {
        if (a == null) return b;
        if (b == null) return a;
        int comparison = Integer.compare(livePriority(a.state()), livePriority(b.state()));
        if (comparison == 0) comparison = Boolean.compare(!a.hasSavedRecord(), !b.hasSavedRecord());
        if (comparison == 0) comparison = Long.compare(a.capturedAtMs(), b.capturedAtMs());
        if (comparison == 0) comparison = Integer.compare(a.x(), b.x());
        if (comparison == 0) comparison = Integer.compare(a.z(), b.z());
        return comparison >= 0 ? a : b;
    }

    private static int livePriority(LiveChunkStatusSnapshot.State state) {
        return switch (state) { case CACHED -> 0; case PENDING_WRITE -> 1; case PENDING_CAPTURE -> 2; };
    }

    private static final class Builder {
        private Summary summary = Summary.EMPTY;
        void add(Fact fact) { summary = summary.merge(summarize(fact)); }
    }
}
