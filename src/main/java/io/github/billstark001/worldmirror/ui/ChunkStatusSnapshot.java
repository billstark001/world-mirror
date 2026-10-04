package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.function.Consumer;

/**
 * Immutable, region-indexed chunk status data for map renderers.
 */
public final class ChunkStatusSnapshot {
    public static final ChunkStatusSnapshot EMPTY = new ChunkStatusSnapshot(List.of(), Set.of(), 0L);

    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIZE = 1 << REGION_SHIFT;
    private static final long AGE_MAX_MS = 30L * 24 * 3600 * 1000;
    private static final long AGE_MIN_MS = 10L * 60 * 1000;
    private static final int COLOR_EXTERNAL = 0xFFFF9000;
    private static final int COLOR_CONFLICT = 0xFFFF3030;

    private final Map<Long, ChunkDatabase.ChunkRecord> recordsByChunk;
    private final Map<Long, List<ChunkDatabase.ChunkRecord>> recordsByRegion;
    private final Set<Long> conflictKeys;
    private final Map<Long, List<ChunkPos>> conflictsByRegion;
    private final NavigableMap<Integer, List<BoundarySegment>> verticalBoundaries;
    private final NavigableMap<Integer, List<BoundarySegment>> horizontalBoundaries;
    private final long version;

    public ChunkStatusSnapshot(Collection<ChunkDatabase.ChunkRecord> records,
                               Collection<ChunkPos> conflicts,
                               long version) {
        this.recordsByChunk = new HashMap<>(Math.max(16, records.size() * 2));
        this.recordsByRegion = new HashMap<>();
        for (ChunkDatabase.ChunkRecord record : records) {
            long chunkKey = chunkKey(record.x(), record.z());
            recordsByChunk.put(chunkKey, record);
            recordsByRegion
                    .computeIfAbsent(regionKeyForChunk(record.x(), record.z()), ignored -> new ArrayList<>())
                    .add(record);
        }

        this.conflictKeys = new HashSet<>(Math.max(16, conflicts.size() * 2));
        this.conflictsByRegion = new HashMap<>();
        for (ChunkPos pos : conflicts) {
            long chunkKey = chunkKey(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
            conflictKeys.add(chunkKey);
            conflictsByRegion
                    .computeIfAbsent(regionKeyForChunk(
                            pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4), ignored -> new ArrayList<>())
                    .add(pos);
        }

        List<BoundarySegment> boundarySegments = buildBoundarySegments(System.currentTimeMillis());
        this.verticalBoundaries = indexBoundaries(boundarySegments, true);
        this.horizontalBoundaries = indexBoundaries(boundarySegments, false);
        this.version = version;
    }

    public long version() {
        return version;
    }

    public int recordCount() {
        return recordsByChunk.size();
    }

    public ChunkDatabase.ChunkRecord getRecord(int chunkX, int chunkZ) {
        return recordsByChunk.get(chunkKey(chunkX, chunkZ));
    }

    public Collection<ChunkDatabase.ChunkRecord> records() {
        return recordsByChunk.values();
    }

    public boolean hasConflict(int chunkX, int chunkZ) {
        return conflictKeys.contains(chunkKey(chunkX, chunkZ));
    }

    public void forEachRecordInRange(int minChunkX, int maxChunkX,
                                     int minChunkZ, int maxChunkZ,
                                     Consumer<ChunkDatabase.ChunkRecord> consumer) {
        forEachRegionInRange(recordsByRegion, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                records -> {
                    for (ChunkDatabase.ChunkRecord record : records) {
                        int x = record.x();
                        int z = record.z();
                        if (x >= minChunkX && x <= maxChunkX && z >= minChunkZ && z <= maxChunkZ) {
                            consumer.accept(record);
                        }
                    }
                });
    }

    public void forEachConflictInRange(int minChunkX, int maxChunkX,
                                       int minChunkZ, int maxChunkZ,
                                       Consumer<ChunkPos> consumer) {
        forEachRegionInRange(conflictsByRegion, minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                conflicts -> {
                    for (ChunkPos pos : conflicts) {
                        int x = pos.getMinBlockX() >> 4;
                        int z = pos.getMinBlockZ() >> 4;
                        if (x >= minChunkX && x <= maxChunkX && z >= minChunkZ && z <= maxChunkZ) {
                            consumer.accept(pos);
                        }
                    }
                });
    }

    public void forEachBoundaryInRange(int minChunkX, int maxChunkX,
                                       int minChunkZ, int maxChunkZ,
                                       Consumer<BoundarySegment> consumer) {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) return;
        int maxBoundaryX = maxChunkX == Integer.MAX_VALUE ? maxChunkX : maxChunkX + 1;
        int maxBoundaryZ = maxChunkZ == Integer.MAX_VALUE ? maxChunkZ : maxChunkZ + 1;
        for (List<BoundarySegment> segments : verticalBoundaries.subMap(
                minChunkX, true, maxBoundaryX, true).values()) {
            for (BoundarySegment segment : segments) {
                if (segment.intersects(minChunkX, maxChunkX + 1L, minChunkZ, maxChunkZ + 1L)) {
                    consumer.accept(segment);
                }
            }
        }
        for (List<BoundarySegment> segments : horizontalBoundaries.subMap(
                minChunkZ, true, maxBoundaryZ, true).values()) {
            for (BoundarySegment segment : segments) {
                if (segment.intersects(minChunkX, maxChunkX + 1L, minChunkZ, maxChunkZ + 1L)) {
                    consumer.accept(segment);
                }
            }
        }
    }

