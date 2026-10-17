package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkWorldOverlayTest {
    @Test
    void mergesAdjacentChunksAndUsesCurrentSessionColor() {
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(-2, 0, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(-1, 0, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(0, 0, 20, "world_mirror"),
                new ChunkDatabase.ChunkRecord(1, 0, 20, "replay_import")), Set.of(), 1);
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY,
                new ChunkMapView.ChunkRange(-2, 1, 0, 0), 64, 15);

        assertEquals(List.of(
                new ChunkWorldOverlay.OverlayChunk(-2, 0, 0, 1, 64, 0xFF2878E0),
                new ChunkWorldOverlay.OverlayChunk(0, 1, 0, 1, 64, 0xFF20E060)),
                overlay.chunks());
    }

    @Test
    void largeDenseViewStaysWithinMapOverlayBucketBudget() {
        ArrayList<ChunkDatabase.ChunkRecord> records = new ArrayList<>();
        for (int z = -128; z <= 128; z++) {
            for (int x = -128; x <= 128; x++) {
                records.add(new ChunkDatabase.ChunkRecord(x, z, 10, "world_mirror"));
            }
        }
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(records, Set.of(), 1);
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY,
                new ChunkMapView.ChunkRange(-128, 128, -128, 128), 0, 0);
        assertTrue(overlay.chunks().size() <= 4_096);
        assertTrue(overlay.chunks().size() < records.size() / 10);
    }

    @Test
    void bucketBudgetAccountsForUnalignedAndNegativeCoordinates() {
        int size = ChunkMapAggregation.bucketSizeFor(-3, 0, -3, 0, 4);
        long width = Math.floorDiv(0, size) - Math.floorDiv(-3, size) + 1L;
        assertTrue(width * width <= 4);
        assertTrue(ChunkMapAggregation.bucketSizeFor(-3, 0, -3, 0, 1) > 0);
    }

    @Test
    void innerGridIsCompleteWithoutDuplicateSharedEdges() {
        ArrayList<ChunkDatabase.ChunkRecord> records = new ArrayList<>();
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                records.add(new ChunkDatabase.ChunkRecord(x, z, 10, "world_mirror"));
            }
        }
        var overlay = ChunkWorldOverlay.build(new ChunkStatusSnapshot(records, Set.of(), 1),
                LiveChunkStatusSnapshot.EMPTY,
                new ChunkMapView.ChunkRange(0, 1, 0, 1), 64, 0, true, 60);

        assertEquals(1, overlay.chunks().size());
        assertEquals(12, overlay.edges().size());
        assertEquals(4, overlay.edges().stream()
                .filter(ChunkWorldOverlay.OverlayEdge::sameColorInterior).count());
        assertEquals(8, overlay.edges().stream()
                .filter(edge -> !edge.sameColorInterior()).count());

        var withoutGrid = ChunkWorldOverlay.build(new ChunkStatusSnapshot(records, Set.of(), 1),
                LiveChunkStatusSnapshot.EMPTY,
                new ChunkMapView.ChunkRange(0, 1, 0, 1), 64, 0, false, 60);
        assertEquals(8, withoutGrid.edges().size());
        assertTrue(withoutGrid.edges().stream().noneMatch(
                ChunkWorldOverlay.OverlayEdge::sameColorInterior));
    }

    @Test
    void statusBoundaryRemainsWhenInnerGridIsHidden() {
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(1, 0, 20, "world_mirror")), Set.of(), 1);
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY,
                new ChunkMapView.ChunkRange(0, 1, 0, 0), 64, 15, false, 60);

        assertTrue(overlay.edges().stream().anyMatch(edge ->
                edge.startChunkX() == 1 && edge.endChunkX() == 1
                        && !edge.sameColorInterior()));
        assertTrue(!overlay.showInnerGrid());
    }

    @Test
    void coarseTilesStayInsideRequestedMaximumRadius() {
        int radius = 16_384;
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(-radius, -radius, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(radius, radius, 10, "world_mirror")), Set.of(), 1);
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY,
                new ChunkMapView.ChunkRange(-radius, radius, -radius, radius), 64, 0);

        assertEquals(2, overlay.chunks().size());
        assertTrue(overlay.chunks().stream().allMatch(chunk ->
                chunk.startChunkX() >= -radius && chunk.endChunkX() <= radius + 1
                        && chunk.startChunkZ() >= -radius && chunk.endChunkZ() <= radius + 1));
        assertTrue(overlay.edges().stream().allMatch(edge ->
                edge.startChunkX() >= -radius && edge.endChunkX() <= radius + 1
                        && edge.startChunkZ() >= -radius && edge.endChunkZ() <= radius + 1));
    }
}
