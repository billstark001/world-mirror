package io.github.billstark001.worldmirror.ui;

/** Shared zero-anchored bucket layout for every chunk-map projection. */
public record ChunkBucketLayout(ChunkBounds bounds, int size) {
    public ChunkBucketLayout {
        if (size <= 0) throw new IllegalArgumentException("Bucket size must be positive");
    }
    public static ChunkBucketLayout withinBudget(ChunkBounds bounds, int budget) {
        return new ChunkBucketLayout(bounds, sizeFor(bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ(), budget));
    }
    public ChunkBounds bucket(int x, int z) {
        return new ChunkBounds(ChunkBounds.clamp((long) x * size), ChunkBounds.clamp(((long) x + 1) * size - 1),
                ChunkBounds.clamp((long) z * size), ChunkBounds.clamp(((long) z + 1) * size - 1));
    }
    public ChunkBounds clipped(int x, int z) {
        ChunkBounds bucket = bucket(x, z);
        return new ChunkBounds(Math.max(bounds.minX(), bucket.minX()), Math.min(bounds.maxX(), bucket.maxX()),
                Math.max(bounds.minZ(), bucket.minZ()), Math.min(bounds.maxZ(), bucket.maxZ()));
    }

    /**
     * Chooses square, zero-anchored buckets within the requested cell budget.
     * A range crossing zero needs at least two buckets on that axis. Empty
     * ranges use size one. If int-sized buckets cannot meet the budget at the
     * extreme integer coordinates, the size saturates at Integer.MAX_VALUE.
     * Sizing uses bounded searches so very thin viewports cannot stall rendering.
     */
    public static int sizeFor(int minX, int maxX, int minZ, int maxZ, int maxBucketCells) {
        if (minX > maxX || minZ > maxZ) return 1;
        double viewportCells = ((double) maxX - minX + 1.0D)
                * ((double) maxZ - minZ + 1.0D);
        // Buckets are anchored at zero. A range crossing zero always occupies
        // at least two buckets on that axis, regardless of bucket size.
        int minimum = (minX < 0 && maxX >= 0 ? 2 : 1)
                * (minZ < 0 && maxZ >= 0 ? 2 : 1);
        int limit = Math.max(minimum, maxBucketCells);
        int size = viewportCells <= limit ? 1
                : Math.max(1, (int) Math.ceil(Math.sqrt(viewportCells / limit)));
        if (withinBudget(minX, maxX, minZ, maxZ, size, limit)) return size;

        int lower = size;
        int upper = size;
        while (upper < Integer.MAX_VALUE
                && !withinBudget(minX, maxX, minZ, maxZ, upper, limit)) {
            lower = upper;
            upper = (int) Math.min(Integer.MAX_VALUE, (long) upper * 2L);
        }
        if (!withinBudget(minX, maxX, minZ, maxZ, upper, limit)) return upper;
        // Bucket counts are not strictly monotonic because their origin is
        // fixed. Keep a known-valid upper bound rather than assuming the
        // smallest possible bucket size can be found by binary search.
        while ((long) upper - lower > 1L) {
            int middle = lower + (upper - lower) / 2;
            if (withinBudget(minX, maxX, minZ, maxZ, middle, limit)) upper = middle;
            else lower = middle;
        }
        return upper;
    }

    private static boolean withinBudget(int minX, int maxX, int minZ, int maxZ, int size, int limit) {
        long columns = (long) Math.floorDiv(maxX, size) - Math.floorDiv(minX, size) + 1L;
        long rows = (long) Math.floorDiv(maxZ, size) - Math.floorDiv(minZ, size) + 1L;
        return columns <= limit / rows;
    }

}
