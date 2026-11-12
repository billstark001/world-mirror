package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStatusSnapshotRangeTest {
    @Test
    void sparseWorldScaleViewportVisitsOnlyIndexedRegions() {
        ChunkDatabase.ChunkRecord west = new ChunkDatabase.ChunkRecord(
                -1_500_000, 0, 1L, "world_mirror");
        ChunkDatabase.ChunkRecord east = new ChunkDatabase.ChunkRecord(
                1_500_000, 0, 2L, "world_mirror");
        ChunkStatusSnapshot snapshot = new ChunkStatusSnapshot(List.of(west, east), List.of(new ChunkPos(1_500_000, 0)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var buckets = snapshot.index().query(new ChunkBucketLayout(
                    new ChunkBounds(-1_875_000, 1_875_000, -1_875_000, 1_875_000), 1));
            assertEquals(2, buckets.size());
            assertTrue(buckets.get(ChunkBounds.key(east.x(), east.z())).conflict());
        });

        var visible = new ChunkBounds(-1_500_000, -1_500_000, 0, 0);
        var nearby = new ChunkMapProjection().get(snapshot, LiveChunkStatusSnapshot.EMPTY,
                visible, 0L, new ChunkBucketLayout(visible.expand(1), 1), true).boundaries();
        assertEquals(4, nearby.size());
    }

    @Test
    void xaeroViewportRejectsInvalidProjectionAndClampsExtremeZoom() {
        assertNull(ChunkMapView.overlayVisibleRange(100, 100, Double.NaN, 0, 1));
        assertNull(ChunkMapView.overlayVisibleRange(100, 100, 0, 0, 0));
        assertNull(ChunkMapView.overlayVisibleRange(0, 100, 0, 0, 1));
        ChunkBounds range = ChunkMapView.overlayVisibleRange(
                100, 100, 0, 0, Double.MIN_VALUE);
        assertNotNull(range);
        assertEquals(-1_875_000, range.minX());
        assertEquals(1_875_000, range.maxX());
        assertEquals(-1_875_000, range.minZ());
        assertEquals(1_875_000, range.maxZ());
    }

    @Test
    void queryResultsCannotInvalidateTheImmutableSnapshot() {
        var record = new ChunkDatabase.ChunkRecord(1, 2, 3L, "world_mirror");
        var snapshot = new ChunkStatusSnapshot(List.of(record), List.of());
        var buckets = snapshot.index().query(new ChunkBucketLayout(new ChunkBounds(0, 3, 0, 3), 1));
        assertThrows(UnsupportedOperationException.class, buckets::clear);
        assertEquals(record, snapshot.getRecord(1, 2));
    }

    @Test
    void coordinateLookupAndRegionIterationAgreeWhenInputContainsDuplicates() {
        var previous = new ChunkDatabase.ChunkRecord(1, 2, 3L, "world_mirror");
        var replacement = new ChunkDatabase.ChunkRecord(1, 2, 4L, "external");
        var snapshot = new ChunkStatusSnapshot(List.of(previous, replacement), List.of(new ChunkPos(1, 2), new ChunkPos(1, 2)));
        var buckets = snapshot.index().query(new ChunkBucketLayout(new ChunkBounds(0, 3, 0, 3), 1));
        assertEquals(1, snapshot.recordCount());
        assertEquals(1, buckets.size());
        var summary = buckets.get(ChunkBounds.key(1, 2));
        assertEquals(replacement, snapshot.getRecord(1, 2));
        assertEquals(replacement, summary.newest());
        assertTrue(summary.conflict());
    }
}
