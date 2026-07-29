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
        int bucketSize = bucketSizeFor(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
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
        Result result = new Result(bucketSize, List.copyOf(runs));
        cache = new CacheEntry(snapshot, minBucketX, maxBucketX, minBucketZ, maxBucketZ, bucketSize, now, result);
        return result;
    }

    private static int bucketSizeFor(int minX, int maxX, int minZ, int maxZ) {
        long viewportCells = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        return viewportCells <= MAX_BUCKET_CELLS ? 1
                : Math.max(1, (int) Math.ceil(Math.sqrt((double) viewportCells / MAX_BUCKET_CELLS)));
    }

    public record Result(int bucketSize, List<Run> runs) {}

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
    }

    private record CacheEntry(ChunkStatusSnapshot snapshot, int minBucketX, int maxBucketX,
                              int minBucketZ, int maxBucketZ, int bucketSize,
                              long createdAtMs, Result result) {}
}
