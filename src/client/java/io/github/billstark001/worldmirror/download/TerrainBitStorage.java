package io.github.billstark001.worldmirror.download;

import net.minecraft.nbt.CompoundTag;

// Non-spanning packed cells in Minecraft palettes and heightmaps.
public final class TerrainBitStorage {
    private TerrainBitStorage() {}

    public static int bits(int size) {
        return 32 - Integer.numberOfLeadingZeros(size - 1);
    }
    public static int wordCount(int cells, int bits) {
        return (cells + 64 / bits - 1) / (64 / bits);
    }
    public static int cell(long[] data, int bits, int index) {
        return (int) ((data[index / (64 / bits)] >>> ((index % (64 / bits)) * bits)) & ((1L << bits) - 1));
    }

    static Integer surfaceHeight(CompoundTag chunk, int dx, int dz) {
        var values = chunk.getCompoundOrEmpty("Heightmaps").getLongArray("WORLD_SURFACE").orElse(null);
        if (values == null || values.length != wordCount(256, 9))
            return null;
        int height = cell(values, 9, dx + 16 * dz);
        // Both the persisted heightmap and the native seed sampler report the first free Y.
        return height <= 0 || height > 384 ? null : height - 64;
    }
}
