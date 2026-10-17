package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot.State.CACHED;
import static io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot.State.PENDING_CAPTURE;
import static io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot.State.PENDING_WRITE;
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
                new ChunkWorldOverlay.OverlayChunk(0, 1, 0, 1, 64, 0xFF20E060),
                new ChunkWorldOverlay.OverlayChunk(1, 2, 0, 1, 64, 0xFF2878E0)),
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
    void sessionUsesOnlyItsThreeColorsForLiveStatesAndHistoricalCoverage() {
        var persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 20, "world_mirror"),
                new ChunkDatabase.ChunkRecord(7, 0, 20, "replay_import"),
                new ChunkDatabase.ChunkRecord(8, 0, 20, "external")), Set.of(), 1);
        var live = live(
                new LiveChunkStatusSnapshot.Record(0, 0, 0, PENDING_CAPTURE, true),
                new LiveChunkStatusSnapshot.Record(1, 0, 0, PENDING_CAPTURE, false),
                new LiveChunkStatusSnapshot.Record(2, 0, 20, PENDING_WRITE, true),
                new LiveChunkStatusSnapshot.Record(3, 0, 20, PENDING_WRITE, false),
                new LiveChunkStatusSnapshot.Record(4, 0, 20, CACHED, true),
                new LiveChunkStatusSnapshot.Record(5, 0, 20, CACHED, false),
                new LiveChunkStatusSnapshot.Record(6, 0, 10, CACHED, false));
        var overlay = ChunkWorldOverlay.build(persistent, live,
                new ChunkMapView.ChunkRange(0, 8, 0, 0), 96, 15, true, 60);

        assertEquals(0xFFFFD21F, colorAt(overlay, 0, 0));
        assertEquals(0xFFFFD21F, colorAt(overlay, 1, 0));
        for (int x = 2; x <= 5; x++) assertEquals(0xFF20E060, colorAt(overlay, x, 0));
        assertEquals(0xFF2878E0, colorAt(overlay, 6, 0));
        assertEquals(0xFF2878E0, colorAt(overlay, 7, 0));
        assertEquals(0, colorAt(overlay, 8, 0));
        assertTrue(overlay.chunks().stream().allMatch(chunk ->
                Set.of(0xFFFFD21F, 0xFF20E060, 0xFF2878E0).contains(chunk.color())));
    }

    @Test
    void withoutAnActiveSessionSavedAndCachedCoverageIsHistoricalBlue() {
        var persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 20, "world_mirror"),
                new ChunkDatabase.ChunkRecord(1, 0, 30, "replay_import")), Set.of(), 1);
        var overlay = ChunkWorldOverlay.build(persistent,
                live(new LiveChunkStatusSnapshot.Record(2, 0, 40, CACHED, false)),
                new ChunkMapView.ChunkRange(0, 2, 0, 0), 96, 0, true, 60);

        assertEquals(List.of(new ChunkWorldOverlay.OverlayChunk(0, 3, 0, 1, 96, 0xFF2878E0)),
                overlay.chunks());
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

    private static LiveChunkStatusSnapshot live(LiveChunkStatusSnapshot.Record... records) {
        var indexed = new HashMap<Long, LiveChunkStatusSnapshot.Record>();
        for (var record : records) indexed.put(ChunkStatusSnapshot.chunkKey(record.x(), record.z()), record);
        return new LiveChunkStatusSnapshot(indexed);
    }

    private static int colorAt(ChunkWorldOverlay.OverlaySnapshot overlay, int x, int z) {
        return overlay.chunks().stream()
                .filter(chunk -> x >= chunk.startChunkX() && x < chunk.endChunkX()
                        && z >= chunk.startChunkZ() && z < chunk.endChunkZ())
                .mapToInt(ChunkWorldOverlay.OverlayChunk::color).findFirst().orElse(0);
    }
}
