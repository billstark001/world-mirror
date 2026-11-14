package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.download.SeedTerrainBiomes;
import io.github.billstark001.worldmirror.download.TerrainBitStorage;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Checks whether an alpha-converted chunk still contains only the old void or incoming fluid. */
public final class SeedVoidRepairEvidence {
    private static final Set<String> CHUNK_KEYS = Set.of("DataVersion", "Heightmaps", "InhabitedTime",
            "LastUpdate", "PostProcessing", "Status", "block_entities", "block_ticks", "entities", "Entities",
            "fluid_ticks", "isLightOn", "sections", "structures", "xPos", "yPos", "zPos", "carving_mask",
            SeedTerrainDigest.BLENDING_DATA);
    private static final Set<String> SECTION_KEYS =
            Set.of("Y", "SkyLight", "BlockLight", "biomes", "block_states");
    private static final Set<String> BIOME_KEYS = Set.of("palette", "data");
    private static final Set<String> TICK_KEYS = Set.of("i", "p", "t", "x", "y", "z");
    private static final Set<String> PRE_TERRAIN =
            Set.of("empty", "structure_starts", "structure_references", "biomes");
    private static final Set<String> POST_TERRAIN = Set.of("noise", "surface", "terrain", "carvers",
            "features", "initialize_light", "light", "spawn", "full");

    @FunctionalInterface
    public interface NeighborTerrain {
        CompoundTag read(int chunkX, int chunkZ) throws IOException;
    }

    record Spill(int x, int z, Map<Integer, byte[]> sections) {
        boolean reaches(NeighborTerrain neighbors) throws IOException {
            return SeedVoidRepairFluids.connected(sections, x, z, neighbors);
        }
    }

    static boolean archivedVoid(CompoundTag chunk, ResourceKey<Level> dimension) {
        return SeedTerrainBiomes.isOwnedVoid(chunk, dimension)
                || SeedTerrainBiomes.isNativeVoid(chunk, dimension);
    }

    private SeedVoidRepairEvidence() {}

    public static boolean matches(CompoundTag before, CompoundTag current, ResourceKey<Level> dimension,
            NeighborTerrain neighbors) throws IOException {
        if (!archivedVoid(before, dimension))
            return false;
        var spill = matchArchivedVoid(before, current, dimension);
        return spill != null && spill.reaches(neighbors);
    }

    static Spill matchArchivedVoid(CompoundTag before, CompoundTag current, ResourceKey<Level> dimension)
            throws IOException {
        if (!matchesChunkMetadata(before, current, dimension))
            return null;
        var expected = before.copy();
        SeedTerrainBiomes.replaceBiomes(expected, dimension);
        var expectedSections = terrainSections(expected);
        var currentSections = terrainSections(current);
        if (expectedSections == null || currentSections == null
                || !expectedSections.keySet().equals(currentSections.keySet()))
            return null;
        var flows = new HashMap<Integer, byte[]>();
        for (var entry : currentSections.entrySet()) {
            var section = entry.getValue();
            if (!matchesBiomes(expectedSections.get(entry.getKey()), section))
                return null;
            var cells = SeedVoidRepairFluids.flowingCells(section, dimension.equals(Level.OVERWORLD));
            if (cells == null)
                return null;
            if (cells.length != 0)
                flows.put(entry.getKey(), cells);
        }
        int x = current.getIntOr("xPos", 0);
        int z = current.getIntOr("zPos", 0);
        return hasOnlyFluidTicks(current, flows, x, z) ? new Spill(x, z, flows) : null;
    }

    private static boolean matchesChunkMetadata(
            CompoundTag before, CompoundTag current, ResourceKey<Level> dimension) {
        int oldVersion = before.getIntOr("DataVersion", 0);
        int newVersion = current.getIntOr("DataVersion", 0);
        String oldStatus = status(before);
        String newStatus = status(current);
        if (oldVersion <= 0 || newVersion < oldVersion
                || newVersion > SharedConstants.getCurrentVersion().dataVersion().version()
                || !matchesCoordinates(before, current)
                || !(PRE_TERRAIN.contains(oldStatus) || POST_TERRAIN.contains(oldStatus))
                || !POST_TERRAIN.contains(newStatus)
                || ("full".equals(oldStatus) && !"full".equals(newStatus))
                || !CHUNK_KEYS.containsAll(before.keySet()) || !CHUNK_KEYS.containsAll(current.keySet())
                || SeedTerrainBiomes.hasStructures(current)
                || !SeedTerrainBiomes.hasOnlyNativeBlending(current, dimension))
            return false;
        for (String field : Set.of("block_entities", "block_ticks", "entities", "Entities")) {
            if (current.contains(field) && (!(current.get(field) instanceof ListTag list) || !list.isEmpty()))
                return false;
        }
        if (current.contains("PostProcessing") && !emptyPostProcessing(current.get("PostProcessing")))
            return false;
        var carvingMask = current.get("carving_mask");
        return carvingMask == null || carvingMask.equals(before.get("carving_mask"));
    }

