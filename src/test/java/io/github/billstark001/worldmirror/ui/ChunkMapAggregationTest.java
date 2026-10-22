package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMapAggregationTest {
    @Test
    void panningInsideTheSameBucketsRefreshesVisibleRecordsAndConflicts() {
        var snapshot = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 1L, "world_mirror"),
                new ChunkDatabase.ChunkRecord(9, 0, 2L, "external")),
                List.of(new ChunkPos(9, 0)), 1L);

        var first = ChunkMapAggregation.aggregate(snapshot, 0, 8, 0, 8, 100L, 4);
        var panned = ChunkMapAggregation.aggregate(snapshot, 1, 9, 0, 8, 101L, 4);

        assertEquals(first.bucketSize(), panned.bucketSize());
        assertFalse(first.runs().stream().anyMatch(ChunkMapAggregation.Run::conflict));
        assertTrue(panned.runs().stream().anyMatch(ChunkMapAggregation.Run::conflict));
        assertEquals(1, panned.runs().size());
        assertEquals(0xFFFF9000, panned.runs().getFirst().color());
    }

    @Test
    void reusesAnUnchangedViewportOnlyUntilTheAgeRefresh() {
        var snapshot = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 1L, "world_mirror")), List.of(), 2L);
        var first = ChunkMapAggregation.aggregate(snapshot, 0, 1, 0, 1, 1_000L);

        assertSame(first, ChunkMapAggregation.aggregate(snapshot, 0, 1, 0, 1, 1_999L));
        var refreshed = ChunkMapAggregation.aggregate(snapshot, 0, 1, 0, 1, 2_000L);
        assertNotSame(first, refreshed);
        assertNotSame(refreshed, ChunkMapAggregation.aggregate(snapshot, 0, 1, 0, 1, 500L));
    }

    @Test
    void thinAndExtremeViewportsHaveBoundedSizingCost() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            int thin = ChunkMapAggregation.bucketSizeFor(0, 2_000_000_000, 0, 0, 1);
            assertTrue(thin > 0);
            assertTrue(bucketCount(0, 2_000_000_000, thin) <= 1);

            int extreme = ChunkMapAggregation.bucketSizeFor(
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
            int size = ChunkMapAggregation.bucketSizeFor(x, maxX, z, maxZ, limit);
            assertTrue(size > 0);
            assertTrue(bucketCount(x, maxX, size) * bucketCount(z, maxZ, size) <= limit,
                    "Viewport exceeds bucket budget at trial " + trial);
        }
        assertEquals(1, ChunkMapAggregation.bucketSizeFor(2, 1, 0, 0, 4));
    }

    private static long bucketCount(int min, int max, int size) {
        return (long) Math.floorDiv(max, size) - Math.floorDiv(min, size) + 1L;
    }
}
