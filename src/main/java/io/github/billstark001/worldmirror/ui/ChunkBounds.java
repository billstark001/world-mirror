package io.github.billstark001.worldmirror.ui;

/** Inclusive chunk coordinates. Boundary calculations widen to long before adding one. */
public record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {
    public boolean isEmpty() { return minX > maxX || minZ > maxZ; }
    public boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    public boolean contains(ChunkBounds other) {
        return other.minX >= minX && other.maxX <= maxX && other.minZ >= minZ && other.maxZ <= maxZ;
    }
    public ChunkBounds expand(int margin) {
        return new ChunkBounds(clamp((long) minX - margin), clamp((long) maxX + margin),
                clamp((long) minZ - margin), clamp((long) maxZ + margin));
    }
    static int clamp(long coordinate) { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, coordinate)); }
    public static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    public static int x(long key) { return (int) (key >> 32); }
    public static int z(long key) { return (int) key; }
}
