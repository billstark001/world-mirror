package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorldMetadataPersistenceTest {
    @Test
    @SuppressWarnings("deprecation")
    void failedLegacyMigrationKeepsTheOnlyCopyOfTheTimestamps(@TempDir Path world) throws Exception {
        WorldMetadata metadata = WorldMetadata.create("server:test", "server", "synchronized");
        metadata.chunkUpdateTimes = Map.of("minecraft:overworld|1,2", 100L);
        metadata.save(world);
        String original = Files.readString(world.resolve(WorldMetadata.FILE_NAME));
        ChunkDatabase closedDatabase = ChunkDatabase.open(world, "server:test");
        closedDatabase.close();

        metadata.migrateAndCleanChunkTimes(closedDatabase, world);

        assertNotNull(metadata.chunkUpdateTimes);
        assertEquals(original, Files.readString(world.resolve(WorldMetadata.FILE_NAME)));
    }
}