    private static boolean matchesCoordinates(CompoundTag before, CompoundTag current) {
        for (String field : Set.of("xPos", "yPos", "zPos")) {
            if (!(current.get(field) instanceof IntTag coordinate) || !coordinate.equals(before.get(field)))
                return false;
        }
        return true;
    }

    private static String status(CompoundTag chunk) {
        String status = chunk.getStringOr("Status", "");
        return status.startsWith("minecraft:") ? status.substring("minecraft:".length()) : status;
    }

    private static boolean emptyPostProcessing(Tag data) {
        if (!(data instanceof ListTag sections))
            return false;
        for (Tag values : sections)
            if (!(values instanceof ListTag list) || !list.isEmpty())
                return false;
        return true;
    }

    private static Map<Integer, CompoundTag> terrainSections(CompoundTag chunk) {
        if (!(chunk.get("sections") instanceof ListTag list))
            return null;
        Map<Integer, CompoundTag> result = new HashMap<>();
        for (Tag tag : list) {
            if (!(tag instanceof CompoundTag section) || !SECTION_KEYS.containsAll(section.keySet())
                    || section.getByte("Y").isEmpty())
                return null;
            if (!section.contains("biomes") && !section.contains("block_states"))
                continue;
            if (result.putIfAbsent((int) section.getByteOr("Y", (byte) 0), section) != null)
                return null;
        }
        return result;
    }

    private static boolean matchesBiomes(CompoundTag expected, CompoundTag current) {
        var oldBiomes = expected.getCompoundOrEmpty("biomes");
        var newBiomes = current.getCompoundOrEmpty("biomes");
        var oldPalette = oldBiomes.getListOrEmpty("palette");
        var newPalette = newBiomes.getListOrEmpty("palette");
        int oldSize = oldPalette.size();
        int newSize = newPalette.size();
        if (oldSize == 0 || newSize == 0 || newSize > 64 || !BIOME_KEYS.containsAll(oldBiomes.keySet())
                || !BIOME_KEYS.containsAll(newBiomes.keySet()))
            return false;
        int oldBits = TerrainBitStorage.bits(oldSize);
        int newBits = TerrainBitStorage.bits(newSize);
        long[] oldData = oldBiomes.getLongArray("data").orElse(null);
        long[] newData = newBiomes.getLongArray("data").orElse(null);
        if (newSize == 1 ? newBiomes.contains("data")
                         : newData == null || newData.length != TerrainBitStorage.wordCount(64, newBits))
            return false;
        for (int cell = 0; cell < 64; cell++) {
            int oldIndex = oldSize == 1 ? 0 : TerrainBitStorage.cell(oldData, oldBits, cell);
            int newIndex = newSize == 1 ? 0 : TerrainBitStorage.cell(newData, newBits, cell);
            if (newIndex >= newSize || !oldPalette.get(oldIndex).equals(newPalette.get(newIndex)))
                return false;
        }
        return true;
    }

    private static boolean hasOnlyFluidTicks(CompoundTag chunk, Map<Integer, byte[]> flows, int x, int z) {
        if (!chunk.contains("fluid_ticks"))
            return true;
        if (!(chunk.get("fluid_ticks") instanceof ListTag ticks))
            return false;
        for (Tag tag : ticks) {
            if (!(tag instanceof CompoundTag tick) || !TICK_KEYS.equals(tick.keySet())
                    || !isMatchingFluidTick(tick, flows, x, z))
                return false;
        }
        return true;
    }

    private static boolean isMatchingFluidTick(CompoundTag tick, Map<Integer, byte[]> flows, int x, int z) {
        if (!(tick.get("x") instanceof IntTag tx) || !(tick.get("y") instanceof IntTag ty)
                || !(tick.get("z") instanceof IntTag tz) || !(tick.get("p") instanceof IntTag)
                || !(tick.get("t") instanceof IntTag) || (tx.value() >> 4) != x || (tz.value() >> 4) != z)
            return false;
        byte type = SeedVoidRepairFluids.fluidAt(flows, tx.value() & 15, ty.value(), tz.value() & 15);
        String id = tick.getStringOr("i", "");
        return type == SeedVoidRepairFluids.WATER && id.equals("minecraft:flowing_water")
                || type == SeedVoidRepairFluids.LAVA && id.equals("minecraft:flowing_lava");
    }
}
