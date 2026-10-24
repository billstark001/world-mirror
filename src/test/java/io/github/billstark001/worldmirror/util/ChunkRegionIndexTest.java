package io.github.billstark001.worldmirror.util;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkRegionIndexTest {
    @Test
    void collisionHeavyCoordinateCopiesAreIsolatedAndFullyReadOnly() {
        var source = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Integer>();
        for (int n = -10_000; n < 10_000; n++) source.put(((long)n<<32)|(n&0xFFFFFFFFL),Integer.valueOf(n));
        var copy = ChunkRegionIndex.copyCoordinates(source);
        source.clear();
        assertEquals(20_000,copy.size());
        for (int n = -10_000; n < 10_000; n++) assertEquals(n,copy.get(((long)n<<32)|(n&0xFFFFFFFFL)));
        assertThrows(UnsupportedOperationException.class,()->copy.put(0L,1));
        assertThrows(UnsupportedOperationException.class,()->copy.entrySet().iterator().next().setValue(1));
    }
    @Test
    void publishedPagesCopyOnlyChangedRegionsAndRemainStableWhileWriting() {
        var index = new ChunkRegionIndex.Publication<Integer>();
        for (int n = 0; n < 100_000; n++) index.put(n * 32, n % 100 * 32, n);
        index.publish();
        long before = index.copiedRecords();
        index.put(0, 0, -1);
        var old = new ArrayList<Integer>();
        assertEquals(1, index.forEachInRange(0, 0, 0, 0, old::add));
        assertEquals(List.of(0), old, "unpublished edits cannot leak into readers");
        index.publish();
        assertEquals(before + 1, index.copiedRecords(), "a one-region edit must not copy the distant history");
        var next = new ArrayList<Integer>();
        assertEquals(1, index.forEachInRange(0, 0, 0, 0, next::add));
        assertEquals(List.of(-1), next);
        assertEquals(List.of(0), old);
        index.publish();
        assertEquals(before + 1, index.copiedRecords());
    }

    @Test
    void concurrentReadersSeeCompleteImmutableRegionPages() throws Exception {
        var index = new ChunkRegionIndex.Publication<Integer>();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) index.put(x,z,0);
        index.publish();
        var writer = java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (int revision = 1; revision <= 20; revision++) {
                for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) index.put(x,z,revision);
                index.publish();
            }
        });
        for (int read = 0; read < 200; read++) {
            var page = new ArrayList<Integer>();
            assertEquals(1024,index.forEachInRange(0,31,0,31,page::add));
            assertEquals(1,page.stream().distinct().count());
        }
        writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(21L*1024,index.copiedRecords());
    }
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
