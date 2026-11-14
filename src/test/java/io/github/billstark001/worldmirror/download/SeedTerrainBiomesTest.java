package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedTerrainBiomesTest {
    private static final String MIRROR = "worldmirror:mirror_overworld";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recognizesAnAirChunkWithBlendedVanillaBiomesAndNativeBlendingData() {
        CompoundTag chunk = chunk("minecraft:deep_ocean", MIRROR, "minecraft:dripstone_caves");
        biomeContainer(chunk).putLongArray("data", new long[] {9, 0});
        var blend = blendingData();
        var heights = new ListTag();
        for (int i = 0; i < 16; i++) heights.add(DoubleTag.valueOf(i < 7 ? -64.0 : Double.MAX_VALUE));
        blend.put("heights", heights);
        chunk.put(SeedTerrainDigest.BLENDING_DATA, blend);

        assertTrue(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
    }

    @Test
    void requiresAnActualOwnedBiomeCellAndValidPackedIndices() {
        CompoundTag chunk = chunk("minecraft:deep_ocean", MIRROR, "minecraft:dripstone_caves");
        CompoundTag biomes = biomeContainer(chunk);
        biomes.putLongArray("data", new long[] {0, 0});
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));

        biomes.putLongArray("data", new long[] {13, 0});
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));

        biomes.putLongArray("data", new long[] {1});
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
    }

    @Test
    void preservesEmptyChunksWithForeignBiomesOrUnknownBlendingData() {
        CompoundTag foreign = chunk(MIRROR, "example:custom");
        biomeContainer(foreign).putLongArray("data", new long[] {1});
        assertFalse(SeedTerrainBiomes.isOwnedVoid(foreign, Level.OVERWORLD));

        CompoundTag chunk = chunk(MIRROR);
        var blend = blendingData();
        blend.putString("custom", "metadata");
        chunk.put(SeedTerrainDigest.BLENDING_DATA, blend);
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));

        blend.remove("custom");
        blend.putString("heights", "invalid");
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
    }

    @Test
    void preservesChunksWithSolidBlocksOrAttachments() {
        CompoundTag chunk = chunk(MIRROR);
        var blocks = section(chunk).getCompoundOrEmpty("block_states");
        var palette = blocks.getListOrEmpty("palette");
        palette.set(0, StringTag.valueOf("minecraft:stone"));
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));

        palette.set(0, StringTag.valueOf("minecraft:air"));
        var attachments = new CompoundTag();
        attachments.putString(SeedTerrainDigest.BLENDING_ATTACHMENT, "other generation");
        chunk.put(SeedTerrainDigest.ATTACHMENTS, attachments);
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
    }

    @Test
    void preservesOwnedBiomeAirWithOtherSavedContent() {
        var chunk = chunk(MIRROR);
        chunk.putString("other_mod:data", "protected");
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
        chunk.remove("other_mod:data");

        var section = section(chunk);
        section.putString("other_mod:section", "protected");
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
        section.remove("other_mod:section");
        var blocks = section.getCompoundOrEmpty("block_states");
        blocks.putString("other_mod:palette", "protected");
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
        blocks.remove("other_mod:palette");

        var heightmaps = new CompoundTag();
        var heights = new long[TerrainBitStorage.wordCount(256, 9)];
        heights[0] = 64;
        heightmaps.putLongArray("WORLD_SURFACE", heights);
        chunk.put("Heightmaps", heightmaps);
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
        heights[0] = 0;
        heightmaps.putLongArray("WORLD_SURFACE", heights);
        assertTrue(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
    }

    @Test
    void recognizesCompleteNativeBiomeVoidOnlyWithEmptyGenerationEvidence() {
        var chunk = chunk("minecraft:ocean");
        var sections = chunk.getListOrEmpty("sections");
        for (int y = -3; y < 20; y++) {
            var section = sections.getCompoundOrEmpty(0).copy();
            section.putByte("Y", (byte) y);
            sections.add(section);
        }
        chunk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.putInt("yPos", -4);
        chunk.putLong("InhabitedTime", 0);
        chunk.putString("Status", "minecraft:full");
        var heightmaps = new CompoundTag();
        var surface = new long[TerrainBitStorage.wordCount(256, 9)];
        heightmaps.putLongArray("WORLD_SURFACE", surface);
        chunk.put("Heightmaps", heightmaps);
        assertTrue(SeedTerrainBiomes.isNativeVoid(chunk, Level.OVERWORLD));
        assertFalse(SeedTerrainBiomes.isOwnedVoid(chunk, Level.OVERWORLD));
        assertFalse(SeedTerrainBiomes.isNativeVoid(chunk, Level.NETHER));

        chunk.putLong("InhabitedTime", 1);
        assertFalse(SeedTerrainBiomes.isNativeVoid(chunk, Level.OVERWORLD));
        chunk.putLong("InhabitedTime", 0);
        surface[0] = 1;
        heightmaps.putLongArray("WORLD_SURFACE", surface);
        assertFalse(SeedTerrainBiomes.isNativeVoid(chunk, Level.OVERWORLD));
        surface[0] = 0;
        heightmaps.putLongArray("WORLD_SURFACE", surface);
        sections.removeLast();
        assertFalse(SeedTerrainBiomes.isNativeVoid(chunk, Level.OVERWORLD));
    }

    @Test
    void corroboratesWholeCardinalComponentsAcrossRegionEdges() {
        var edge = new ChunkPos(-1, 0);
        var middle = new ChunkPos(0, 0);
        var next = new ChunkPos(1, 0);
        var diagonal = new ChunkPos(2, 1);
        var captureEdge = new ChunkPos(7, 0);
        var candidates = Set.of(edge, middle, next, diagonal, captureEdge);
        assertEquals(Set.of(edge, middle, next, captureEdge),
                SeedTerrainBiomes.corroboratedNativeVoids(
                        candidates, Set.of(new ChunkPos(-2, 0)), Set.of(new ChunkPos(7, 1))));
    }

    private static CompoundTag chunk(String... biomeNames) {
        var blockPalette = new ListTag();
        var legacyAir = new CompoundTag();
        legacyAir.putString("Name", "minecraft:air");
        blockPalette.add(legacyAir);
        var blocks = new CompoundTag();
        blocks.put("palette", blockPalette);

        var biomePalette = new ListTag();
        for (String name : biomeNames) biomePalette.add(StringTag.valueOf(name));
        var biomes = new CompoundTag();
        biomes.put("palette", biomePalette);

        var section = new CompoundTag();
        section.putByte("Y", (byte) -4);
        section.put("block_states", blocks);
        section.put("biomes", biomes);
        var sections = new ListTag();
        sections.add(section);
        var chunk = new CompoundTag();
        chunk.put("sections", sections);
        return chunk;
    }

    private static CompoundTag section(CompoundTag chunk) {
        return chunk.getListOrEmpty("sections").getCompoundOrEmpty(0);
    }

    private static CompoundTag biomeContainer(CompoundTag chunk) {
        return section(chunk).getCompoundOrEmpty("biomes");
    }

    private static CompoundTag blendingData() {
        var blend = new CompoundTag();
        blend.putInt("min_section", -4);
        blend.putInt("max_section", 20);
        return blend;
    }
}
