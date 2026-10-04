package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldMetadataCaptureTimeTest {
    @Test
    void keepsOlderCompletionTimeUntilASavedCaptureIsNewer(@TempDir Path world) {
        WorldMetadata metadata = WorldMetadata.create("local:test", "singleplayer", "synchronized");
        metadata.lastSyncTime = 2_000L; // A value from a release that wrote export completion time.
        metadata.save(world);

        metadata.markSyncComplete(world, 1_500L);
        assertEquals(2_000L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);

        metadata.markSyncComplete(world, 2_500L);
        assertEquals(2_500L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);

        metadata.markSyncComplete(world, 0L); // An entity-only pass has no new chunk capture.
        assertEquals(2_500L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
    }
}
