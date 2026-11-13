package io.github.billstark001.worldmirror.download;

import net.minecraft.world.level.ChunkPos;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDatabaseCountTest {
    @Test
    void upgradesAnExistingIndexWithoutCreatingOneForOtherWorlds(@TempDir Path root) throws Exception {
        Path existing = root.resolve("existing"), missing = root.resolve("missing");
        try (var connection = ChunkDatabase.openWritableConnection(existing);
                var sql = connection.createStatement()) {
            ChunkIndexStore.initialize(connection);
            sql.executeUpdate(
                    "DELETE FROM update_sources WHERE update_source IN ('replay_import', 'seed_generated')");
        }
        ChunkDatabase.upgradeExisting(existing);
        ChunkDatabase.upgradeExisting(missing);
        assertFalse(Files.exists(missing.resolve(MirrorFormat.DATABASE_FILE)));
        try (var connection = ChunkDatabase.openWritableConnection(existing)) {
            assertTrue(ChunkIndexStore.hasRegisteredSource(connection, ChunkIndexStore.REPLAY_IMPORT));
            assertTrue(ChunkIndexStore.hasRegisteredSource(connection, ChunkIndexStore.SEED_GENERATED));
        }
    }

    @Test
    void countsSavedChunksAcrossDimensionsForOneSource(@TempDir Path world) throws Exception {
        try (ChunkDatabase database = ChunkDatabase.open(world, "source:a")) {
            assertTrue(database.recordUpdates(
                    "minecraft:overworld", Map.of(new ChunkPos(1, 2), 100L), "world_mirror"));
            assertTrue(database.recordUpdates(
                    "minecraft:the_nether", Map.of(new ChunkPos(3, 4), 200L), "replay_import"));
        }
        try (ChunkDatabase database = ChunkDatabase.open(world, "source:b")) {
            assertTrue(database.recordUpdates(
                    "minecraft:overworld", Map.of(new ChunkPos(5, 6), 300L), "world_mirror"));
        }

        assertEquals(2, ChunkDatabase.countAllReadOnly(world, "source:a"));
        assertEquals(1, ChunkDatabase.countAllReadOnly(world, "source:b"));
        assertEquals(0, ChunkDatabase.countAllReadOnly(world, "source:c"));
    }

    @Test
    void snapshotsCommittedWalRowsAndKeepsSeedEvidenceOutOfMapQueries(@TempDir Path folder) throws Exception {
        Path world = folder.resolve("source"), stage = folder.resolve("stage");
        String source = "source:a", dimension = "minecraft:overworld";
        try (ChunkDatabase database = ChunkDatabase.open(world, source)) {
            database.recordSeedGeneratedIfMissing(dimension, Set.of(new ChunkPos(0, 0)));
            assertTrue(database.recordUpdates(dimension, Map.of(new ChunkPos(1, 0), 100L), "world_mirror"));
            assertTrue(ChunkDatabase.snapshot(world, stage));
            assertEquals(2, ChunkDatabase.countAllReadOnly(stage, source));
            assertEquals(1, ChunkDatabase.countVisibleReadOnly(stage, source));
            assertEquals(ChunkIndexStore.SEED_GENERATED,
                    ChunkDatabase.queryAllReadOnly(stage, source, dimension)
                            .stream()
                            .filter(row -> row.x() == 0)
                            .findFirst()
                            .orElseThrow()
                            .updateSource());
            assertEquals("world_mirror",
                    ChunkDatabase.queryVisibleReadOnly(stage, source, dimension).getFirst().updateSource());
            database.removeSeedGenerated(dimension, Set.of(new ChunkPos(0, 0)));
            assertEquals(1, ChunkDatabase.countAllReadOnly(world, source));
            assertEquals(2, ChunkDatabase.countAllReadOnly(stage, source));
        }
    }

    @Test
    void stagesAClosedFilteredIndexEvenWhenTheSaveTransactionPathIsLong(@TempDir Path folder)
            throws Exception {
        Path world = folder.resolve("source");
        Path stage = folder.resolve("a".repeat(110)).resolve("b".repeat(110)).resolve("stage");
        String source = "source:a", dimension = "minecraft:overworld";
        ChunkPos seed = new ChunkPos(0, 0), capture = new ChunkPos(1, 0);
        try (ChunkDatabase database = ChunkDatabase.open(world, source)) {
            database.recordSeedGeneratedIfMissing(dimension, Set.of(seed));
            assertTrue(database.recordUpdates(dimension, Map.of(capture, 100L), "world_mirror"));
            assertTrue(ChunkDatabase.stageWithoutSeedGenerated(
                    world, stage, source, Map.of(dimension, Set.of(seed, capture))));
            assertEquals(2, ChunkDatabase.countAllReadOnly(world, source));
        }
        Path verified = folder.resolve("verified/data");
        java.nio.file.Files.createDirectories(verified);
        java.nio.file.Files.copy(
                stage.resolve("data/world_mirror.sqlite"), verified.resolve("world_mirror.sqlite"));
        assertEquals(1, ChunkDatabase.countAllReadOnly(verified.getParent(), source));
        assertEquals("world_mirror",
                ChunkDatabase.queryAllReadOnly(verified.getParent(), source, dimension)
                        .getFirst()
                        .updateSource());
    }
}
