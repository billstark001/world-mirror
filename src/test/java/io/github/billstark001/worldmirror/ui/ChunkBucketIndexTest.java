package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode.MAP;
import static io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode.SESSION;
import static io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot.State.*;
import static org.junit.jupiter.api.Assertions.*;

class ChunkBucketIndexTest {
    @Test
    void summaryPreservesDifferentMapAndSessionWinners() {
        var saved = new ChunkStatusSnapshot(List.of(new ChunkRecord(0, 0, 200, "world_mirror"),
                new ChunkRecord(1, 0, 300, "external")), Set.of());
        var live = live(new LiveChunkStatusSnapshot.Record(0, 0, 100, PENDING_WRITE, false),
                new LiveChunkStatusSnapshot.Record(1, 0, 400, CACHED, false));
        var layout = new ChunkBucketLayout(new ChunkBounds(0, 3, 0, 3), 4);
        var summary = ChunkBucketIndex.combine(saved.index(), live.index(), layout).get(ChunkBounds.key(0, 0));
        assertEquals("external", summary.newest().updateSource());
        assertEquals(200, summary.newestMirrorTime());
        assertEquals(PENDING_WRITE, summary.live().state());
        assertEquals(400, summary.newestLiveTime());
        assertEquals(0xFF91E633, ChunkMapStyle.world(summary, MAP, 250, 500));
        assertEquals(0xFF20E060, ChunkMapStyle.world(summary, SESSION, 250, 500));
        var savedOnly = saved.index().query(layout).get(ChunkBounds.key(0, 0));
        assertEquals(0xFFFF9000, ChunkMapStyle.world(savedOnly, MAP, 150, 500));
        assertEquals(0xFF20E060, ChunkMapStyle.world(savedOnly, SESSION, 150, 500));
    }

    @Test
    void clippedLiveBucketsCannotSelectPendingHintsOutsideTheViewport() {
        var live = live(new LiveChunkStatusSnapshot.Record(0, 0, 100, CACHED, false),
                new LiveChunkStatusSnapshot.Record(1, 0, 0, PENDING_CAPTURE, false));
        var summary = live.index().query(new ChunkBucketLayout(new ChunkBounds(0, 0, 0, 0), 4)).get(0L);
        assertEquals(CACHED, summary.live().state());
        assertFalse(summary.pendingCapture());
    }

    @Test
    void equalTimestampsAndLivePrioritiesHaveDeterministicCoordinateTies() {
        var records = new ArrayList<>(List.of(new ChunkRecord(-2, -1, 10, "world_mirror"), new ChunkRecord(-1, -1, 10, "external")));
        var layout = new ChunkBucketLayout(new ChunkBounds(-4, -1, -4, -1), 4);
        var a = new ChunkStatusSnapshot(records, Set.of()).index().query(layout);
        Collections.reverse(records);
        var b = new ChunkStatusSnapshot(records, Set.of()).index().query(layout);
        assertEquals(a, b);
        assertEquals("external", a.values().iterator().next().newest().updateSource());
    }

    @Test
    void randomClippedAndNegativeBucketsAgreeWithDirectRecordSelection() {
        Random random = new Random(0xB0C4E7L);
        var records = new ArrayList<ChunkRecord>();
        for (int x = -30; x <= 30; x++) for (int z = -30; z <= 30; z++) {
            if (random.nextBoolean()) records.add(new ChunkRecord(x, z, records.size() + 1L,
                    random.nextBoolean() ? "world_mirror" : "external"));
        }
        var snapshot = new ChunkStatusSnapshot(records, List.of(new ChunkPos(-3, 2)));
        for (int trial = 0; trial < 100; trial++) {
            int x = random.nextInt(-35, 30), z = random.nextInt(-35, 30), size = random.nextInt(1, 12);
            var bounds = new ChunkBounds(x, x + random.nextInt(1, 30), z, z + random.nextInt(1, 30));
            var actual = snapshot.index().query(new ChunkBucketLayout(bounds, size));
            Map<Long, ChunkRecord> expected = new HashMap<>();
            for (var record : records) if (bounds.contains(record.x(), record.z())) {
                long key = ChunkBounds.key(Math.floorDiv(record.x(), size), Math.floorDiv(record.z(), size));
                expected.merge(key, record, (a, b) -> a.updateTime() > b.updateTime() ? a : b);
            }
            actual.forEach((key, summary) -> assertEquals(expected.get(key), summary.newest()));
            expected.forEach((key, record) -> assertEquals(record, actual.get(key).newest()));
            assertEquals(bounds.contains(-3, 2), actual.values().stream().anyMatch(ChunkBucketIndex.Summary::conflict));
        }
    }

