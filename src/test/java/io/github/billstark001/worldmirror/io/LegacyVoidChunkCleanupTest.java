package io.github.billstark001.worldmirror.io;

import io.github.ensgijs.nbt.tag.CompoundTag;
import io.github.ensgijs.nbt.tag.ListTag;
import io.github.ensgijs.nbt.tag.StringTag;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import org.junit.jupiter.api.Test;
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

        CompoundTag section = (CompoundTag) ((ListTag<?>) chunk.get("sections")).get(0);
        ListTag<CompoundTag> palette = section.getCompoundTag("block_states").getCompoundList("palette");
        palette.clear();
        palette.add(block("minecraft:stone"));
        assertFalse(LegacyVoidChunkCleanup.isLegacyVoidChunk(chunk));
    }

    @Test
    void preservesVoidChunksWithBlocks() {
        CompoundTag chunk = blankVoidChunk();
        CompoundTag section = (CompoundTag) ((ListTag<?>) chunk.get("sections")).get(0);
        ListTag<CompoundTag> palette = section.getCompoundTag("block_states").getCompoundList("palette");
        palette.clear();
        palette.add(block("minecraft:chest"));
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

    @Test
    void temporaryRegionFileRetainsTheMcaCoordinateSuffix(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("r.-2.7.mca");
        Path temporary = LegacyVoidChunkCleanup.createTemporaryRegionFile(source);
        try {
            var coordinates = McaFileHelpers.regionXZFromFileName(temporary.getFileName().toString());
            assertTrue(temporary.getFileName().toString().endsWith(".mca"));
            assertEquals(-2, coordinates.getX());
            assertEquals(7, coordinates.getZ());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static CompoundTag blankVoidChunk() {
        CompoundTag blockStates = new CompoundTag();
        blockStates.put("palette", new ListTag<>(new ArrayList<>(List.of(block("minecraft:air")))));
        CompoundTag biomes = new CompoundTag();
        biomes.put("palette", new ListTag<>(List.of(new StringTag("minecraft:the_void"))));
        CompoundTag section = new CompoundTag();
        section.put("block_states", blockStates);
        section.put("biomes", biomes);
        CompoundTag chunk = new CompoundTag();
        chunk.put("sections", new ListTag<>(List.of(section)));
        chunk.put("block_entities", new ListTag<>(List.of()));
        return chunk;
    }

    private static CompoundTag block(String name) {
        CompoundTag block = new CompoundTag();
        block.put("Name", new StringTag(name));
        return block;
    }
}
