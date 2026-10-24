package io.github.billstark001.worldmirror.util;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkRegionIndexTest {
    @Test
    void aSmallViewportDoesNotVisitALargeDistantHistory() {
        var index = new ChunkRegionIndex<Integer>();
        for (int n = 0; n < 100_000; n++) index.put(n * 32, n % 100 * 32, n);
        List<Integer> found = new ArrayList<>();
        int visited = index.forEachInRange(-1, 15, -1, 15, found::add);
        assertEquals(List.of(0), found);
        assertEquals(1, visited);
        assertFalse(index.put(0, 0, 0));
        assertTrue(index.remove(0, 0));
        assertEquals(0, index.forEachInRange(-1, 15, -1, 15, found::add));
    }

    @Test
    void negativeAndExtremeCoordinatesRemainDistinctAndSparseWorldQueriesTerminate() {
        var index = new ChunkRegionIndex<String>();
        index.put(Integer.MIN_VALUE, Integer.MAX_VALUE, "extreme");
        index.put(-1, -1, "negative");
        index.put(0, 0, "origin");
        List<String> found = new ArrayList<>();
        assertEquals(3, index.forEachInRange(Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE, found::add));
        assertEquals(3, found.size());
        found.clear();
        index.forEachInRange(-1, -1, -1, -1, found::add);
        assertEquals(List.of("negative"), found);
    }
}
