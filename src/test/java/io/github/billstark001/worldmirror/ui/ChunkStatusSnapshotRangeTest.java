package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkStatusSnapshotRangeTest {
    @Test
    void sparseWorldScaleViewportVisitsOnlyIndexedRegions() {
        ChunkDatabase.ChunkRecord west = new ChunkDatabase.ChunkRecord(
                -1_500_000, 0, 1L, "world_mirror");
        ChunkDatabase.ChunkRecord east = new ChunkDatabase.ChunkRecord(
                1_500_000, 0, 2L, "world_mirror");
        ChunkStatusSnapshot snapshot = new ChunkStatusSnapshot(
                List.of(west, east), List.of(new ChunkPos(1_500_000, 0)), 1L);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<ChunkDatabase.ChunkRecord> records = new ArrayList<>();
            List<ChunkPos> conflicts = new ArrayList<>();
            snapshot.forEachRecordInRange(-1_875_000, 1_875_000,
                    -1_875_000, 1_875_000, records::add);
            snapshot.forEachConflictInRange(-1_875_000, 1_875_000,
                    -1_875_000, 1_875_000, conflicts::add);
            assertEquals(2, records.size());
            assertEquals(1, conflicts.size());
        });

        List<ChunkStatusSnapshot.BoundarySegment> nearby = new ArrayList<>();
        snapshot.forEachBoundaryInRange(-1_500_000, -1_500_000, 0, 0, nearby::add);
        assertEquals(4, nearby.size());
    }

    @Test
    void xaeroViewportRejectsInvalidProjectionAndClampsExtremeZoom() {
        assertNull(ChunkMapView.overlayVisibleRange(100, 100, Double.NaN, 0, 1));
        assertNull(ChunkMapView.overlayVisibleRange(100, 100, 0, 0, 0));
        assertNull(ChunkMapView.overlayVisibleRange(0, 100, 0, 0, 1));
        ChunkMapView.ChunkRange range = ChunkMapView.overlayVisibleRange(
                100, 100, 0, 0, Double.MIN_VALUE);
        assertNotNull(range);
        assertEquals(-1_875_000, range.minX());
        assertEquals(1_875_000, range.maxX());
        assertEquals(-1_875_000, range.minZ());
        assertEquals(1_875_000, range.maxZ());
    }

    @Test
    void recordsViewCannotInvalidateTheImmutableSnapshot() {
        var record = new ChunkDatabase.ChunkRecord(1, 2, 3L, "world_mirror");
        var snapshot = new ChunkStatusSnapshot(List.of(record), List.of(), 1L);

        assertThrows(UnsupportedOperationException.class, () -> snapshot.records().clear());
        assertEquals(record, snapshot.getRecord(1, 2));
    }

    @Test
    void coordinateLookupAndRegionIterationAgreeWhenInputContainsDuplicates() {
        var previous = new ChunkDatabase.ChunkRecord(1, 2, 3L, "world_mirror");
        var replacement = new ChunkDatabase.ChunkRecord(1, 2, 4L, "external");
        var snapshot = new ChunkStatusSnapshot(List.of(previous, replacement),
                List.of(new ChunkPos(1, 2), new ChunkPos(1, 2)), 1L);
        List<ChunkDatabase.ChunkRecord> visited = new ArrayList<>();
        List<ChunkPos> conflicts = new ArrayList<>();

        snapshot.forEachRecordInRange(0, 3, 0, 3, visited::add);
        snapshot.forEachConflictInRange(0, 3, 0, 3, conflicts::add);

        assertEquals(1, snapshot.recordCount());
        assertEquals(List.of(replacement), visited);
        assertEquals(List.of(new ChunkPos(1, 2)), conflicts);
    }
}
