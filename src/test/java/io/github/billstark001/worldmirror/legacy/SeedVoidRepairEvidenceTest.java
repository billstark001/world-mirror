package io.github.billstark001.worldmirror.legacy;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedVoidRepairEvidenceTest {
    private static final String MIRROR = "worldmirror:mirror_overworld";
    private static final String PLAINS = "minecraft:plains";
    private static final String DEEP_OCEAN = "minecraft:deep_ocean";
    private static final String AIR = "minecraft:air";
    private static final String WATER = "minecraft:water";
    private static final String LAVA = "minecraft:lava";
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final SeedVoidRepairEvidence.NeighborTerrain NO_NEIGHBORS = (x, z) -> null;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void matchesTheExpectedRemapAcrossDifferentBiomePaletteOrder() throws Exception {
        var before = chunk(0, 0, MIRROR, DEEP_OCEAN, 1L);
        var current = chunk(0, 0, DEEP_OCEAN, PLAINS, ~1L);
        assertTrue(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, NO_NEIGHBORS));

        section(current).getCompoundOrEmpty("biomes").putLongArray("data", new long[] {1});
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, NO_NEIGHBORS));
    }

    @Test
    void requiresNeighborContinuityForAllFlowingComponents() throws Exception {
        var before = chunk(0, 0, MIRROR, null, 0);
        var current = chunk(0, 0, PLAINS, null, 0);
        setBlock(section(current), 0, 5, 5, liquid(WATER, "1", true));
        setBlock(section(current), 1, 5, 5, liquid(WATER, "8", false));
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, NO_NEIGHBORS));

        var west = chunk(-1, 0, PLAINS, null, 0);
        setBlock(section(west), 15, 5, 5, StringTag.valueOf(WATER));
        SeedVoidRepairEvidence.NeighborTerrain reader = (x, z) -> x == -1 && z == 0 ? west : null;
        var ticks = new ListTag();
        ticks.add(tick("minecraft:flowing_water", 0, -59, 5));
        current.put("fluid_ticks", ticks);
        assertTrue(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, reader));

        setBlock(section(current), 7, 8, 7, liquid(WATER, "1", true));
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, reader));
    }

    @Test
    void preservesSourcesReactionBlocksAndUnexpectedTicks() throws Exception {
        var before = chunk(0, 0, MIRROR, null, 0);
        var current = chunk(0, 0, PLAINS, null, 0);
        var neighbor = chunk(-1, 0, PLAINS, null, 0);
        setBlock(section(neighbor), 15, 5, 5, StringTag.valueOf(LAVA));
        SeedVoidRepairEvidence.NeighborTerrain reader = (x, z) -> neighbor;

        setBlock(section(current), 0, 5, 5, liquid(LAVA, "0", true));
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, reader));
        setOnlyBlock(section(current), COBBLESTONE);
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, reader));

        setOnlyBlock(section(current), AIR);
        setBlock(section(current), 0, 5, 5, liquid(LAVA, "2", true));
        var ticks = new ListTag();
        ticks.add(tick("minecraft:flowing_water", 0, -59, 5));
        current.put("fluid_ticks", ticks);
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, reader));
    }

    @Test
    void preservesUnknownMetadataEntitiesAndEarlyChunks() throws Exception {
        var before = chunk(0, 0, MIRROR, null, 0);
        var current = chunk(0, 0, PLAINS, null, 0);
        current.put("mod:metadata", new CompoundTag());
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, NO_NEIGHBORS));
        current.remove("mod:metadata");

        var entities = new ListTag();
        entities.add(new CompoundTag());
        current.put("block_entities", entities);
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, NO_NEIGHBORS));
        current.remove("block_entities");

        before.putString("Status", "minecraft:biomes");
        current.putString("Status", "minecraft:biomes");
        assertFalse(SeedVoidRepairEvidence.matches(before, current, Level.OVERWORLD, NO_NEIGHBORS));
    }

    private static CompoundTag chunk(int x, int z, String firstBiome, String secondBiome, long packedBiomes) {
        var chunk = new CompoundTag();
        chunk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.putInt("xPos", x);
        chunk.putInt("yPos", -4);
        chunk.putInt("zPos", z);
        chunk.putString("Status", "minecraft:full");
        var section = new CompoundTag();
        section.putByte("Y", (byte) -4);
        setOnlyBlock(section, AIR);

        var biomePalette = new ListTag();
        biomePalette.add(StringTag.valueOf(firstBiome));
        var biomes = new CompoundTag();
        if (secondBiome != null) {
            biomePalette.add(StringTag.valueOf(secondBiome));
            biomes.putLongArray("data", new long[] {packedBiomes});
        }
        biomes.put("palette", biomePalette);
        section.put("biomes", biomes);
        var sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }

    private static CompoundTag section(CompoundTag chunk) {
        return chunk.getListOrEmpty("sections").getCompoundOrEmpty(0);
    }

    private static void setOnlyBlock(CompoundTag section, String id) {
        var palette = new ListTag();
        palette.add(StringTag.valueOf(id));
        var states = new CompoundTag();
        states.put("palette", palette);
        section.put("block_states", states);
    }

    private static void setBlock(CompoundTag section, int x, int y, int z, Tag state) {
        var states = section.getCompoundOrEmpty("block_states");
        var palette = states.getListOrEmpty("palette");
        int paletteIndex = palette.indexOf(state);
        if (paletteIndex < 0) {
            paletteIndex = palette.size();
            palette.add(state);
        }
        long[] data = states.getLongArray("data").orElseGet(() -> new long[256]);
        int index = y * 256 + z * 16 + x;
        int offset = index % 16 * 4;
        data[index / 16] = (data[index / 16] & ~(15L << offset)) | (long) paletteIndex << offset;
        states.putLongArray("data", data);
    }

    private static CompoundTag liquid(String type, String level, boolean modern) {
        var tag = new CompoundTag();
        tag.putString(modern ? "id" : "Name", type);
        var properties = new CompoundTag();
        properties.putString("level", level);
        tag.put(modern ? "properties" : "Properties", properties);
        return tag;
    }

    private static CompoundTag tick(String id, int x, int y, int z) {
        var tick = new CompoundTag();
        tick.putString("i", id);
        tick.putInt("p", 0);
        tick.putInt("t", -1);
        tick.putInt("x", x);
        tick.putInt("y", y);
        tick.putInt("z", z);
        return tick;
    }
}
