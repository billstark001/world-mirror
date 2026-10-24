package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChunkMapViewColorTest {
    @Test
    void transparentMapsKeepSavedLiveAndConflictCompositingOrder() {
        var saved = new ChunkStatusSnapshot(java.util.List.of(new ChunkDatabase.ChunkRecord(0, 0, 100, "world_mirror")), java.util.Set.of(new net.minecraft.world.level.ChunkPos(0, 0)));
        var live = new LiveChunkStatusSnapshot(java.util.Map.of(0L,
                new LiveChunkStatusSnapshot.Record(0, 0, 0, LiveChunkStatusSnapshot.State.PENDING_CAPTURE, true)));
        for (int sparseThreshold : new int[] {1, 16}) {
            var colors = new java.util.ArrayList<Integer>();
            ChunkMapView.renderBuiltIn((x1, z1, x2, z2, color) -> colors.add(color), new ChunkMapProjection(),
                    saved, live, new ChunkMapView.BuiltInViewport(100, 100, 0, 0, 6, true), 200, sparseThreshold);
            assertBefore(colors, 0x7F00C800, 0x7FD8DC30, 0xFFFF3030);
        }
        var colors = new java.util.ArrayList<Integer>();
        ChunkMapView.renderOverlay((x1, z1, x2, z2, color) -> colors.add(color), new ChunkMapProjection(),
                coordinate -> 50 + (int) coordinate, coordinate -> 50 + (int) coordinate,
                saved, live, 100, 100, 0, 0, 1, 200, 4096);
        assertBefore(colors, 0x5F00C800, 0x5FD8DC30, 0x66FF3030);
    }

    private static void assertBefore(java.util.List<Integer> colors, int saved, int live, int conflict) {
        org.junit.jupiter.api.Assertions.assertTrue(colors.indexOf(saved) >= 0);
        org.junit.jupiter.api.Assertions.assertTrue(colors.indexOf(saved) < colors.indexOf(live));
        org.junit.jupiter.api.Assertions.assertTrue(colors.indexOf(live) < colors.indexOf(conflict));
    }

    @Test
    void replayImportsUseTheSameCaptureAgeGradientAsWorldMirror() {
        long now = 40L * 24 * 60 * 60 * 1_000;
        for (long age : new long[] {0L, 60L * 60 * 1_000, 35L * 24 * 60 * 60 * 1_000}) {
            int worldMirror = ChunkMapStyle.saved(
                    new ChunkDatabase.ChunkRecord(1, 2, now - age, "world_mirror"), now);
            int replayImport = ChunkMapStyle.saved(
                    new ChunkDatabase.ChunkRecord(1, 2, now - age, "replay_import"), now);
            assertEquals(worldMirror, replayImport);
            assertNotEquals(0xFFFF9000, replayImport);
        }
    }
}
