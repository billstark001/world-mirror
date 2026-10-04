package io.github.billstark001.worldmirror.download;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDatabaseCountTest {
    @Test
    void countsSavedChunksAcrossDimensionsForOneSource(@TempDir Path world) throws Exception {
        try (ChunkDatabase database = ChunkDatabase.open(world, "source:a")) {
            assertTrue(database.recordUpdates("minecraft:overworld",
                    Map.of(new ChunkPos(1, 2), 100L), "world_mirror"));
            assertTrue(database.recordUpdates("minecraft:the_nether",
                    Map.of(new ChunkPos(3, 4), 200L), "replay_import"));
        }
        try (ChunkDatabase database = ChunkDatabase.open(world, "source:b")) {
            assertTrue(database.recordUpdates("minecraft:overworld",
                    Map.of(new ChunkPos(5, 6), 300L), "world_mirror"));
        }

        assertEquals(2, ChunkDatabase.countAllReadOnly(world, "source:a"));
        assertEquals(1, ChunkDatabase.countAllReadOnly(world, "source:b"));
        assertEquals(0, ChunkDatabase.countAllReadOnly(world, "source:c"));
    }
}
