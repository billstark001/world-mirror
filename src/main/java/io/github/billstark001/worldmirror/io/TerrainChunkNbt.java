package io.github.billstark001.worldmirror.io;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;

/** Slot validation and content equality for native terrain records. */
public final class TerrainChunkNbt {
    private TerrainChunkNbt() {}

    public static void requireCoordinates(CompoundTag chunk, ChunkPos pos) throws IOException {
        if ((chunk.contains("xPos") || chunk.contains("zPos")) && !coordinatesMatch(chunk, pos))
            throw new IOException("Saved terrain coordinates disagree with its Anvil slot: " + pos);
    }

    public static boolean coordinatesMatch(CompoundTag chunk, ChunkPos pos) {
        return chunk.get("xPos") instanceof IntTag x && chunk.get("zPos") instanceof IntTag z
                && x.value() == pos.getRegionX() * ChunkPos.REGION_SIZE + pos.getRegionLocalX()
                && z.value() == pos.getRegionZ() * ChunkPos.REGION_SIZE + pos.getRegionLocalZ();
    }

    public static boolean hasSupportedDataVersion(CompoundTag chunk) {
        return chunk.get("DataVersion") instanceof IntTag version && version.value() > 0
                && version.value() <= SharedConstants.getCurrentVersion().dataVersion().version();
    }

    public static boolean equivalent(CompoundTag saved, CompoundTag incoming) {
        if (saved.equals(incoming))
            return true;
        // Full chunks with empty tick lists can retain vanilla's last-save game time.
        if (saved.size() != incoming.size() || !isTicklessFull(saved) || !isTicklessFull(incoming))
            return false;
        for (var entry : saved.entrySet())
            if (!entry.getKey().equals("LastUpdate")
                    && !entry.getValue().equals(incoming.get(entry.getKey())))
                return false;
        return true;
    }

    private static boolean isTicklessFull(CompoundTag chunk) {
        return chunk.getStringOr("Status", "").equals("minecraft:full")
                && chunk.get("LastUpdate") instanceof LongTag
                && chunk.get("block_ticks") instanceof ListTag blockTicks && blockTicks.isEmpty()
                && chunk.get("fluid_ticks") instanceof ListTag fluidTicks && fluidTicks.isEmpty();
    }
}
