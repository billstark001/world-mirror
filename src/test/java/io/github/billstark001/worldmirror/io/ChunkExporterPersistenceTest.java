package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.conflict.IgnoreResolver;
import io.github.billstark001.worldmirror.conflict.ManualResolver;
import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.SeedTerrainBlending;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkExporterPersistenceTest {
    @Test
    void writesTerrainIntoAZeroByteRegion(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.createFile(region);
        CompoundTag incoming = new CompoundTag();
        incoming.putString("marker", "captured");
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, incoming), Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L, ExportProgress.NONE);
            assertTrue(result.chunkWritesSuccessful());
            assertEquals(1, result.totalWritten());
            assertEquals(incoming, AnvilReader.readChunk(region, pos));
        }
    }

    @Test
    void progressObserverFailureCannotAbortNativePersistence(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:observer")) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, new CompoundTag()), Map.of(),
                    Map.of(), new OverwriteResolver(), db, -1L,
                    (part, dimension, chunks) -> { throw new IllegalStateException("observer only"); });
            assertTrue(result.chunkWritesSuccessful());
            assertEquals(1, result.totalWritten());
        }
    }
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void successiveTerrainWritesRemainDurableWhenExistingEntityDataCannotBeIndexed(@TempDir Path world)
            throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path entityFile =
                WorldSaveLayout.dimensionDirectory(world, Level.OVERWORLD).resolve("entities/r.0.0.mca");
        Files.createDirectories(entityFile.getParent());
        byte[] corrupt = {1, 2, 3, 4, 5};
        Files.write(entityFile, corrupt);
        UUID uuid = new UUID(88L, 1L);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        entity.putIntArray("UUID", net.minecraft.core.UUIDUtil.uuidToIntArray(uuid));
        var update = new EntityTracker.ChunkUpdate(1L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, new EntityTracker.EntityRecord(uuid, Set.of(uuid), entity)), Set.of());
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:partial")) {
            for (int revision = 1; revision <= 2; revision++) {
                CompoundTag chunk = new CompoundTag();
                chunk.putInt("marker", revision);
                var terrain = ChunkListener.snapshotOf(Map.of(Level.OVERWORLD,
                        Map.of(pos,
                                new ChunkListener.CapturedChunk(chunk, revision * 100L, revision, false))));
                var result = ChunkExporter.exportChunks(world, terrain,
                        Map.of(Level.OVERWORLD, Map.of(pos, update)), Map.of(), new OverwriteResolver(), db,
                        -1L, io.github.billstark001.worldmirror.io.ExportProgress.NONE);
                assertTrue(result.chunkWritesSuccessful());
                assertFalse(result.entityWritesSuccessful());
                assertEquals(1, result.totalWritten());
                assertTrue(db.recordUpdates(
                        "minecraft:overworld", Map.of(pos, revision * 100L), "world_mirror"));
                assertArrayEquals(corrupt, Files.readAllBytes(entityFile));
            }
            Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
            try (RegionFile reader = new RegionFile(new RegionStorageInfo("test", Level.OVERWORLD, "chunk"),
                         region, region.getParent(), false)) {
                assertEquals(2, McaWriteSupport.readChunk(reader, pos).getIntOr("marker", 0));
            }
            assertTrue(db.shouldSkipUpdate("minecraft:overworld", pos, "world_mirror", 200L));
        }
    }

    @Test
    void regionFileNamesAlwaysUseAsciiCoordinates(@TempDir Path world) throws Exception {
        Locale previous = Locale.getDefault();
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:locale")) {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            ChunkPos pos = new ChunkPos(33, -1);
            CompoundTag chunk = new CompoundTag();
            chunk.putInt("xPos", 33);
            chunk.putInt("zPos", -1);
            chunk.putString("marker", "captured");
            var snapshot = ChunkListener.snapshotOf(Map.of(
                    Level.OVERWORLD, Map.of(pos, new ChunkListener.CapturedChunk(chunk, 100L, 1L, false))));

            var result =
                    ChunkExporter.exportChunks(world, snapshot, Map.of(), Map.of(), new OverwriteResolver(),
                            db, -1L, io.github.billstark001.worldmirror.io.ExportProgress.NONE);

            assertTrue(result.chunkWritesSuccessful());
            assertEquals(1, result.totalWritten());
            Path region =
                    WorldSaveLayout.dimensionDirectory(world, Level.OVERWORLD).resolve("region/r.1.-1.mca");
            assertTrue(Files.isRegularFile(region));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void aDatabaseRowCannotSettleACaptureWhoseRegionFileIsMissing(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            assertTrue(db.recordUpdates("minecraft:overworld", Map.of(pos, 200L), "world_mirror"));
            var result = ChunkExporter.exportChunks(world, snapshot(pos, new CompoundTag()), Map.of(),
                    Map.of(), new OverwriteResolver(), db, -1L,
                    io.github.billstark001.worldmirror.io.ExportProgress.NONE);

            assertFalse(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertTrue(result.settledRevisions().get(Level.OVERWORLD).isEmpty());
            assertTrue(result.unreadableChunks().get(Level.OVERWORLD).contains(pos));
            assertTrue(db.removeUnreadableUpdates(
                    "minecraft:overworld", result.unreadableChunks().get(Level.OVERWORLD)));

            var repaired = ChunkExporter.exportChunks(world, snapshot(pos, new CompoundTag()), Map.of(),
                    Map.of(), new OverwriteResolver(), db, -1L,
                    io.github.billstark001.worldmirror.io.ExportProgress.NONE);
            assertTrue(repaired.chunkWritesSuccessful());
            assertEquals(1, repaired.totalWritten());
            assertTrue(db.recordUpdates("minecraft:overworld", Map.of(pos, 100L), "world_mirror"));
            assertFalse(db.shouldSkipUpdate("minecraft:overworld", pos, "world_mirror", 150L));
            assertTrue(Files.isRegularFile(
                    WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca")));
        }
    }

    @Test
    void manualConflictsContainTheFrozenInventoryOverlay(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        CompoundTag original = new CompoundTag();
        original.putString("marker", "local");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, original));
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 1);
        chest.putInt("y", 64);
        chest.putInt("z", 2);
        CompoundTag incoming = new CompoundTag();
        ListTag blockEntities = new ListTag();
        blockEntities.add(chest);
        incoming.put("block_entities", blockEntities);
        CompoundTag inventory = new CompoundTag();
        inventory.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:diamond");
        items.add(item);
        inventory.put("Items", items);
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, incoming), Map.of(),
                    Map.of(Level.OVERWORLD, Map.of(new BlockPos(1, 64, 2), inventory)), new ManualResolver(),
                    db, -1L, io.github.billstark001.worldmirror.io.ExportProgress.NONE);

            assertTrue(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertEquals(1, result.settledRevisions().get(Level.OVERWORLD).size());
            Path conflict = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
            try (RegionFile reader = new RegionFile(new RegionStorageInfo("test", Level.OVERWORLD, "chunk"),
                         conflict, conflict.getParent(), false)) {
                CompoundTag stored = McaWriteSupport.readChunk(reader, pos);
                assertEquals(items,
                        stored.getListOrEmpty("block_entities").getCompound(0).orElseThrow().get("Items"));
            }
        }
    }

    @Test
    void failedManualConflictPersistenceCannotSettleOrOverwriteTheCapture(@TempDir Path world)
            throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, new CompoundTag()));
        byte[] original = Files.readAllBytes(region);
        Files.writeString(world.resolve("conflict_chunks"), "blocked conflict root");
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, new CompoundTag()), Map.of(),
                    Map.of(), new ManualResolver(), db, -1L,
                    io.github.billstark001.worldmirror.io.ExportProgress.NONE);

            assertFalse(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertTrue(result.settledRevisions().get(Level.OVERWORLD).isEmpty());
            assertArrayEquals(original, Files.readAllBytes(region));
        }
    }

    @Test
    void confirmedEmptyTerrainDoesNotRestoreBlockEntitiesFromDisk(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        CompoundTag old = new CompoundTag();
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 1);
        chest.putInt("y", 64);
        chest.putInt("z", 2);
        ListTag blockEntities = new ListTag();
        blockEntities.add(chest);
        old.put("block_entities", blockEntities);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, old));
        var snapshot = ChunkListener.snapshotOf(Map.of(Level.OVERWORLD,
                Map.of(pos, new ChunkListener.CapturedChunk(new CompoundTag(), 100L, 1L, true))));
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            var result =
                    ChunkExporter.exportChunks(world, snapshot, Map.of(), Map.of(), new OverwriteResolver(),
                            db, -1L, io.github.billstark001.worldmirror.io.ExportProgress.NONE);
            assertTrue(result.chunkWritesSuccessful());
            try (RegionFile reader = new RegionFile(new RegionStorageInfo("test", Level.OVERWORLD, "chunk"),
                         region, region.getParent(), false)) {
                assertTrue(McaWriteSupport.readChunk(reader, pos).getListOrEmpty("block_entities").isEmpty());
            }
        }
    }

    @Test
    void oversizedStringsCannotBeSilentlyDroppedAndAcknowledged(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        CompoundTag original = new CompoundTag();
        original.putString("marker", "original");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, original));
        byte[] bytes = Files.readAllBytes(region);
        CompoundTag oversized = new CompoundTag();
        oversized.putString("text", "x".repeat(70_000));
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, oversized), Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L,
                    io.github.billstark001.worldmirror.io.ExportProgress.NONE);
            assertFalse(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertTrue(result.settledRevisions().get(Level.OVERWORLD).isEmpty());
            assertArrayEquals(bytes, Files.readAllBytes(region));
        }
    }

    private static ChunkListener.DirtySnapshot snapshot(ChunkPos pos, CompoundTag nbt) {
        return ChunkListener.snapshotOf(
                Map.of(Level.OVERWORLD, Map.of(pos, new ChunkListener.CapturedChunk(nbt, 100L, 1L, false))));
    }

    @Test
    void replacesRetainedSeedTerrainBlendingAndRecoversIndexWithoutRewritingIt(@TempDir Path world)
            throws Exception {
        var metadata = WorldMetadata.create("mirror:retained-seed", WorldGraph.Kind.MIRROR,
                "remote:retained-seed", WorldGraph.Role.SYNC, "server:retained-seed");
        assertTrue(metadata.save(world));
        var pos = new ChunkPos(0, 0);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var original = generatedTerrain();
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, original));

        var incoming = new CompoundTag();
        incoming.putString("marker", "captured");
        var capture = snapshot(pos, incoming);
        try (var db = ChunkDatabase.open(world, metadata.sourceId)) {
            String dimension = "minecraft:overworld";
            db.recordSeedGeneratedIfMissing(dimension, Set.of(pos));
            var first = ChunkExporter.exportChunks(
                    world, capture, Map.of(), Map.of(), new IgnoreResolver(), db, -1L, ExportProgress.NONE);
            assertTrue(first.chunkWritesSuccessful());
            assertEquals(1, first.totalWritten());
            assertEquals(ChunkIndexStore.SEED_GENERATED, db.queryChunk(dimension, pos).updateSource());

            byte[] replaced = Files.readAllBytes(region);
            var retry = ChunkExporter.exportChunks(
                    world, capture, Map.of(), Map.of(), new IgnoreResolver(), db, -1L, ExportProgress.NONE);
            assertTrue(retry.chunkWritesSuccessful());
            assertEquals(0, retry.totalWritten());
            assertEquals(1L, retry.settledRevisions().get(Level.OVERWORLD).get(pos));
            assertTrue(retry.recoveredIndex());
            assertEquals(100L, retry.recoveredCaptureTime());
            assertEquals("world_mirror", db.queryChunk(dimension, pos).updateSource());
            assertEquals(100L, db.queryChunk(dimension, pos).updateTime());
            assertArrayEquals(replaced, Files.readAllBytes(region));
        }
    }

    @Test
    void replacesOnlySeedTerrainWithoutEntitiesOrPoiInTheSameRegion(@TempDir Path world) throws Exception {
        var metadata = WorldMetadata.create("mirror:auxiliary", WorldGraph.Kind.MIRROR, "remote:auxiliary",
                WorldGraph.Role.SYNC, "server:auxiliary");
        assertTrue(metadata.save(world));
        var empty = new ChunkPos(4, 0);
        var occupied = new ChunkPos(5, 0);
        var poi = new ChunkPos(6, 0);
        var malformedPoi = new ChunkPos(7, 0);
        Path dimension = WorldSaveLayout.dimensionDirectory(world, Level.OVERWORLD);
        Path region = dimension.resolve("region/r.0.0.mca");
        var generated = generatedTerrain();
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(empty, generated, occupied, generated, poi, generated, malformedPoi, generated));

        var entity = new CompoundTag();
        var entities = new ListTag();
        entities.add(new CompoundTag());
        entity.put("Entities", entities);
        McaWriteSupport.writeChunks(
                dimension.resolve("entities/r.0.0.mca"), Level.OVERWORLD, Map.of(occupied, entity));
        McaWriteSupport.writeChunks(dimension.resolve("poi/r.0.0.mca"), Level.OVERWORLD,
                Map.of(poi, poiEntry(true), malformedPoi, poiEntry(false)));

        var writes = new java.util.HashMap<ChunkPos, ChunkListener.CapturedChunk>();
        for (ChunkPos pos : Set.of(empty, occupied, poi, malformedPoi)) {
            var nbt = new CompoundTag();
            nbt.putString("marker", "captured");
            writes.put(pos, new ChunkListener.CapturedChunk(nbt, 100L, 1L, false));
        }
        try (var db = ChunkDatabase.open(world, metadata.sourceId)) {
            db.recordSeedGeneratedIfMissing("minecraft:overworld", writes.keySet());
            var capture = ChunkListener.snapshotOf(Map.of(Level.OVERWORLD, writes));
            var result = ChunkExporter.exportChunks(
                    world, capture, Map.of(), Map.of(), new IgnoreResolver(), db, -1L, ExportProgress.NONE);
            assertTrue(result.chunkWritesSuccessful());
            assertEquals(Set.of(empty), result.written().get(Level.OVERWORLD).keySet());
            assertEquals(Set.of(occupied, poi, malformedPoi),
                    result.settledRevisions().get(Level.OVERWORLD).keySet());
        }
        assertEquals("captured", AnvilReader.readChunk(region, empty).getStringOr("marker", ""));
        for (ChunkPos pos : Set.of(occupied, poi, malformedPoi))
            assertEquals(generated, AnvilReader.readChunk(region, pos));
    }

    private static CompoundTag poiEntry(boolean withRecords) {
        var section = new CompoundTag();
        if (withRecords) {
            var records = new ListTag();
            records.add(new CompoundTag());
            section.put("Records", records);
        }
        var sections = new CompoundTag();
        sections.put("0", section);
        var entry = new CompoundTag();
        entry.put("Sections", sections);
        return entry;
    }

    private static CompoundTag generatedTerrain() {
        var original = new CompoundTag();
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        original.putInt("DataVersion", version);
        original.put("sections", new ListTag());
        var evidence = SeedChunkProvenance.baseline(original, UUID.randomUUID(), version);
        var marker = new CompoundTag();
        marker.putInt(SeedTerrainDigest.SEED_SCHEMA, SeedTerrainDigest.SEED_SCHEMA_VERSION);
        marker.putString(SeedTerrainDigest.EPOCH, evidence.epoch().toString());
        marker.putInt(SeedTerrainDigest.DATA_VERSION, version);
        marker.putString(SeedTerrainDigest.BASELINE, evidence.baseline());
        marker.putBoolean(SeedTerrainDigest.SEED_EDITED, false);
        var attachments = new CompoundTag();
        attachments.put(SeedTerrainDigest.ATTACHMENT, marker);
        original.put(SeedTerrainDigest.ATTACHMENTS, attachments);
        return original;
    }

    @Test
    void refreshesAnOwnedBlendMarkerWhenACaptureFillsAnExistingBorder(@TempDir Path world) throws Exception {
        UUID epoch = UUID.randomUUID();
        var metadata = WorldMetadata.create("mirror:blend-recapture", WorldGraph.Kind.MIRROR,
                "remote:blend-recapture", WorldGraph.Role.SYNC, "server:blend-recapture");
        metadata.markSeededGeneration(
                SharedConstants.getCurrentVersion().dataVersion().version(), "a".repeat(64), epoch, true);
        assertTrue(metadata.save(world));
        var pos = new ChunkPos(5, 5);
        var initial = new CompoundTag();
        initial.putString("Status", "minecraft:full");
        SeedTerrainBlending.markBlend(initial, epoch);
        initial.getCompoundOrEmpty(SeedTerrainDigest.BLENDING_DATA).put("heights", new ListTag());
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var neighbors = new java.util.HashMap<ChunkPos, CompoundTag>();
        for (int x = 4; x <= 6; x++)
            for (int z = 4; z <= 6; z++) neighbors.put(new ChunkPos(x, z), new CompoundTag());
        neighbors.put(pos, initial);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, neighbors);

        var incoming = blendTerrain("minecraft:stone");
        incoming.putString("marker", "recaptured");
        try (var db = ChunkDatabase.open(world, metadata.sourceId)) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, incoming), Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L, ExportProgress.NONE);
            assertEquals(1, result.totalWritten());
        }
        var recaptured = AnvilReader.readChunk(region, pos);
        assertTrue(SeedTerrainBlending.ownsBlend(recaptured, epoch));
        assertEquals("recaptured", recaptured.getStringOr("marker", ""));
        assertFalse(recaptured.getCompoundOrEmpty(SeedTerrainDigest.BLENDING_DATA).contains("heights"));
    }

    @Test
    void capturesEmptyBoundaryWithoutGivingItAVanillaBlendMarker(@TempDir Path world) throws Exception {
        UUID epoch = UUID.randomUUID();
        var metadata = WorldMetadata.create("mirror:blend-empty", WorldGraph.Kind.MIRROR,
                "remote:blend-empty", WorldGraph.Role.SYNC, "server:blend-empty");
        metadata.markSeededGeneration(
                SharedConstants.getCurrentVersion().dataVersion().version(), "a".repeat(64), epoch, true);
        assertTrue(metadata.save(world));
        var pos = new ChunkPos(5, 5);
        try (var db = ChunkDatabase.open(world, metadata.sourceId)) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, blendTerrain("minecraft:air")),
                    Map.of(), Map.of(), new OverwriteResolver(), db, -1L, ExportProgress.NONE);
            assertEquals(1, result.totalWritten());
        }
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var captured = AnvilReader.readChunk(region, pos);
        assertFalse(captured.contains(SeedTerrainDigest.BLENDING_DATA));
        assertFalse(SeedTerrainBlending.ownsBlend(captured, epoch));
    }

    private static CompoundTag blendTerrain(String blockId) {
        var block = new CompoundTag();
        block.putString("id", blockId);
        var palette = new ListTag();
        palette.add(block);
        var states = new CompoundTag();
        states.put("palette", palette);
        var section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.put("block_states", states);
        var sections = new ListTag();
        sections.add(section);
        var result = new CompoundTag();
        result.putString("Status", "minecraft:full");
        result.put("sections", sections);
        return result;
    }
}
