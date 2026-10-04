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
}
