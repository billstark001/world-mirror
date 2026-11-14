package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.blending.BlendingData;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recognizes void terrain and translates owned biome palettes for use without the data pack. */
public final class SeedTerrainBiomes {
    private static final List<String> CHUNK_CONTENT =
            List.of("block_entities", "entities", "Entities", "block_ticks", "fluid_ticks");
    private static final Set<String> BLENDING_FIELDS = Set.of("min_section", "max_section", "heights");
    private static final Set<String> CHUNK_FIELDS = Set.of("DataVersion", "InhabitedTime", "LastUpdate",
            "PostProcessing", "Status", "xPos", "yPos", "zPos", "block_entities", "block_ticks", "entities",
            "Entities", "fluid_ticks", "structures", "sections", "isLightOn", "Heightmaps", "carving_mask",
            SeedTerrainDigest.BLENDING_DATA);
    private static final Set<String> SECTION_FIELDS =
            Set.of("Y", "SkyLight", "BlockLight", "biomes", "block_states");
    private static final Set<String> BIOME_FIELDS = Set.of("palette", "data");
    private static final Set<String> NATIVE_TERRAIN_STATUS = Set.of("noise", "surface", "terrain", "carvers",
            "features", "initialize_light", "light", "spawn", "full");
    private static final Set<String> HEIGHTMAP_FIELDS = Set.of("WORLD_SURFACE_WG", "OCEAN_FLOOR_WG",
            "WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES");
    private static final int BIOME_CELLS = 64;
    private static final int OVERWORLD_MIN_SECTION = -4;
    private static final int OVERWORLD_SECTION_COUNT = 24;
    // All three vanilla dimension heights use nine bits per heightmap entry.
    private static final int HEIGHTMAP_WORDS = TerrainBitStorage.wordCount(256, 9);

    private enum BiomeUse { INVALID, VANILLA, MIRROR }

    private SeedTerrainBiomes() {}

    private static boolean nonemptyList(CompoundTag tag, String key) {
        return tag.contains(key) && (!(tag.get(key) instanceof ListTag list) || !list.isEmpty());
    }

