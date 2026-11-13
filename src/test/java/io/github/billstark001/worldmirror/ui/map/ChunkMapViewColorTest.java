package io.github.billstark001.worldmirror.ui.map;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMapViewColorTest {
    @Test
    void denseSavedFillsBatchEqualColorsWithoutDrawingOutsideTheViewport() {
        var records = new ArrayList<ChunkDatabase.ChunkRecord>();
        for (int x = -10; x <= 10; x++) records.add(new ChunkDatabase.ChunkRecord(x, 0, 100L, "world_mirror"));
        var snapshot = new ChunkStatusSnapshot(records, java.util.Set.of());
        var projection = new ChunkMapProjection();
        var viewport = new ChunkMapView.BuiltInViewport(100, 100, 0, 0, 8, true);
        var savedFills = new ArrayList<java.util.List<Integer>>();
        ChunkMapView.renderBuiltIn((left, top, right, bottom, color) -> {
            assertTrue(left >= 0 && top >= 0 && right <= viewport.width() && bottom <= viewport.height());
            if (color == 0x7F00C800) savedFills.add(java.util.List.of(left, top, right, bottom));
        }, projection, snapshot, LiveChunkStatusSnapshot.EMPTY, viewport, 200L, 1);

        assertTrue(projection.currentFrame().dense());
        assertEquals(java.util.List.of(java.util.List.of(0, 50, 100, 58)), savedFills);
    }

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void transparentMapsKeepSavedLiveAndConflictCompositingOrder() {
        var saved = new ChunkStatusSnapshot(java.util.List.of(new ChunkDatabase.ChunkRecord(0, 0, 100, "world_mirror")), java.util.Set.of(new net.minecraft.world.level.ChunkPos(0, 0)));
        var live = new LiveChunkStatusSnapshot(java.util.Map.of(0L,
                new LiveChunkStatusSnapshot.Record(0, 0, 0, LiveChunkStatusSnapshot.State.PENDING_CAPTURE, true)), Map.of());
        for (int sparseThreshold : new int[] {1, 16}) {
            var colors = new java.util.ArrayList<Integer>();
            ChunkMapView.renderBuiltIn((x1, z1, x2, z2, color) -> colors.add(color), new ChunkMapProjection(),
                    saved, live, new ChunkMapView.BuiltInViewport(100, 100, 0, 0, 6, true), 200, sparseThreshold);
            assertBefore(colors, 0x7F00C800, 0x7FD8DC30, ExportStatusMarkers.CONFLICT);
        }
        var colors = new java.util.ArrayList<Integer>();
        ChunkMapView.renderOverlay((x1, z1, x2, z2, color) -> colors.add(color), new ChunkMapProjection(),
                coordinate -> 50 + (int) coordinate, coordinate -> 50 + (int) coordinate,
                saved, live, 100, 100, 0, 0, 1, 200, 4096);
        org.junit.jupiter.api.Assertions.assertTrue(colors.indexOf(0x5F00C800) >= 0);
        org.junit.jupiter.api.Assertions.assertTrue(colors.indexOf(0x5F00C800) < colors.indexOf(0x5FD8DC30));
        org.junit.jupiter.api.Assertions.assertFalse(colors.contains(ExportStatusMarkers.CONFLICT));
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
