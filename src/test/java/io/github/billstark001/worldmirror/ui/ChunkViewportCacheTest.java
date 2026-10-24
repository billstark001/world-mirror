package io.github.billstark001.worldmirror.ui;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ChunkViewportCacheTest {
    @Test
    void coarseViewportProgressUsesBoundedRefreshIntervals() {
        for (int radius : new int[] {128, 1_024, 16_384}) {
            var cache = new ChunkViewportCache();
            var range = new ChunkBounds(-radius,radius,-radius,radius);
            long interval = radius <= 256 ? 500 : radius <= 2_048 ? 1_000 : 5_000;
            assertEquals(interval,ChunkViewportCache.refreshInterval(range));
            var old = new ChunkViewportCache.Context("source",Level.OVERWORLD,ChunkStatusSnapshot.EMPTY,1,1,1);
            var next = new ChunkViewportCache.Context("source",Level.OVERWORLD,ChunkStatusSnapshot.EMPTY,1,1,2);
            Object world = new Object();
            Function<ChunkBounds,LiveChunkStatusSnapshot> capture = bounds -> new LiveChunkStatusSnapshot(Map.of(),Map.of());
            var initial = cache.get(world,old,range,0,capture);
            assertSame(initial,cache.get(world,next,range,interval-1,capture));
            assertNotSame(initial,cache.get(world,next,range,interval,capture));
        }
        assertEquals(5_000,ChunkViewportCache.refreshInterval(new ChunkBounds(Integer.MIN_VALUE,Integer.MAX_VALUE,0,0)));
    }
    @Test
    void exportProgressInvalidatesViewportWithBoundedCaptureCadence() {
        var cache = new ChunkViewportCache();
        Object world = new Object();
        var range = new ChunkBounds(0,0,0,0);
        Function<ChunkBounds,LiveChunkStatusSnapshot> capture = bounds -> new LiveChunkStatusSnapshot(Map.of(),Map.of());
        var old = new ChunkViewportCache.Context("source",Level.OVERWORLD,ChunkStatusSnapshot.EMPTY,1,1,1);
        var next = new ChunkViewportCache.Context("source",Level.OVERWORLD,ChunkStatusSnapshot.EMPTY,1,1,2);
        var first = cache.get(world,old,range,0,capture);
        assertSame(first,cache.get(world,next,range,499,capture));
        var changed = cache.get(world,next,range,500,capture);
        assertNotSame(first,changed);
        assertSame(changed,cache.get(world,next,range,60_000,capture));
    }
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test
    void unchangedSourcesReuseCaptureAndEveryContextChangeInvalidatesIt() {
        var cache = new ChunkViewportCache();
        Object world = new Object();
        var range = new ChunkBounds(-1, 1, -1, 1);
        AtomicInteger captures = new AtomicInteger();
        Function<ChunkBounds, LiveChunkStatusSnapshot> capture = bounds -> {
            captures.incrementAndGet();
            assertTrue(bounds.contains(range));
            return new LiveChunkStatusSnapshot(Map.of(), Map.of());
        };
        var context = new ChunkViewportCache.Context("source", Level.OVERWORLD, ChunkStatusSnapshot.EMPTY, 1, 1, 0L);
        var first = cache.get(world, context, range, 0, capture);
        assertSame(first, cache.get(world, context, range, 60_000, capture));
        assertEquals(1, captures.get());
        var changed = new ChunkViewportCache.Context("source", Level.OVERWORLD, ChunkStatusSnapshot.EMPTY, 2, 1, 0L);
        assertNotSame(first, cache.get(world, changed, range, 60_001, capture));
        cache.get(new Object(), changed, range, 60_002, capture);
        cache.get(world, new ChunkViewportCache.Context("other", Level.OVERWORLD, ChunkStatusSnapshot.EMPTY, 2, 1, 0L), range, 60_003, capture);
        cache.get(world, changed, range, 1, capture);
        cache.clear();
        cache.get(world, changed, range, 2, capture);
        assertEquals(6, captures.get());
    }
}
