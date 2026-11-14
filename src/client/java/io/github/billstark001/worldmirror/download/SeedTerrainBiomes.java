package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recognizes void terrain and translates owned biome palettes for use without the data pack. */
public final class SeedTerrainBiomes {
    private static final List<String> CHUNK_CONTENT =
            List.of("block_entities", "entities", "Entities", "block_ticks", "fluid_ticks");

    private SeedTerrainBiomes() {}

    private static boolean nonemptyList(CompoundTag tag, String key) {
        return tag.contains(key) && (!(tag.get(key) instanceof ListTag list) || !list.isEmpty());
    }

    static boolean isOwnedVoid(CompoundTag chunk, ResourceKey<Level> dimension) {
        for (String key : CHUNK_CONTENT)
            if (nonemptyList(chunk, key))
                return false;
        var structures = chunk.getCompoundOrEmpty("structures");
        if (!structures.getCompoundOrEmpty("starts").isEmpty()
                || !structures.getCompoundOrEmpty("References").isEmpty()
                || chunk.contains("below_zero_retrogen") || chunk.contains("UpgradeData")
                || chunk.contains(SeedTerrainDigest.BLENDING_DATA)
                || chunk.contains(SeedTerrainDigest.ATTACHMENTS))
            return false;
        boolean hasTerrain = false;
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            if (!(tag instanceof CompoundTag section))
                return false;
            if (!section.contains("biomes") && !section.contains("block_states"))
                continue;
            var blocks = section.getCompoundOrEmpty("block_states");
            var biomes = section.getCompoundOrEmpty("biomes");
            var blockPalette = blocks.getListOrEmpty("palette");
            var biomePalette = biomes.getListOrEmpty("palette");
            if (blockPalette.size() != 1 || biomePalette.size() != 1 || !isVanillaAir(blockPalette.getFirst())
                    || !biomePalette.getStringOr(0, "").equals(mirrorBiome(dimension))
                    || blocks.contains("data") || biomes.contains("data"))
                return false;
            hasTerrain = true;
        }
        return hasTerrain;
    }

    private static boolean isVanillaAir(Tag state) {
        if (state instanceof StringTag name)
            return name.asString().orElse("").equals("minecraft:air");
        return state instanceof CompoundTag compound && compound.size() == 1
                && compound.getStringOr("Name", "").equals("minecraft:air");
    }

    private static String mirrorBiome(ResourceKey<Level> dimension) {
        return dimension.equals(Level.NETHER) ? "worldmirror:mirror_nether"
                : dimension.equals(Level.END) ? "worldmirror:mirror_end"
                                              : "worldmirror:mirror_overworld";
    }

    private static String normalBiome(ResourceKey<Level> dimension) {
        return dimension.equals(Level.NETHER) ? "minecraft:nether_wastes"
                : dimension.equals(Level.END) ? "minecraft:the_end"
                                              : "minecraft:plains";
    }

    static boolean hasMirrorBiomes(CompoundTag chunk, ResourceKey<Level> dimension) throws IOException {
        boolean result = false;
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            if (!(tag instanceof CompoundTag section))
                throw new IOException("Invalid saved terrain section");
            for (Tag biome : section.getCompoundOrEmpty("biomes").getListOrEmpty("palette")) {
                String key = biome.asString().orElseThrow(() -> new IOException("Invalid saved biome name"));
                if (key.startsWith("worldmirror:")) {
                    if (!key.equals(mirrorBiome(dimension)))
                        throw new IOException("Unexpected owned biome " + key);
                    result = true;
                }
            }
        }
        return result;
    }

    /** PalettedContainer uses non-spanning packed cells. Collapse the palette after replacing our biome. */
    static void replaceBiomes(CompoundTag chunk, ResourceKey<Level> dimension) throws IOException {
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            var section = (CompoundTag) tag;
            var container = section.getCompoundOrEmpty("biomes");
            var previous = container.getListOrEmpty("palette");
            if (!previous.contains(StringTag.valueOf(mirrorBiome(dimension))))
                continue;
            ListTag palette = new ListTag();
            Map<String, Integer> indices = new HashMap<>();
            int[] remap = new int[previous.size()];
            for (int i = 0; i < previous.size(); i++) {
                String key = previous.getStringOr(i, "");
                if (key.equals(mirrorBiome(dimension)))
                    key = normalBiome(dimension);
                Integer existing = indices.get(key);
                if (existing == null) {
                    existing = palette.size();
                    indices.put(key, existing);
                    palette.add(StringTag.valueOf(key));
                }
                remap[i] = existing;
            }
            int oldBits = TerrainBitStorage.bits(previous.size());
            long[] oldData = container.getLongArray("data").orElse(new long[0]);
            if (oldBits != 0 && oldData.length != TerrainBitStorage.wordCount(64, oldBits))
                throw new IOException("Invalid biome packed array");
            int newBits = TerrainBitStorage.bits(palette.size());
            long[] data = newBits == 0 ? null : new long[TerrainBitStorage.wordCount(64, newBits)];
            for (int i = 0; i < 64; i++) {
                int index = oldBits == 0 ? 0 : TerrainBitStorage.cell(oldData, oldBits, i);
                if (index >= remap.length)
                    throw new IOException("Invalid biome palette index");
                if (data != null)
                    data[i / (64 / newBits)] |= (long) remap[index] << ((i % (64 / newBits)) * newBits);
            }
            container.put("palette", palette);
            if (data == null)
                container.remove("data");
            else
                container.putLongArray("data", data);
        }
    }

    /** Terrain captured from a void mirror can still include its datapack's biome identifiers. */
    public static void repairCapturedBiomes(CompoundTag chunk, ResourceKey<Level> dimension)
            throws IOException {
        if ((dimension.equals(Level.OVERWORLD) || dimension.equals(Level.NETHER)
                    || dimension.equals(Level.END))
                && hasMirrorBiomes(chunk, dimension))
            replaceBiomes(chunk, dimension);
    }
}
