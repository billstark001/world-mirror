package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.SeedChunkProvenance;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SeedConversionLightingTest {
    @BeforeAll
    static void bootstrap() {
        SeedConversionTest.bootstrap();
    }

    @Test
    void assigningSeedInvalidatesBorderLightWithBlendingOff(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var directory = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD);
        var left = directory.resolve("r.0.0.mca");
        var right = directory.resolve("r.1.0.mca");
        var chunk = SeedConversionTest.terrain("minecraft:stone", "minecraft:plains");
        chunk.putBoolean("isLightOn", true);
        var sections = chunk.getListOrEmpty("sections");
        sections.getCompoundOrEmpty(0).putByteArray("SkyLight", new byte[2048]);
        sections.getCompoundOrEmpty(0).putByteArray("BlockLight", new byte[2048]);
        var lightOnly = new CompoundTag();
        lightOnly.putByte("Y", (byte) 1);
        lightOnly.putByteArray("SkyLight", new byte[2048]);
        sections.add(lightOnly);
        Map<ChunkPos, CompoundTag> leftChunks = new java.util.HashMap<>();
        for (int x = 29; x <= 31; x++)
            for (int z = 0; z <= 2; z++) leftChunks.put(new ChunkPos(x, z), chunk.copy());
        McaWriteSupport.writeChunks(left, Level.OVERWORLD, leftChunks);
        var gap = new ChunkPos(32, 1);
        McaWriteSupport.writeChunks(right, Level.OVERWORLD,
                Map.of(gap, SeedConversionTest.terrain("minecraft:air", "worldmirror:mirror_overworld")));
        var edge = new ChunkPos(31, 1);
        var interior = new ChunkPos(30, 1);
        var expectedEdge = AnvilReader.readChunk(left, edge);
        var originalInterior = AnvilReader.readChunk(left, interior);
        expectedEdge.remove("isLightOn");
        var expectedSections = expectedEdge.getListOrEmpty("sections");
        expectedSections.getCompoundOrEmpty(0).remove("SkyLight");
        expectedSections.getCompoundOrEmpty(0).remove("BlockLight");
        expectedSections.remove(1);

        var preview = SeedConversion.preview(target, SeedConversion.Request.set(25L, false));
        assertEquals(1, preview.preview().removed());
        assertEquals(0, preview.preview().blended());
        assertTrue(Files.isRegularFile(SeedConversion.apply(preview)));
        assertNull(AnvilReader.readChunk(right, gap));
        assertEquals(expectedEdge, AnvilReader.readChunk(left, edge));
        assertFalse(AnvilReader.readChunk(left, new ChunkPos(29, 1)).contains("isLightOn"));
        assertEquals(originalInterior, AnvilReader.readChunk(left, interior));
    }

    @Test
    void relightsRetainedChunksBesidePendingFeaturesButKeepsUnsettledReceipts(@TempDir Path world)
            throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var lit = SeedConversionTest.terrain("minecraft:stone", "minecraft:plains");
        lit.putBoolean("isLightOn", true);
        lit.getListOrEmpty("sections").getCompoundOrEmpty(0).putByteArray("SkyLight", new byte[2048]);
        var proto = lit.copy();
#if MC_VERSION >= 260300
        proto.putString("Status", "minecraft:terrain");
#else
        proto.putString("Status", "minecraft:carvers");
#endif
        var litPositions = new java.util.HashMap<ChunkPos, CompoundTag>();
        for (int x = 0; x < 7; x++)
            for (int z = 0; z < 7; z++) litPositions.put(new ChunkPos(x, z), lit);
        var pending = new ChunkPos(3, 3);
        var neighbor = new ChunkPos(2, 3);
        var protectedNeighbor = new ChunkPos(3, 2);
        var interior = new ChunkPos(5, 3);
        var receipt = lit.copy();
        addReceipt(receipt, target.nodeId(), 42L);
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(index.recordUpdates(
                    "minecraft:overworld", Map.of(protectedNeighbor, 41L), "world_mirror"));
        }
        litPositions.put(pending, proto);
        litPositions.put(protectedNeighbor, receipt);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, litPositions);

        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        assertFalse(AnvilReader.readChunk(region, pending).contains("isLightOn"));
        assertFalse(AnvilReader.readChunk(region, neighbor).contains("isLightOn"));
        assertEquals(lit, AnvilReader.readChunk(region, interior));
        var savedReceipt = AnvilReader.readChunk(region, protectedNeighbor);
        assertEquals(receipt, savedReceipt);
    }

    @Test
    void changingSeedKeepsTheCaptureReceiptRecoverableUntilItsIndexIsCurrent(@TempDir Path world)
            throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var edge = new ChunkPos(1, 0);
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, true)));
        var seeded = WorldMetadata.loadIfPresent(world).orElseThrow();
        var chunk = SeedConversionTest.terrain("minecraft:stone", "minecraft:plains");
        SeedTerrainBlending.markBlend(chunk, seeded.generation.seedEpoch());
        addReceipt(chunk, target.nodeId(), 42L);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(edge, chunk));
        var change = SeedConversion.Request.reseed(26, false);

        assertThrows(java.io.IOException.class, () -> SeedConversion.preview(target, change));
        assertEquals(chunk, AnvilReader.readChunk(region, edge));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(index.recordUpdates("minecraft:overworld", Map.of(edge, 42L), "world_mirror"));
        }
        SeedConversion.apply(SeedConversion.preview(target, change));
        assertFalse(AnvilReader.readChunk(region, edge).contains(SeedTerrainDigest.BLENDING_DATA));
    }

    @Test
    void removingSeedRelightsRetainedNeighborsOfDeletedTerrain(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        var metadata = WorldMetadata.loadIfPresent(world).orElseThrow();
        var generated = new ChunkPos(0, 0);
        var neighbor = new ChunkPos(1, 0);
        var outer = new ChunkPos(2, 0);
        var lit = SeedConversionTest.terrain("minecraft:stone", "minecraft:plains");
        lit.putBoolean("isLightOn", true);
        lit.getListOrEmpty("sections").getCompoundOrEmpty(0).putByteArray("SkyLight", new byte[2048]);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(generated, SeedConversionTest.generated(metadata, false), neighbor, lit, outer, lit));

        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.remove(true)));

        assertNull(AnvilReader.readChunk(region, generated));
        assertEquals(lit, AnvilReader.readChunk(region, outer));
        SeedTerrainLighting.invalidate(lit);
        assertEquals(lit, AnvilReader.readChunk(region, neighbor));
    }

    private static void addReceipt(CompoundTag chunk, String node, long time) {
        var receipt = new CompoundTag();
        receipt.putString(SeedTerrainDigest.CAPTURE_NODE, node);
        receipt.putLong(SeedTerrainDigest.CAPTURE_TIME, time);
        receipt.putString(SeedTerrainDigest.CAPTURE_SOURCE, "world_mirror");
        receipt.putString(SeedTerrainDigest.CAPTURE_DIGEST, SeedChunkProvenance.captureDigest(chunk));
        var attachments = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS);
        attachments.put(SeedTerrainDigest.CAPTURE_RECEIPT, receipt);
        chunk.put(SeedTerrainDigest.ATTACHMENTS, attachments);
    }
}
