package io.github.billstark001.worldmirror.download;

import com.google.gson.JsonParser;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.SeedChunkProvenance;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.JsonSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SeededTerrainRepairTest {
    private static final ChunkPos CAPTURE = new ChunkPos(0, 0);
    private static final ChunkPos FLOW = new ChunkPos(1, 0);
    private static final ChunkPos EMPTY = new ChunkPos(2, 0);
    private static final ChunkPos AMBIGUOUS = new ChunkPos(3, 0);
    private static final ChunkPos INDEXED = new ChunkPos(4, 0);
    private static final String MIRROR_BIOME = "worldmirror:mirror_overworld";
    private static final String PLAINS = "minecraft:plains";

    @BeforeAll
    static void bootstrap() {
        SeedConversionTest.bootstrap();
    }

    @Test
    void removesOnlyProvenLegacyVoidsAndRelightsRetainedChunks(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var captured = terrain(CAPTURE, "minecraft:water", PLAINS);
        addLight(captured);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(CAPTURE, captured, FLOW, mixedAir(FLOW, MIRROR_BIOME), EMPTY,
                        mixedAir(EMPTY, MIRROR_BIOME), AMBIGUOUS, mixedAir(AMBIGUOUS, MIRROR_BIOME), INDEXED,
                        mixedAir(INDEXED, MIRROR_BIOME)));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(index.recordUpdates(
                    "minecraft:overworld", Map.of(CAPTURE, 42L, INDEXED, 43L), "world_mirror"));
        }
        var archive =
                SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        assertTrue(Files.isRegularFile(archive));
        assertNull(AnvilReader.readChunk(region, EMPTY));

        var flowing = mixedAir(FLOW, PLAINS);
        addFlowingWater(flowing);
        var ambiguous = terrain(AMBIGUOUS, "minecraft:cobblestone", PLAINS);
        mixDeepOcean(ambiguous);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(CAPTURE, captured, FLOW, flowing, EMPTY, mixedAir(EMPTY, PLAINS), AMBIGUOUS,
                        ambiguous));
        var indexed = AnvilReader.readChunk(region, INDEXED);
        var records = ChunkDatabase.queryAllReadOnly(world, target.sourceId(), "minecraft:overworld");
        var old = emulateAlpha(world);
        assertTrue(old.needsSeededTerrainRepair());
        assertTrue(repair(world));

        var repaired = WorldMetadata.loadIfPresent(world).orElseThrow();
        assertFalse(repaired.needsSeededTerrainRepair());
        assertEquals(old.generation.seedEpoch(), repaired.generation.seedEpoch());
        assertEquals(old.generation.settingsDigest(), repaired.generation.settingsDigest());
        assertEquals(25, WorldStructureCreator.readSeed(world));
        assertNull(AnvilReader.readChunk(region, FLOW));
        assertNull(AnvilReader.readChunk(region, EMPTY));
        assertEquals(ambiguous, AnvilReader.readChunk(region, AMBIGUOUS));
        assertEquals(indexed, AnvilReader.readChunk(region, INDEXED));
        assertEquals(
                records, ChunkDatabase.queryAllReadOnly(world, target.sourceId(), "minecraft:overworld"));

        var expectedCapture = captured.copy();
        SeedTerrainLighting.invalidate(expectedCapture);
        assertEquals(expectedCapture, AnvilReader.readChunk(region, CAPTURE));
        Set<Path> backups = backupFiles(world);
        assertEquals(2, backups.size());
        assertTrue(backups.contains(archive));
        assertFalse(repair(world));
        assertEquals(backups, backupFiles(world));
    }

    @Test
    void leavesAlphaSaveEligibleWhenNoConversionBackupExists(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var archive =
                SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        Files.delete(archive);
        var old = emulateAlpha(world);
        var metadata = world.resolve(WorldMetadata.FILE_NAME);
        byte[] previous = Files.readAllBytes(metadata);
        assertFalse(repair(world));
        assertArrayEquals(previous, Files.readAllBytes(metadata));
        assertEquals(old.generation, WorldMetadata.loadIfPresent(world).orElseThrow().generation);
        assertTrue(backupFiles(world).isEmpty());
    }

    @Test
    void preservesEditedGeneratedTerrainEvenAtAConfirmedVoidDonor(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var donor = mixedAir(FLOW, MIRROR_BIOME);
        var blend = new CompoundTag();
        blend.putInt("min_section", -4);
        blend.putInt("max_section", 20);
        donor.put("blending_data", blend);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(FLOW, donor, EMPTY, mixedAir(EMPTY, MIRROR_BIOME), AMBIGUOUS,
                        mixedAir(AMBIGUOUS, MIRROR_BIOME)));
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));

        var old = emulateAlpha(world);
        var currentDonor = mixedAir(FLOW, PLAINS);
        currentDonor.put("blending_data", blend);
        var edited = terrain(EMPTY, "minecraft:stone", PLAINS);
        addDeepReactionWall(edited);
        markGenerated(edited, old, true);
        var unmodified = terrain(AMBIGUOUS, "minecraft:stone", PLAINS);
        markGenerated(unmodified, old, false);
        McaWriteSupport.writeChunks(
                region, Level.OVERWORLD, Map.of(FLOW, currentDonor, EMPTY, edited, AMBIGUOUS, unmodified));
        assertTrue(repair(world));

        assertNull(AnvilReader.readChunk(region, FLOW));
        assertEquals(edited, AnvilReader.readChunk(region, EMPTY));
        assertNull(AnvilReader.readChunk(region, AMBIGUOUS));
    }

    @Test
    void staleCaptureRowDoesNotProveLegacyNativeAir(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var nativeAir = nativeAir(FLOW);
        var archivedOwnedAir = mixedAir(AMBIGUOUS, MIRROR_BIOME);
        McaWriteSupport.writeChunks(
                region, Level.OVERWORLD, Map.of(FLOW, nativeAir, AMBIGUOUS, archivedOwnedAir));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(index.recordUpdates("minecraft:overworld", Map.of(CAPTURE, 42L), "world_mirror"));
        }
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        var currentOwnedAir = mixedAir(AMBIGUOUS, PLAINS);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(AMBIGUOUS, currentOwnedAir));
        emulateAlpha(world);

        assertTrue(repair(world));
        assertNull(AnvilReader.readChunk(region, CAPTURE));
        assertEquals(nativeAir, AnvilReader.readChunk(region, FLOW));
        assertNull(AnvilReader.readChunk(region, AMBIGUOUS));
    }

    @Test
    void anAlreadyClearedOwnedVoidCorroboratesAdjacentLegacyNativeAir(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var nativeAir = nativeAir(FLOW);
        McaWriteSupport.writeChunks(
                region, Level.OVERWORLD, Map.of(CAPTURE, mixedAir(CAPTURE, MIRROR_BIOME), FLOW, nativeAir));
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        assertNull(AnvilReader.readChunk(region, CAPTURE));

        // Alpha.1 left the native-only biome payload behind, despite removing the adjacent owned void.
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(FLOW, nativeAir));
        emulateAlpha(world);
        assertTrue(repair(world));

        assertNull(AnvilReader.readChunk(region, FLOW));
    }

    @Test
    void relightsBothSidesBesideRetainedLegacyGeneration(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var retained = new ChunkPos(0, 0);
        var neighbor = new ChunkPos(1, 0);
        var outside = new ChunkPos(2, 0);
        McaWriteSupport.writeChunks(
                region, Level.OVERWORLD, Map.of(retained, mixedAir(retained, MIRROR_BIOME)));
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        var metadata = emulateAlpha(world);
        var generated = terrain(retained, "minecraft:stone", PLAINS);
        markGenerated(generated, metadata, true);
        addLight(generated);
        var neighboringTerrain = terrain(neighbor, "minecraft:stone", PLAINS);
        addLight(neighboringTerrain);
        var outsideTerrain = terrain(outside, "minecraft:stone", PLAINS);
        addLight(outsideTerrain);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(retained, generated, neighbor, neighboringTerrain, outside, outsideTerrain));

        assertTrue(repair(world));
        SeedTerrainLighting.invalidate(generated);
        SeedTerrainLighting.invalidate(neighboringTerrain);
        assertEquals(generated, AnvilReader.readChunk(region, retained));
        assertEquals(neighboringTerrain, AnvilReader.readChunk(region, neighbor));
        assertEquals(outsideTerrain, AnvilReader.readChunk(region, outside));
    }

    private static boolean repair(Path world) throws Exception {
        try (var lock = WorldWriteLock.acquire(world)) {
            return SeededTerrainRepair.repairLocked(world, WorldMetadata.loadIfPresent(world).orElseThrow());
        }
    }

    private static Set<Path> backupFiles(Path world) throws Exception {
        try (var files = Files.list(world.resolve("backups"))) {
            return Set.copyOf(files.toList());
        }
    }

    private static WorldMetadata emulateAlpha(Path world) throws Exception {
        var path = world.resolve(WorldMetadata.FILE_NAME);
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        json.addProperty("modVersion", "0.6.0-alpha.1");
        json.getAsJsonObject("generation").addProperty("cleanupRevision", 1);
        JsonSupport.writeDocument(path, json);
        return WorldMetadata.loadIfPresent(world).orElseThrow();
    }

    private static CompoundTag terrain(ChunkPos pos, String block, String biome) {
        var chunk = SeedConversionTest.terrain(block, biome);
        chunk.putInt("xPos", SeedTerrainFiles.chunkX(pos));
        chunk.putInt("yPos", 0);
        chunk.putInt("zPos", SeedTerrainFiles.chunkZ(pos));
        return chunk;
    }

    private static CompoundTag mixedAir(ChunkPos pos, String biome) {
        var chunk = terrain(pos, "minecraft:air", biome);
        mixDeepOcean(chunk);
        return chunk;
    }

    private static CompoundTag nativeAir(ChunkPos pos) {
        var chunk = terrain(pos, "minecraft:air", PLAINS);
        var section = section(chunk).copy();
        var sections = new ListTag();
        for (int y = -4; y < 20; y++) {
            var atHeight = section.copy();
            atHeight.putByte("Y", (byte) y);
            sections.add(atHeight);
        }
        chunk.put("sections", sections);
        chunk.putInt("yPos", -4);
        chunk.putLong("InhabitedTime", 0);
        var heights = new CompoundTag();
        heights.putLongArray("WORLD_SURFACE", new long[TerrainBitStorage.wordCount(256, 9)]);
        chunk.put("Heightmaps", heights);
        return chunk;
    }

    private static void markGenerated(CompoundTag chunk, WorldMetadata metadata, boolean edited) {
        chunk.putLong("InhabitedTime", 0);
        int version = chunk.getIntOr("DataVersion", 0);
        var baseline = SeedChunkProvenance.baseline(chunk, metadata.generation.seedEpoch(), version);
        var provenance = new CompoundTag();
        provenance.putInt(SeedTerrainDigest.SEED_SCHEMA, SeedTerrainDigest.SEED_SCHEMA_VERSION);
        provenance.putInt(SeedTerrainDigest.DATA_VERSION, version);
        provenance.putString(SeedTerrainDigest.EPOCH, baseline.epoch().toString());
        provenance.putString(SeedTerrainDigest.BASELINE, baseline.baseline());
        provenance.putBoolean(SeedTerrainDigest.SEED_EDITED, edited);
        var attachments = new CompoundTag();
        attachments.put(SeedTerrainDigest.ATTACHMENT, provenance);
        chunk.put(SeedTerrainDigest.ATTACHMENTS, attachments);
    }

    private static void addDeepReactionWall(CompoundTag chunk) {
        chunk.put("block_entities", new ListTag());
        var heights = new long[TerrainBitStorage.wordCount(256, 9)];
        for (int i = 0; i < 256; i++) heights[i / 7] |= 1L << ((i % 7) * 9);
        var heightmaps = new CompoundTag();
        heightmaps.putLongArray("OCEAN_FLOOR", heights);
        chunk.put("Heightmaps", heightmaps);
        var section = section(chunk);
        section.putByte("Y", (byte) -4);
        var blocks = section.getCompoundOrEmpty("block_states");
        blocks.getListOrEmpty("palette").add(StringTag.valueOf("minecraft:obsidian"));
        var cells = new long[TerrainBitStorage.wordCount(4096, 4)];
        for (int y = 4; y < 6; y++)
            for (int z = 2; z < 10; z++) cells[y * 16 + z] = 1;
        blocks.putLongArray("data", cells);
    }

    private static void mixDeepOcean(CompoundTag chunk) {
        var biome = section(chunk).getCompoundOrEmpty("biomes");
        biome.getListOrEmpty("palette").add(StringTag.valueOf("minecraft:deep_ocean"));
        biome.putLongArray("data", new long[] {1L});
    }

    private static void addLight(CompoundTag chunk) {
        chunk.putBoolean("isLightOn", true);
        section(chunk).putByteArray("SkyLight", new byte[2048]);
        section(chunk).putByteArray("BlockLight", new byte[2048]);
    }

    private static void addFlowingWater(CompoundTag chunk) {
        var states = section(chunk).getCompoundOrEmpty("block_states");
        var water = new CompoundTag();
        water.putString("Name", "minecraft:water");
        var properties = new CompoundTag();
        properties.putString("level", "1");
        water.put("Properties", properties);
        states.getListOrEmpty("palette").add(water);
        var packed = new long[256];
        packed[0] = 1;
        states.putLongArray("data", packed);
    }

    private static CompoundTag section(CompoundTag chunk) {
        return chunk.getListOrEmpty("sections").getCompoundOrEmpty(0);
    }
}