    @Test
    void diagonalPanSkipsWarmInteriorRegionsAndDifferentViewsShareBuckets() {
        var records = new ArrayList<ChunkRecord>();
        for (int x = 0; x < 330; x++) for (int z = 0; z < 330; z++) records.add(new ChunkRecord(x, z, 1, "world_mirror"));
        var snapshot = new ChunkStatusSnapshot(records, Set.of());
        var firstView = new ChunkMapProjection();
        var secondView = new ChunkMapProjection();
        var first = firstView.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 319, 0, 319), 0, 4096, false);
        long before = snapshot.index().visits();
        secondView.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(5, 324, 5, 324), 0, 4096, false);
        assertTrue(snapshot.index().visits() - before < 25_000, "only the new edge regions may be scanned");
        assertSame(first, firstView.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 319, 0, 319), 1, 4096, false));
        long beforeSession = snapshot.index().visits();
        ChunkWorldOverlay.build(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 319, 0, 319),
                new ChunkWorldOverlay.Settings(96, 1, true, 60, SESSION), 1);
        assertEquals(beforeSession, snapshot.index().visits(), "SESSION shares the same color-independent bucket facts");
    }

    @Test
    void completeBucketMemoizationIsBoundedAndExtremeCoordinatesRemainDistinct() {
        var index = new ChunkBucketIndex(List.of(new ChunkBucketIndex.Fact(1_000_000, 0,
                new ChunkRecord(1_000_000, 0, 1, "world_mirror"), false, null)));
        for (int x = 0; x < 17_000; x++) index.query(new ChunkBucketLayout(new ChunkBounds(x * 2, x * 2 + 1, 0, 1), 2));
        assertEquals(16_384, index.cachedBuckets());
        var saved = new ChunkStatusSnapshot(List.of(new ChunkRecord(Integer.MIN_VALUE, Integer.MAX_VALUE, 1, "world_mirror"),
                new ChunkRecord(Integer.MAX_VALUE, Integer.MIN_VALUE, 2, "external")), Set.of());
        var result = saved.index().query(ChunkBucketLayout.withinBudget(
                new ChunkBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE), 4));
        assertEquals(2, result.size());
    }

    @Test
    void sparseDenseViewsVisitStoredFactsInsteadOfEnumeratingEmptyCoordinates() {
        var snapshot = new ChunkStatusSnapshot(List.of(new ChunkRecord(-9_000, 0, 1, "world_mirror"),
                new ChunkRecord(9_000, 0, 2, "world_mirror")), Set.of());
        var layout = new ChunkBucketLayout(new ChunkBounds(-10_000, 10_000, -10_000, 10_000), 1);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertEquals(2, snapshot.index().query(layout).size());
            assertTrue(LiveChunkStatusSnapshot.EMPTY.index().query(layout).isEmpty());
        });
        assertEquals(2, snapshot.index().visits());
        assertEquals(0, LiveChunkStatusSnapshot.EMPTY.index().visits());
    }

    private static LiveChunkStatusSnapshot live(LiveChunkStatusSnapshot.Record... records) {
        Map<Long, LiveChunkStatusSnapshot.Record> byChunk = new HashMap<>();
        for (var record : records) byChunk.put(ChunkBounds.key(record.x(), record.z()), record);
        return new LiveChunkStatusSnapshot(byChunk);
    }
}
