package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.conflict.ManualResolver;
import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ChunkExporterPersistenceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
            var snapshot = ChunkListener.snapshotOf(Map.of(Level.OVERWORLD,
                    Map.of(pos, new ChunkListener.CapturedChunk(chunk, 100L, 1L))));

            var result = ChunkExporter.exportChunks(world, snapshot, Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L);

            assertTrue(result.chunkWritesSuccessful());
            assertEquals(1, result.totalWritten());
            Path region = ChunkExporter.dimensionDirForDimension(world, Level.OVERWORLD)
                    .resolve("region/r.1.-1.mca");
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
            var result = ChunkExporter.exportChunks(world, snapshot(pos, new CompoundTag()), Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L);

            assertFalse(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertTrue(result.settledRevisions().get(Level.OVERWORLD).isEmpty());
            assertTrue(result.unreadableChunks().get(Level.OVERWORLD).contains(pos));
            assertTrue(db.removeUnreadableUpdates("minecraft:overworld", result.unreadableChunks().get(Level.OVERWORLD)));

            var repaired = ChunkExporter.exportChunks(world, snapshot(pos, new CompoundTag()), Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L);
            assertTrue(repaired.chunkWritesSuccessful());
            assertEquals(1, repaired.totalWritten());
            assertTrue(db.recordUpdates("minecraft:overworld", Map.of(pos, 100L), "world_mirror"));
            assertFalse(db.shouldSkipUpdate("minecraft:overworld", pos, "world_mirror", 150L));
            assertTrue(Files.isRegularFile(ChunkExporter.regionDirForDimension(world, Level.OVERWORLD)
                    .resolve("r.0.0.mca")));
        }
    }

    @Test
    void manualConflictsContainTheFrozenInventoryOverlay(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
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
                    Map.of(Level.OVERWORLD, Map.of(new BlockPos(1, 64, 2), inventory)),
                    new ManualResolver(), db, -1L);

            assertTrue(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertEquals(1, result.settledRevisions().get(Level.OVERWORLD).size());
            Path conflict = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
            try (RegionFile reader = new RegionFile(new RegionStorageInfo("test", Level.OVERWORLD, "chunk"),
                    conflict, conflict.getParent(), false)) {
                CompoundTag stored = McaWriteSupport.readChunk(reader, pos);
                assertEquals(items, stored.getListOrEmpty("block_entities").getCompound(0).orElseThrow().get("Items"));
            }
        }
    }

    @Test
    void failedManualConflictPersistenceCannotSettleOrOverwriteTheCapture(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, new CompoundTag()));
        byte[] original = Files.readAllBytes(region);
        Files.writeString(world.resolve("conflict_chunks"), "blocked conflict root");
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, new CompoundTag()), Map.of(), Map.of(),
                    new ManualResolver(), db, -1L);

            assertFalse(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertTrue(result.settledRevisions().get(Level.OVERWORLD).isEmpty());
            assertArrayEquals(original, Files.readAllBytes(region));
        }
    }

    @Test
    void confirmedEmptyTerrainDoesNotRestoreBlockEntitiesFromDisk(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
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
            var result = ChunkExporter.exportChunks(world, snapshot, Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L);
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
        Path region = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
        CompoundTag original = new CompoundTag();
        original.putString("marker", "original");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, original));
        byte[] bytes = Files.readAllBytes(region);
        CompoundTag oversized = new CompoundTag();
        oversized.putString("text", "x".repeat(70_000));
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            var result = ChunkExporter.exportChunks(world, snapshot(pos, oversized), Map.of(), Map.of(),
                    new OverwriteResolver(), db, -1L);
            assertFalse(result.chunkWritesSuccessful());
            assertEquals(0, result.totalWritten());
            assertTrue(result.settledRevisions().get(Level.OVERWORLD).isEmpty());
            assertArrayEquals(bytes, Files.readAllBytes(region));
        }
    }

    private static ChunkListener.DirtySnapshot snapshot(ChunkPos pos, CompoundTag nbt) {
        return ChunkListener.snapshotOf(Map.of(Level.OVERWORLD,
                Map.of(pos, new ChunkListener.CapturedChunk(nbt, 100L, 1L))));
    }
}
