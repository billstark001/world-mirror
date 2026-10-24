package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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
        projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 319, 0, 319), 1_000L, 4096, false);
        long first = snapshot.index().visits();
        projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 319, 0, 319), 2_001L, 4096, false);
        assertEquals(first, snapshot.index().visits(), "age refresh reuses complete bucket summaries");
        projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(1, 318, 1, 318), 2_002L, 4096, false);
        assertTrue(snapshot.index().visits() - first < 10_000,
                "panning inspects clipped edges, not the 102400-record interior again");
        long beforeNewBuckets = snapshot.index().visits();
        var panned = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(5, 324, 0, 319), 2_003L, 4096, false);
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

        var first = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 8, 0, 8), 100L, 4, false);
        var panned = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(1, 9, 0, 8), 101L, 4, false);

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
        var first = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 1, 0, 1), 1_000L, 4096, false);

        assertSame(first, projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 1, 0, 1), 1_999L, 4096, false));
        var refreshed = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 1, 0, 1), 2_000L, 4096, false);
        assertNotSame(first, refreshed);
        assertNotSame(refreshed, projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 1, 0, 1), 500L, 4096, false));
    }

    @Test
    void dataReuseDoesNotFreezeBoundaryAgeColors() {
        long now = System.currentTimeMillis();
        var snapshot = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, now, "world_mirror")), Set.of());
        var range = new ChunkBounds(0, 0, 0, 0);
        var fresh = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, now, 4096, true);
        var old = projection.get(snapshot, LiveChunkStatusSnapshot.EMPTY, range, now + 31L * 24 * 60 * 60 * 1_000, 4096, true);
        assertFalse(fresh.boundaries().equals(old.boundaries()));
        assertTrue(snapshot.matchesData(snapshot.records(), Set.of()));
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

    private static long bucketCount(int min, int max, int size) {
        return (long) Math.floorDiv(max, size) - Math.floorDiv(min, size) + 1L;
    }
}
