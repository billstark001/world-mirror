package io.github.billstark001.worldmirror.download;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

import static io.github.billstark001.worldmirror.download.SeedTerrainFiles.chunkX;
import static io.github.billstark001.worldmirror.download.SeedTerrainFiles.chunkZ;

/** Invalidates saved light at generation boundaries so vanilla can light the terrain on load. */
public final class SeedTerrainLighting {
    private static final String LIGHT_CORRECT = "isLightOn";
    private static final String SKY_LIGHT = "SkyLight";
    private static final String BLOCK_LIGHT = "BlockLight";
    private static final Set<String> BEFORE_FEATURES = Set.of("empty", "structure_starts",
            "structure_references", "biomes", "noise", "surface", "carvers", "terrain");

    private SeedTerrainLighting() {}

    public static boolean hasSavedLight(CompoundTag chunk) {
        if (chunk.getBooleanOr(LIGHT_CORRECT, false))
            return true;
        for (Tag tag : chunk.getListOrEmpty("sections"))
            if (tag instanceof CompoundTag section
                    && (section.contains(SKY_LIGHT) || section.contains(BLOCK_LIGHT)))
                return true;
        return false;
    }

    static boolean awaitsFeatures(CompoundTag chunk) {
        String status = chunk.getStringOr("Status", "");
        if (status.startsWith("minecraft:"))
            status = status.substring("minecraft:".length());
        return BEFORE_FEATURES.contains(status);
    }

    static boolean bordersGeneration(
            ChunkPos pos, Set<ChunkPos> present, Set<ChunkPos> generates, boolean includeMissing) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                if ((dx != 0 || dz != 0)
                        && generatesAt(new ChunkPos(chunkX(pos) + dx, chunkZ(pos) + dz), present, generates,
                                includeMissing))
                    return true;
        return false;
    }

    private static boolean generatesAt(
            ChunkPos pos, Set<ChunkPos> present, Set<ChunkPos> generates, boolean includeMissing) {
        return generates.contains(pos) || includeMissing && !present.contains(pos);
    }

    static void invalidate(CompoundTag chunk) {
        chunk.remove(LIGHT_CORRECT);
        // Vanilla loads saved light layers even with the correct-light flag cleared.
        var sections = chunk.getListOrEmpty("sections");
        for (int i = sections.size() - 1; i >= 0; i--) {
            if (!(sections.get(i) instanceof CompoundTag section)
                    || (!section.contains(SKY_LIGHT) && !section.contains(BLOCK_LIGHT)))
                continue;
            section.remove(SKY_LIGHT);
            section.remove(BLOCK_LIGHT);
            if (section.size() == 1 && section.contains("Y"))
                sections.remove(i);
        }
    }
}