    private static <T> void forEachRegionInRange(Map<Long, T> regions,
                                                  int minChunkX, int maxChunkX,
                                                  int minChunkZ, int maxChunkZ,
                                                  Consumer<T> consumer) {
        if (regions.isEmpty() || minChunkX > maxChunkX || minChunkZ > maxChunkZ) return;
        int minRegionX = Math.floorDiv(minChunkX, REGION_SIZE);
        int maxRegionX = Math.floorDiv(maxChunkX, REGION_SIZE);
        int minRegionZ = Math.floorDiv(minChunkZ, REGION_SIZE);
        int maxRegionZ = Math.floorDiv(maxChunkZ, REGION_SIZE);
        long area = ((long) maxRegionX - minRegionX + 1L)
                * ((long) maxRegionZ - minRegionZ + 1L);
        if (area > 4L * regions.size()) {
            for (Map.Entry<Long, T> entry : regions.entrySet()) {
                int regionX = (int) (entry.getKey() >> 32);
                int regionZ = (int) (long) entry.getKey();
                if (regionX >= minRegionX && regionX <= maxRegionX
                        && regionZ >= minRegionZ && regionZ <= maxRegionZ) {
                    consumer.accept(entry.getValue());
                }
            }
            return;
        }
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                T value = regions.get(regionKey(regionX, regionZ));
                if (value != null) consumer.accept(value);
            }
        }
    }

    private static NavigableMap<Integer, List<BoundarySegment>> indexBoundaries(
            List<BoundarySegment> segments, boolean vertical) {
        NavigableMap<Integer, List<BoundarySegment>> index = new TreeMap<>();
        for (BoundarySegment segment : segments) {
            if (segment.vertical() == vertical) {
                index.computeIfAbsent(segment.fixed(), ignored -> new ArrayList<>()).add(segment);
            }
        }
        return index;
    }

    /** Version adapter for consumers that need coordinates without depending on ChunkPos accessors. */
    public void forEachConflictCoordinatesInRange(int minChunkX, int maxChunkX,
                                                  int minChunkZ, int maxChunkZ,
                                                  java.util.function.BiConsumer<Integer, Integer> consumer) {
        forEachConflictInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                pos -> consumer.accept(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4));
    }

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private List<BoundarySegment> buildBoundarySegments(long now) {
        HashMap<Long, VisualStatus> statuses = new HashMap<>(Math.max(16, recordsByChunk.size() + conflictKeys.size()));
        for (ChunkDatabase.ChunkRecord record : recordsByChunk.values()) {
            statuses.put(chunkKey(record.x(), record.z()), visualStatus(record, false, now));
        }
        for (long key : conflictKeys) {
            ChunkDatabase.ChunkRecord record = recordsByChunk.get(key);
            int x = unpackX(key);
            int z = unpackZ(key);
            statuses.put(key, visualStatus(record != null ? record : new ChunkDatabase.ChunkRecord(x, z, now, "world_mirror"), true, now));
        }

        HashMap<Long, BoundaryUnit> vertical = new HashMap<>();
        HashMap<Long, BoundaryUnit> horizontal = new HashMap<>();
        for (long key : statuses.keySet()) {
            int x = unpackX(key);
            int z = unpackZ(key);
            addVerticalBoundary(vertical, statuses, x, z);
            addVerticalBoundary(vertical, statuses, x + 1, z);
            addHorizontalBoundary(horizontal, statuses, z, x);
            addHorizontalBoundary(horizontal, statuses, z + 1, x);
        }

        ArrayList<BoundarySegment> segments = new ArrayList<>(vertical.size() + horizontal.size());
        mergeBoundaryUnits(vertical.values(), true, segments);
        mergeBoundaryUnits(horizontal.values(), false, segments);
        return List.copyOf(segments);
    }

    private static void addVerticalBoundary(Map<Long, BoundaryUnit> target,
                                            Map<Long, VisualStatus> statuses,
                                            int fixedX,
                                            int z) {
        VisualStatus left = statuses.get(chunkKey(fixedX - 1, z));
        VisualStatus right = statuses.get(chunkKey(fixedX, z));
        BoundaryUnit unit = boundaryUnit(true, fixedX, z, left, right);
        if (unit != null) {
            target.put(boundaryUnitKey(fixedX, z), unit);
        }
    }

    private static void addHorizontalBoundary(Map<Long, BoundaryUnit> target,
                                              Map<Long, VisualStatus> statuses,
                                              int fixedZ,
                                              int x) {
        VisualStatus top = statuses.get(chunkKey(x, fixedZ - 1));
        VisualStatus bottom = statuses.get(chunkKey(x, fixedZ));
        BoundaryUnit unit = boundaryUnit(false, fixedZ, x, top, bottom);
        if (unit != null) {
            target.put(boundaryUnitKey(fixedZ, x), unit);
        }
    }

    private static BoundaryUnit boundaryUnit(boolean vertical,
                                             int fixed,
                                             int start,
                                             VisualStatus a,
                                             VisualStatus b) {
        if (a == null && b == null) return null;
        if (a != null && b != null && a.visualKey == b.visualKey) return null;
        VisualStatus chosen = chooseBoundaryStatus(a, b);
        return chosen != null ? new BoundaryUnit(vertical, fixed, start, chosen.color) : null;
    }

    private static VisualStatus chooseBoundaryStatus(VisualStatus a, VisualStatus b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.priority >= b.priority ? a : b;
    }

    private static void mergeBoundaryUnits(Collection<BoundaryUnit> units,
                                           boolean vertical,
                                           List<BoundarySegment> out) {
        ArrayList<BoundaryUnit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator
                .comparingInt((BoundaryUnit unit) -> unit.fixed)
                .thenComparingInt(unit -> unit.color)
                .thenComparingInt(unit -> unit.start));

        BoundaryBuilder builder = null;
        for (BoundaryUnit unit : sorted) {
            if (builder != null && builder.canAppend(unit)) {
                builder.append(unit);
            } else {
                if (builder != null) out.add(builder.build());
                builder = new BoundaryBuilder(vertical, unit);
            }
        }
        if (builder != null) out.add(builder.build());
    }

    private static VisualStatus visualStatus(ChunkDatabase.ChunkRecord record, boolean conflict, long now) {
        if (conflict) {
            return new VisualStatus(0, COLOR_CONFLICT, 1_000_000);
        }
        if (record == null) return null;
        if (!ChunkMapView.usesCaptureTimeGradient(record.updateSource())) {
            return new VisualStatus(1, COLOR_EXTERNAL, 900_000);
        }
        long ageMs = Math.max(0L, now - record.updateTime());
        int bucket = freshnessBucket(ageMs);
        int color = freshnessColor(bucket);
        int green = (color >> 8) & 0xFF;
        return new VisualStatus(2 + bucket, color, green);
    }

    private static int freshnessBucket(long ageMs) {
        if (ageMs <= AGE_MIN_MS) return 0;
        if (ageMs >= AGE_MAX_MS) return 7;
        double t = Math.log((double) ageMs / AGE_MIN_MS)
                / Math.log((double) AGE_MAX_MS / AGE_MIN_MS);
        return Math.max(0, Math.min(7, (int) Math.floor(t * 8.0D)));
    }

    private static int freshnessColor(int bucket) {
        double t = bucket / 7.0D;
        int g = (int) Math.round(200 * (1.0D - t));
        int b = (int) Math.round(200 * t);
        return 0xFF000000 | (g << 8) | b;
    }

    private static long boundaryUnitKey(int fixed, int start) {
        return ((long) fixed << 32) | (start & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static long regionKeyForChunk(int chunkX, int chunkZ) {
        return regionKey(Math.floorDiv(chunkX, REGION_SIZE), Math.floorDiv(chunkZ, REGION_SIZE));
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public record BoundarySegment(boolean vertical, int fixed, int start, int end, int color) {
        private boolean intersects(long minX, long maxX, long minZ, long maxZ) {
            if (vertical) {
                return fixed >= minX && fixed <= maxX && end > minZ && start < maxZ;
            }
            return fixed >= minZ && fixed <= maxZ && end > minX && start < maxX;
        }
    }

    private record VisualStatus(int visualKey, int color, int priority) {}

    private record BoundaryUnit(boolean vertical, int fixed, int start, int color) {}

    private static final class BoundaryBuilder {
        private final boolean vertical;
        private final int fixed;
        private final int color;
        private int start;
        private int end;

        private BoundaryBuilder(boolean vertical, BoundaryUnit unit) {
            this.vertical = vertical;
            this.fixed = unit.fixed;
            this.color = unit.color;
            this.start = unit.start;
            this.end = unit.start + 1;
        }

        private boolean canAppend(BoundaryUnit unit) {
            return unit.fixed == fixed
                    && unit.color == color
                    && unit.start == end;
        }

        private void append(BoundaryUnit unit) {
            this.end = unit.start + 1;
        }

        private BoundarySegment build() {
            return new BoundarySegment(vertical, fixed, start, end, color);
        }
    }
}
