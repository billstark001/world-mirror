package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedTerrainDigestTest {
    @Test
    void canonicalizesSectionAndEntityOrderAndIgnoresLightingAndRuntimeMetadata() {
        var blocks = Map.of("palette", List.of(Map.of("Name", "minecraft:stone")));
        var first = Map.of("Y", (byte) 0, "block_states", blocks, "BlockLight", new byte[] {1});
        var second = Map.of("Y", (byte) 1, "block_states", blocks);
        var chest = Map.of("id", "minecraft:chest", "x", 1, "z", 0);
        var furnace = Map.of("id", "minecraft:furnace", "x", 2, "z", 0);
        String original = SeedTerrainDigest.digest(Map.of("sections", List.of(first, second),
                "block_entities", List.of(chest, furnace), "LastUpdate", 8L));
        assertEquals(original,
                SeedTerrainDigest.digest(Map.of("sections",
                        List.of(second,
                                Map.of("Y", (byte) 0, "block_states", blocks, "BlockLight", new byte[] {9}),
                                Map.of("Y", (byte) -1, "SkyLight", new byte[] {7})),
                        "block_entities", List.of(furnace, chest), "LastUpdate", 2L)));
        assertNotEquals(original,
                SeedTerrainDigest.digest(Map.of("sections", List.of(first, second), "block_entities",
                        List.of(furnace, Map.of("id", "minecraft:chest", "x", (short) 1, "z", 0)))));
    }

    @Test
    void rejectsDuplicateAndMissingSectionHeights() {
        var section = Map.of("Y", (byte) 0);
        assertThrows(IllegalArgumentException.class,
                () -> SeedTerrainDigest.digest(Map.of("sections", List.of(section, section))));
        assertThrows(IllegalArgumentException.class,
                () -> SeedTerrainDigest.digest(Map.of("sections", List.of(Map.of()))));
    }
}
