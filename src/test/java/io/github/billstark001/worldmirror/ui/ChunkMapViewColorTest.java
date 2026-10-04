package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChunkMapViewColorTest {
    @Test
    void replayImportsUseTheSameCaptureAgeGradientAsWorldMirror() {
        long now = 40L * 24 * 60 * 60 * 1_000;
        for (long age : new long[] {0L, 60L * 60 * 1_000, 35L * 24 * 60 * 60 * 1_000}) {
            int worldMirror = ChunkMapView.computeColor(
                    new ChunkDatabase.ChunkRecord(1, 2, now - age, "world_mirror"), now);
            int replayImport = ChunkMapView.computeColor(
                    new ChunkDatabase.ChunkRecord(1, 2, now - age, "replay_import"), now);
            assertEquals(worldMirror, replayImport);
            assertNotEquals(0xFFFF9000, replayImport);
        }
    }
}
