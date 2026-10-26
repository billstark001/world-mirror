package io.github.billstark001.worldmirror.io;

import org.junit.jupiter.api.Test;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyVoidChunkCleanupTest {

    @Test
    void recognizesOnlyAnEmptyTheVoidChunk() {
        CompoundTag chunk = blankVoidChunk();
        assertTrue(LegacyVoidChunkCleanup.isLegacyVoidChunk(chunk));

        CompoundTag section = (CompoundTag) ((ListTag) chunk.get("sections")).get(0);
        ListTag palette = section.getCompoundOrEmpty("block_states").getListOrEmpty("palette");
        palette.clear();
        palette.add(block("minecraft:stone"));
        assertFalse(LegacyVoidChunkCleanup.isLegacyVoidChunk(chunk));
    }

    @Test
    void preservesVoidChunksWithBlocks() {
        CompoundTag chunk = blankVoidChunk();
        CompoundTag section = (CompoundTag) ((ListTag) chunk.get("sections")).get(0);
        ListTag palette = section.getCompoundOrEmpty("block_states").getListOrEmpty("palette");
        palette.clear();
        palette.add(block("minecraft:chest"));
        assertFalse(LegacyVoidChunkCleanup.isLegacyVoidChunk(chunk));
    }

    @Test
    void preservesChunksWithSavedBlockEntitiesEvenWhenTerrainIsAir() {
        CompoundTag chunk = blankVoidChunk();
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chunk.put("block_entities", list(chest));
        assertFalse(LegacyVoidChunkCleanup.isLegacyVoidChunk(chunk));
    }

    @Test
    void ignoresPaletteLikeFieldsOutsideTerrainSections() {
        CompoundTag chunk = new CompoundTag();
        chunk.put("custom_payload", blankVoidChunk());
        assertFalse(LegacyVoidChunkCleanup.isLegacyVoidChunk(chunk));
    }

    @Test
    void preservesMalformedSectionsInsteadOfInferringThatTheyAreAir() {
        CompoundTag chunk = blankVoidChunk();
        CompoundTag section = (CompoundTag) ((ListTag) chunk.get("sections")).get(0);
        section.remove("block_states");
        assertFalse(LegacyVoidChunkCleanup.isLegacyVoidChunk(chunk));
    }

    @Test
    void skipsZeroByteRegionFilesInEveryDimension(@TempDir Path world) throws Exception {
        for (Path dimension : List.of(
                Path.of("region"),
                Path.of("DIM-1", "region"),
                Path.of("DIM1", "region"),
                Path.of("dimensions", "minecraft", "overworld", "region"),
                Path.of("dimensions", "minecraft", "the_nether", "region"),
                Path.of("dimensions", "minecraft", "the_end", "region"))) {
            Path region = world.resolve(dimension).resolve("r.0.0.mca");
            Files.createDirectories(region.getParent());
            Files.createFile(region);
        }

        assertTrue(LegacyVoidChunkCleanup.plan(world).regions().isEmpty());
    }

    private static CompoundTag blankVoidChunk() {
        CompoundTag blockStates = new CompoundTag();
        blockStates.put("palette", list(block("minecraft:air")));
        CompoundTag biomes = new CompoundTag();
        biomes.put("palette", list(StringTag.valueOf("minecraft:the_void")));
        CompoundTag section = new CompoundTag();
        section.put("block_states", blockStates);
        section.put("biomes", biomes);
        CompoundTag chunk = new CompoundTag();
        chunk.put("sections", list(section));
        chunk.put("block_entities", new ListTag());
        return chunk;
    }

    private static ListTag list(net.minecraft.nbt.Tag... tags) {
        ListTag list = new ListTag();
        for (var tag : tags) list.add(tag);
        return list;
    }

    private static CompoundTag block(String name) {
        CompoundTag block = new CompoundTag();
        block.put("Name", StringTag.valueOf(name));
        return block;
    }
}