    public static boolean isOwnedVoid(CompoundTag chunk, ResourceKey<Level> dimension) {
        if (!hasEmptyContent(chunk, dimension) || !CHUNK_FIELDS.containsAll(chunk.keySet())
                || !hasEmptyPostProcessing(chunk) || !hasZeroHeightmaps(chunk))
            return false;
        String mirrorBiome = mirrorBiome(dimension);
        boolean allowVanilla = dimension.equals(Level.OVERWORLD);
        boolean hasMirrorBiome = false;
        var seen = new HashSet<Byte>();
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            if (!(tag instanceof CompoundTag section) || !SECTION_FIELDS.containsAll(section.keySet()))
                return false;
            var y = section.getByte("Y").orElse(null);
            if (y == null || !seen.add(y))
                return false;
            if (!section.contains("biomes") && !section.contains("block_states"))
                continue;
            if (!hasOnlyAir(section))
                return false;
            var biomes = section.getCompoundOrEmpty("biomes");
            if (!BIOME_FIELDS.containsAll(biomes.keySet()))
                return false;
            var use = biomeUse(biomes, mirrorBiome, allowVanilla);
            if (use == BiomeUse.INVALID)
                return false;
            hasMirrorBiome |= use == BiomeUse.MIRROR;
        }
        return hasMirrorBiome;
    }

    /** Also needs a verified void generator, no index or auxiliary data, and a matching capture boundary. */
    public static boolean isNativeVoid(CompoundTag chunk, ResourceKey<Level> dimension) {
        if (!dimension.equals(Level.OVERWORLD) || !hasEmptyContent(chunk, dimension)
                || !CHUNK_FIELDS.containsAll(chunk.keySet()) || chunk.getIntOr("DataVersion", 0) <= 0
                || chunk.getLongOr("InhabitedTime", -1L) != 0
                || chunk.getIntOr("yPos", Integer.MIN_VALUE) != OVERWORLD_MIN_SECTION
                || !hasEmptyGenerationData(chunk))
            return false;
        return hasCompleteVanillaAirSections(chunk);
    }

    /** Connects complete native-only air candidates to a known void or captured boundary. */
    public static Set<ChunkPos> corroboratedNativeVoids(
            Set<ChunkPos> candidates, Set<ChunkPos> owned, Set<ChunkPos> captured) {
        Set<ChunkPos> verified = new HashSet<>();
        var pending = new ArrayDeque<ChunkPos>();
        for (ChunkPos pos : candidates) {
            if (cardinal(pos).stream().anyMatch(next -> owned.contains(next) || captured.contains(next))) {
                verified.add(pos);
                pending.add(pos);
            }
        }
        while (!pending.isEmpty()) {
            for (ChunkPos next : cardinal(pending.removeFirst()))
                if (candidates.contains(next) && verified.add(next))
                    pending.add(next);
        }
        return verified;
    }

    private static List<ChunkPos> cardinal(ChunkPos pos) {
        int x = pos.getRegionX() * ChunkPos.REGION_SIZE + pos.getRegionLocalX();
        int z = pos.getRegionZ() * ChunkPos.REGION_SIZE + pos.getRegionLocalZ();
        return List.of(new ChunkPos(x - 1, z), new ChunkPos(x + 1, z), new ChunkPos(x, z - 1),
                new ChunkPos(x, z + 1));
    }

    private static boolean hasEmptyContent(CompoundTag chunk, ResourceKey<Level> dimension) {
        if (chunk == null)
            return false;
        for (String key : CHUNK_CONTENT)
            if (nonemptyList(chunk, key))
                return false;
        return !hasStructures(chunk) && !chunk.contains("below_zero_retrogen")
                && !chunk.contains("UpgradeData") && hasOnlyNativeBlending(chunk, dimension)
                && !chunk.contains(SeedTerrainDigest.ATTACHMENTS);
    }

    private static boolean hasOnlyAir(CompoundTag section) {
        var blocks = section.getCompoundOrEmpty("block_states");
        var palette = blocks.getListOrEmpty("palette");
        return blocks.size() == 1 && palette.size() == 1 && isVanillaAir(palette.getFirst());
    }

    private static boolean hasCompleteVanillaAirSections(CompoundTag chunk) {
        var seen = new HashSet<Byte>();
        int terrainCount = 0;
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            if (!(tag instanceof CompoundTag section) || !SECTION_FIELDS.containsAll(section.keySet()))
                return false;
            var y = section.getByte("Y").orElse(null);
            if (y == null || !seen.add(y))
                return false;
            if (!section.contains("biomes") && !section.contains("block_states"))
                continue;
            int index = y - OVERWORLD_MIN_SECTION;
            if (index < 0 || index >= OVERWORLD_SECTION_COUNT || !hasOnlyAir(section))
                return false;
            var biomes = section.getCompoundOrEmpty("biomes");
            if (!BIOME_FIELDS.containsAll(biomes.keySet())
                    || biomeUse(biomes, null, true) != BiomeUse.VANILLA)
                return false;
            terrainCount++;
        }
        return terrainCount == OVERWORLD_SECTION_COUNT;
    }

    private static boolean hasEmptyGenerationData(CompoundTag chunk) {
        String status = chunk.getStringOr("Status", "");
        if (status.startsWith("minecraft:"))
            status = status.substring("minecraft:".length());
        if (!NATIVE_TERRAIN_STATUS.contains(status) || !hasEmptyPostProcessing(chunk))
            return false;
        if (!chunk.contains("Heightmaps"))
            return status.equals("carvers") || status.equals("noise") || status.equals("surface");
        var heights = chunk.getCompoundOrEmpty("Heightmaps");
        return (!status.equals("full") || heights.contains("WORLD_SURFACE")) && hasZeroHeightmaps(chunk);
    }

    private static boolean hasEmptyPostProcessing(CompoundTag chunk) {
        if (!chunk.contains("PostProcessing"))
            return true;
        if (!(chunk.get("PostProcessing") instanceof ListTag sections))
            return false;
        for (Tag section : sections)
            if (!(section instanceof ListTag entries) || !entries.isEmpty())
                return false;
        return true;
    }

    private static boolean hasZeroHeightmaps(CompoundTag chunk) {
        if (!chunk.contains("Heightmaps"))
            return true;
        if (!(chunk.get("Heightmaps") instanceof CompoundTag heightmaps)
                || !HEIGHTMAP_FIELDS.containsAll(heightmaps.keySet()))
            return false;
        for (String key : heightmaps.keySet()) {
            var words = heightmaps.getLongArray(key).orElse(null);
            if (words == null || words.length != HEIGHTMAP_WORDS || !allZero(words))
                return false;
        }
        return true;
    }

    private static boolean allZero(long[] words) {
        for (long word : words)
            if (word != 0)
                return false;
        return true;
    }

    public static boolean hasStructures(CompoundTag chunk) {
        if (!chunk.contains("structures"))
            return false;
        if (!(chunk.get("structures") instanceof CompoundTag structures))
            return true;
        for (String key : structures.keySet()) {
            if (!(structures.get(key) instanceof CompoundTag entries) || !entries.isEmpty())
                return true;
        }
        return false;
    }

    public static boolean hasOnlyNativeBlending(CompoundTag chunk, ResourceKey<Level> dimension) {
        if (!chunk.contains(SeedTerrainDigest.BLENDING_DATA))
            return true;
        if (!dimension.equals(Level.OVERWORLD)
                || !(chunk.get(SeedTerrainDigest.BLENDING_DATA) instanceof CompoundTag tag)
                || !BLENDING_FIELDS.containsAll(tag.keySet()))
            return false;
        // Version upgrades write this metadata even in a void world.
        var packed = BlendingData.Packed.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
        return packed != null && packed.minSection() >= SeedTerrainDigest.BLENDING_MIN_SECTION
                && packed.maxSection() <= SeedTerrainDigest.BLENDING_MAX_SECTION
                && packed.minSection() < packed.maxSection()
                && (!tag.contains("heights") || packed.heights().isPresent());
    }

    private static BiomeUse biomeUse(CompoundTag biomes, String mirrorBiome, boolean allowVanilla) {
        var palette = biomes.getListOrEmpty("palette");
        int size = palette.size();
        if (size == 0 || size > BIOME_CELLS)
            return BiomeUse.INVALID;
        int mirrorIndex = -1;
        for (int i = 0; i < size; i++) {
            String name = palette.getStringOr(i, "");
            if (name.equals(mirrorBiome) && mirrorIndex == -1)
                mirrorIndex = i;
            else if (!allowVanilla || !isVanillaBiome(name))
                return BiomeUse.INVALID;
        }
        if (size == 1) {
            if (biomes.contains("data"))
                return BiomeUse.INVALID;
            return mirrorIndex == 0 ? BiomeUse.MIRROR : BiomeUse.VANILLA;
        }
        int bits = TerrainBitStorage.bits(size);
        var data = biomes.getLongArray("data").orElse(null);
        if (data == null || data.length != TerrainBitStorage.wordCount(BIOME_CELLS, bits))
            return BiomeUse.INVALID;
        boolean hasMirrorBiome = false;
        for (int cell = 0; cell < BIOME_CELLS; cell++) {
            int index = TerrainBitStorage.cell(data, bits, cell);
            if (index >= size)
                return BiomeUse.INVALID;
            hasMirrorBiome |= index == mirrorIndex;
        }
        return hasMirrorBiome ? BiomeUse.MIRROR : BiomeUse.VANILLA;
    }

    private static boolean isVanillaBiome(String name) {
        if (!name.startsWith("minecraft:"))
            return false;
        var id = Identifier.tryParse(name);
        return id != null && !id.getPath().isEmpty();
    }

    public static boolean isVanillaAir(Tag state) {
        if (state instanceof StringTag name)
            return name.asString().orElse("").equals("minecraft:air");
        return state instanceof CompoundTag compound && compound.size() == 1
                && (compound.getStringOr("Name", "").equals("minecraft:air")
                        || compound.getStringOr("id", "").equals("minecraft:air")
                        || compound.getStringOr("", "").equals("minecraft:air"));
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
    public static void replaceBiomes(CompoundTag chunk, ResourceKey<Level> dimension) throws IOException {
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
