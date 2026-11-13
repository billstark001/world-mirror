package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMapProjectionTest {
    private final ChunkMapProjection projection = new ChunkMapProjection();
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void largeMapPansReuseInteriorBucketsAndRefreshAgeWithoutRescanningHistory() {
        var records = new ArrayList<ChunkDatabase.ChunkRecord>();
        for (int x = 0; x < 320; x++) {
            for (int z = 0; z < 320; z++) records.add(new ChunkDatabase.ChunkRecord(x, z, 1L, "world_mirror"));
        }
        var snapshot = new ChunkStatusSnapshot(records, Set.of());
        var range = new ChunkBounds(0, 319, 0, 319);
        var layout = ChunkBucketLayout.withinBudget(range, 4096);
        projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 1_000L, layout, false);
        long first = snapshot.index().visits();
        projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 2_001L, layout, false);
        assertEquals(first, snapshot.index().visits(), "age refresh reuses complete bucket summaries");
        var clipped = new ChunkBounds(1, 318, 1, 318);
        projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, clipped, 2_002L, ChunkBucketLayout.withinBudget(clipped, 4096), false);
        assertTrue(snapshot.index().visits() - first < 10_000,
                "panning inspects clipped edges, not the 102400-record interior again");
        long beforeNewBuckets = snapshot.index().visits();
        var shifted = new ChunkBounds(5, 324, 0, 319);
        var panned = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, shifted, 2_003L,
                ChunkBucketLayout.withinBudget(shifted, 4096), false);
        assertEquals(5, panned.bucketSize());
        assertTrue(snapshot.index().visits() - beforeNewBuckets < 10_000,
                "crossing a bucket boundary queries the new column, not every cached bucket");
        assertTrue(snapshot.matchesData(records, Set.of()));
    }
    @Test
    void panningInsideTheSameBucketsRefreshesVisibleRecordsAndConflicts() {
        var snapshot = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 1L, "world_mirror"),
                new ChunkDatabase.ChunkRecord(9, 0, 2L, "external")), List.of(new ChunkPos(9, 0)));

        var initial = new ChunkBounds(0, 8, 0, 8);
        var shifted = new ChunkBounds(1, 9, 0, 8);
        var first = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, initial, 100L,
                ChunkBucketLayout.withinBudget(initial, 4), false);
        var panned = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, shifted, 101L,
                ChunkBucketLayout.withinBudget(shifted, 4), false);

        assertEquals(first.bucketSize(), panned.bucketSize());
        assertFalse(first.runs().stream().anyMatch(ChunkTileGeometry.Run::conflict));
        assertTrue(panned.runs().stream().anyMatch(ChunkTileGeometry.Run::conflict));
        assertEquals(1, panned.runs().size());
        assertEquals(0xFFFF9000, panned.runs().getFirst().color());
    }

    @Test
    void reusesAnUnchangedViewportOnlyUntilTheAgeRefresh() {
        var snapshot = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 1L, "world_mirror")), List.of());
        var range = new ChunkBounds(0, 1, 0, 1);
        var layout = new ChunkBucketLayout(range, 1);
        var first = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 1_000L, layout, false);

        assertSame(first, projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 600_000L, layout, false));
        long visits = snapshot.index().visits();
        assertSame(first, projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 600_001L, layout, false));
        var refreshed = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 601_001L, layout, false);
        assertNotSame(first, refreshed);
        assertSame(refreshed, projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 602_000L, layout, false));
        assertEquals(visits, snapshot.index().visits());
        assertNotSame(refreshed, projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, 500L, layout, false));
    }

    @Test
    void refreshesFillColorsWithoutRebuildingUnchangedBoundariesOrStatusMarks() {
        var saved = new ChunkStatusSnapshot(List.of(new ChunkDatabase.ChunkRecord(0, 0, 0, "world_mirror")),
                List.of(new ChunkPos(1, 0)));
        var live = new LiveChunkStatusSnapshot(Map.of(0L,
                new LiveChunkStatusSnapshot.Record(0, 0, 0, LiveChunkStatusSnapshot.State.PENDING_CAPTURE, true)), Map.of());
        var range = new ChunkBounds(0, 1, 0, 1);
        var layout = new ChunkBucketLayout(range.expand(1), 1);
        var first = projection.get(saved, live, range, 600_000L, layout, true);

        var refreshed = projection.get(saved, live, range, 601_000L, layout, true);
        var fresh = new ChunkMapProjection().get(saved, live, range, 601_000L, layout, true);

        assertFalse(first.runs().equals(refreshed.runs()));
        assertSame(first.boundaries(), refreshed.boundaries());
        assertSame(first.live(), refreshed.live());
        assertSame(first.marks(), refreshed.marks());
        assertEquals(fresh, refreshed);
    }

    @Test
    void dataReuseDoesNotFreezeBoundaryAgeColors() {
        long now = System.currentTimeMillis();
        var snapshot = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, now, "world_mirror")), Set.of());
        var range = new ChunkBounds(0, 0, 0, 0);
        var layout = new ChunkBucketLayout(range.expand(1), 1);
        var fresh = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, now, layout, true);
        var old = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, now + 31L * 24 * 60 * 60 * 1_000, layout, true);
        assertFalse(fresh.boundaries().equals(old.boundaries()));
        assertTrue(snapshot.matchesData(List.of(new ChunkDatabase.ChunkRecord(0, 0, now, "world_mirror")), Set.of()));
    }

    @Test
    void thinAndExtremeViewportsHaveBoundedSizingCost() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            int thin = ChunkBucketLayout.sizeFor(0, 2_000_000_000, 0, 0, 1);
            assertTrue(thin > 0);
            assertTrue(bucketCount(0, 2_000_000_000, thin) <= 1);

            int extreme = ChunkBucketLayout.sizeFor(
                    Integer.MIN_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, 4);
            assertTrue(extreme > 0);
        });
    }

    @Test
    void randomWorldViewportsRespectTheCellBudget() {
        Random random = new Random(0x57A71CL);
        for (int trial = 0; trial < 2_000; trial++) {
            int x = random.nextInt(-1_875_000, 1_875_001);
            int z = random.nextInt(-1_875_000, 1_875_001);
            int maxX = x + random.nextInt(100_000);
            int maxZ = z + random.nextInt(100_000);
            int limit = random.nextInt(4, 4_097);
            int size = ChunkBucketLayout.sizeFor(x, maxX, z, maxZ, limit);
            assertTrue(size > 0);
            assertTrue(bucketCount(x, maxX, size) * bucketCount(z, maxZ, size) <= limit,
                    "Viewport exceeds bucket budget at trial " + trial);
        }
        assertEquals(1, ChunkBucketLayout.sizeFor(2, 1, 0, 0, 4));
    }

    @Test
    void comparableBuiltInAndXaeroScalesUseTheSameBoundedBuckets() {
        int width = 960, height = 540;
        int budget = ChunkBucketLayout.DEFAULT_MAP_BUDGET;
        var saved = new ChunkStatusSnapshot(List.of(), Set.of());
        var live = LiveChunkStatusSnapshot.EMPTY;
        var builtIn = new ChunkMapProjection();
        var xaero = new ChunkMapProjection();
        ChunkMapView.Canvas canvas = (left, top, right, bottom, color) -> {};

        int previousSize = Integer.MAX_VALUE;
        for (int zoom : new int[]{1, 2, 3, 4, 5, 6, 8, 12, 16}) {
            int previousPanSize = -1;
            for (double centerX : new double[]{-121.5, -120.25, -119, 0, 122.75}) {
                var viewport = new ChunkMapView.BuiltInViewport(width, height, centerX, -21.375, zoom, true);
                ChunkMapView.renderBuiltIn(canvas, builtIn, saved, live, viewport, 100L, 1);
                ChunkMapView.renderOverlay(canvas, xaero, coordinate -> 0, coordinate -> 0,
                        saved, live, width, height, centerX * 16, -21.375 * 16, zoom / 16.0D, 100L, budget);

                var frame = builtIn.currentFrame();
                assertEquals(frame.bucketSize(), xaero.currentFrame().bucketSize());
                if (!frame.dense()) assertEquals(frame.projectedBuckets(), xaero.currentFrame().projectedBuckets());
                assertTrue(frame.projectedBuckets() <= budget);
                assertTrue(xaero.currentFrame().projectedBuckets() <= budget);
                if (previousPanSize != -1) assertEquals(previousPanSize, frame.bucketSize());
                previousPanSize = frame.bucketSize();
                if (zoom == 2) assertTrue(frame.bucketSize() > 1 && !frame.dense());
                if (zoom == 16) assertTrue(frame.dense());
            }
            assertTrue(previousPanSize <= previousSize);
            previousSize = previousPanSize;
        }
    }

    @Test
    void mapBudgetIncludesTheDenseBoundaryHaloAtThreshold() {
        var saved = new ChunkStatusSnapshot(List.of(), Set.of());
        ChunkMapView.Canvas canvas = (left, top, right, bottom, color) -> {};
        var viewport = new ChunkMapView.BuiltInViewport(180, 180, 0, 0, 3, true);
        ChunkMapView.renderBuiltIn(canvas, projection, saved, LiveChunkStatusSnapshot.EMPTY, viewport, 100L, 1);
        assertEquals(1, projection.currentFrame().bucketSize());
        assertTrue(projection.currentFrame().dense());

        // Visible spans fit in 6000 cells, but adding the dense halo at this scale would exceed it.
        viewport = new ChunkMapView.BuiltInViewport(216, 222, 0, 0, 3, true);
        ChunkMapView.renderBuiltIn(canvas, projection, saved, LiveChunkStatusSnapshot.EMPTY, viewport, 100L, 1);
        assertEquals(1, projection.currentFrame().bucketSize());
        assertFalse(projection.currentFrame().dense());
        assertTrue(projection.currentFrame().projectedBuckets() <= ChunkBucketLayout.DEFAULT_MAP_BUDGET);
    }

    @Test
    void bothMapViewportsClipAtWorldLimitsWithoutChangingBucketSize() {
        int width = 960, height = 540;
        int budget = ChunkBucketLayout.DEFAULT_MAP_BUDGET;
        var saved = new ChunkStatusSnapshot(List.of(), Set.of());
        var live = LiveChunkStatusSnapshot.EMPTY;
        var xaero = new ChunkMapProjection();
        ChunkMapView.Canvas canvas = (left, top, right, bottom, color) -> {};
        ChunkMapView.renderBuiltIn(canvas, projection, saved, live,
                new ChunkMapView.BuiltInViewport(width, height, 0, 0, 2, true), 100L, 1);
        int interiorSize = projection.currentFrame().bucketSize();

        double edge = 1_875_000D;
        var viewport = new ChunkMapView.BuiltInViewport(width, height, edge, -edge, 2, true);
        var builtInRange = ChunkMapView.renderBuiltIn(canvas, projection, saved, live, viewport, 100L, 1);
        ChunkMapView.renderOverlay(canvas, xaero, coordinate -> 0, coordinate -> 0,
                saved, live, width, height, edge * 16, -edge * 16, 2 / 16.0D, 100L, budget);
        assertEquals(1_875_000, builtInRange.maxX());
        assertEquals(-1_875_000, builtInRange.minZ());
        assertEquals(interiorSize, projection.currentFrame().bucketSize());
        assertEquals(interiorSize, xaero.currentFrame().bucketSize());
        assertEquals(projection.currentFrame().projectedBuckets(), xaero.currentFrame().projectedBuckets());

        assertTrue(ChunkMapView.visibleRange(new ChunkMapView.BuiltInViewport(
                width, height, Integer.MAX_VALUE, Integer.MIN_VALUE, 2, true)).isEmpty());
    }

    private static long bucketCount(int min, int max, int size) {
        return (long) Math.floorDiv(max, size) - Math.floorDiv(min, size) + 1L;
    }
}
