package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Coalesces a large chunk viewport into coloured bucket runs.  This is used at
 * low zoom where issuing a GUI draw call for every chunk is prohibitively
 * expensive.  The cache is deliberately short-lived: it follows the immutable
 * snapshot version while still allowing colour ageing to progress.
 */
public final class ChunkMapAggregation {
    private static final int MAX_BUCKET_CELLS = 4_096;
    private static final long CACHE_MS = 1_000L;
    private static CacheEntry cache;

    private ChunkMapAggregation() {}

    public static synchronized Result aggregate(ChunkStatusSnapshot snapshot,
                                                 int minChunkX, int maxChunkX,
                                                 int minChunkZ, int maxChunkZ,
                                                 long now) {
        return aggregate(snapshot, minChunkX, maxChunkX, minChunkZ, maxChunkZ, now, MAX_BUCKET_CELLS);
    }

    public static synchronized Result aggregate(ChunkStatusSnapshot snapshot,
                                                 int minChunkX, int maxChunkX,
                                                 int minChunkZ, int maxChunkZ,
                                                 long now, int maxBucketCells) {
        int bucketSize = bucketSizeFor(minChunkX, maxChunkX, minChunkZ, maxChunkZ, maxBucketCells);
        int minBucketX = Math.floorDiv(minChunkX, bucketSize);
        int maxBucketX = Math.floorDiv(maxChunkX, bucketSize);
        int minBucketZ = Math.floorDiv(minChunkZ, bucketSize);
        int maxBucketZ = Math.floorDiv(maxChunkZ, bucketSize);
        if (cache != null && cache.snapshot == snapshot
                && cache.minBucketX == minBucketX && cache.maxBucketX == maxBucketX
                && cache.minBucketZ == minBucketZ && cache.maxBucketZ == maxBucketZ
                && cache.bucketSize == bucketSize && now - cache.createdAtMs < CACHE_MS) {
            return cache.result;
        }

        HashMap<Long, Cell> cells = new HashMap<>();
        snapshot.forEachRecordInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ, record -> {
            int bucketX = Math.floorDiv(record.x(), bucketSize);
            int bucketZ = Math.floorDiv(record.z(), bucketSize);
            Cell cell = cells.computeIfAbsent(ChunkStatusSnapshot.chunkKey(bucketX, bucketZ), ignored -> new Cell(bucketX, bucketZ));
            if (record.updateTime() >= cell.updateTime) {
                cell.updateTime = record.updateTime();
                cell.color = ChunkMapScreen.computeColor(record, now);
            }
        });
        snapshot.forEachConflictCoordinatesInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ, (x, z) -> {
            int bucketX = Math.floorDiv(x, bucketSize);
            int bucketZ = Math.floorDiv(z, bucketSize);
            cells.computeIfAbsent(ChunkStatusSnapshot.chunkKey(bucketX, bucketZ), ignored -> new Cell(bucketX, bucketZ)).conflict = true;
        });

        ArrayList<Cell> sorted = new ArrayList<>(cells.values());
        sorted.sort(Comparator.comparingInt((Cell cell) -> cell.z).thenComparingInt(cell -> cell.x));
        ArrayList<Run> runs = new ArrayList<>(sorted.size());
        for (Cell cell : sorted) {
            int color = cell.color != 0 ? cell.color : 0xFFFF3030;
            if (!runs.isEmpty() && runs.getLast().canAppend(cell, color)) {
                runs.getLast().endBucketX = cell.x;
            } else {
                runs.add(new Run(cell.x, cell.x, cell.z, color, cell.conflict));
            }
        }
        Result result = new Result(bucketSize, List.copyOf(runs), buildBoundaries(cells));
        cache = new CacheEntry(snapshot, minBucketX, maxBucketX, minBucketZ, maxBucketZ, bucketSize, now, result);
        return result;
    }

    private static int bucketSizeFor(int minX, int maxX, int minZ, int maxZ, int maxBucketCells) {
        long viewportCells = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        int limit = Math.max(1, maxBucketCells);
        return viewportCells <= limit ? 1
                : Math.max(1, (int) Math.ceil(Math.sqrt((double) viewportCells / limit)));
    }

    private static List<Boundary> buildBoundaries(HashMap<Long, Cell> cells) {
        HashMap<Long, Boundary> vertical = new HashMap<>();
        HashMap<Long, Boundary> horizontal = new HashMap<>();
        for (Cell cell : cells.values()) {
            addVerticalBoundary(vertical, cells, cell.x, cell.z);
            addVerticalBoundary(vertical, cells, cell.x + 1, cell.z);
            addHorizontalBoundary(horizontal, cells, cell.z, cell.x);
            addHorizontalBoundary(horizontal, cells, cell.z + 1, cell.x);
        }
        ArrayList<Boundary> result = new ArrayList<>(vertical.size() + horizontal.size());
        mergeBoundaries(vertical.values(), true, result);
        mergeBoundaries(horizontal.values(), false, result);
        return List.copyOf(result);
    }

    private static void addVerticalBoundary(HashMap<Long, Boundary> target, HashMap<Long, Cell> cells, int fixedX, int z) {
        Cell left = cells.get(ChunkStatusSnapshot.chunkKey(fixedX - 1, z));
        Cell right = cells.get(ChunkStatusSnapshot.chunkKey(fixedX, z));
        addBoundary(target, true, fixedX, z, left, right);
    }

    private static void addHorizontalBoundary(HashMap<Long, Boundary> target, HashMap<Long, Cell> cells, int fixedZ, int x) {
        Cell top = cells.get(ChunkStatusSnapshot.chunkKey(x, fixedZ - 1));
        Cell bottom = cells.get(ChunkStatusSnapshot.chunkKey(x, fixedZ));
        addBoundary(target, false, fixedZ, x, top, bottom);
    }

    private static void addBoundary(HashMap<Long, Boundary> target, boolean vertical, int fixed, int start, Cell a, Cell b) {
        if (a == null && b == null) return;
        if (a != null && b != null && a.visualKey() == b.visualKey()) return;
        Cell chosen = chooseBoundaryCell(a, b);
        target.put(ChunkStatusSnapshot.chunkKey(fixed, start), new Boundary(vertical, fixed, start, start + 1, chosen.displayColor()));
    }

    private static Cell chooseBoundaryCell(Cell a, Cell b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.priority() >= b.priority() ? a : b;
    }

    private static void mergeBoundaries(Iterable<Boundary> boundaries, boolean vertical, List<Boundary> output) {
        ArrayList<Boundary> sorted = new ArrayList<>();
        boundaries.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(Boundary::fixed).thenComparingInt(Boundary::color).thenComparingInt(Boundary::start));
        Boundary previous = null;
        for (Boundary boundary : sorted) {
            if (previous != null && previous.vertical == vertical && previous.fixed == boundary.fixed
                    && previous.color == boundary.color && previous.end == boundary.start) {
                previous = new Boundary(vertical, previous.fixed, previous.start, boundary.end, previous.color);
                output.set(output.size() - 1, previous);
            } else {
                previous = boundary;
                output.add(boundary);
            }
        }
    }

    public record Result(int bucketSize, List<Run> runs, List<Boundary> boundaries) {}
    public record Boundary(boolean vertical, int fixed, int start, int end, int color) {}

    public static final class Run {
        private final int startBucketX;
        private int endBucketX;
        private final int bucketZ;
        private final int color;
        private final boolean conflict;

        private Run(int startBucketX, int endBucketX, int bucketZ, int color, boolean conflict) {
            this.startBucketX = startBucketX;
            this.endBucketX = endBucketX;
            this.bucketZ = bucketZ;
            this.color = color;
            this.conflict = conflict;
        }

        public int startBucketX() { return startBucketX; }
        public int endBucketX() { return endBucketX; }
        public int bucketZ() { return bucketZ; }
        public int color() { return color; }
        public boolean conflict() { return conflict; }

        private boolean canAppend(Cell cell, int color) {
            return cell.z == bucketZ && cell.x == endBucketX + 1 && color == this.color && cell.conflict == conflict;
        }
    }

    private static final class Cell {
        private final int x;
        private final int z;
        private long updateTime = Long.MIN_VALUE;
        private int color;
        private boolean conflict;

        private Cell(int x, int z) { this.x = x; this.z = z; }

        private int displayColor() { return color != 0 ? color : 0xFFFF3030; }
        private int visualKey() {
            if (conflict) return 1_000_000;
            int color = displayColor() & 0x00FFFFFF;
            if (color == 0x00FF9000) return 900_000;
            // Match the snapshot's eight freshness bands instead of treating tiny
            // age-gradient differences as a boundary at low zoom.
            return 2 + Math.min(7, ((color & 0xFF) * 8) / 201);
        }
        private int priority() { return conflict ? 1_000_000 : ((displayColor() >> 8) & 0xFF); }
    }

    private record CacheEntry(ChunkStatusSnapshot snapshot, int minBucketX, int maxBucketX,
                              int minBucketZ, int maxBucketZ, int bucketSize,
                              long createdAtMs, Result result) {}
}
