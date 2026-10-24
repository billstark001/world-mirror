package io.github.billstark001.worldmirror.ui;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ChunkViewportCacheTest {
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
            return new LiveChunkStatusSnapshot(Map.of());
        };
        var context = new ChunkViewportCache.Context("source", Level.OVERWORLD, ChunkStatusSnapshot.EMPTY, 1, 1);
        var first = cache.get(world, context, range, 0, capture);
        assertSame(first, cache.get(world, context, range, 60_000, capture));
        assertEquals(1, captures.get());
        var changed = new ChunkViewportCache.Context("source", Level.OVERWORLD, ChunkStatusSnapshot.EMPTY, 2, 1);
        assertNotSame(first, cache.get(world, changed, range, 60_001, capture));
        cache.get(new Object(), changed, range, 60_002, capture);
        cache.get(world, new ChunkViewportCache.Context("other", Level.OVERWORLD, ChunkStatusSnapshot.EMPTY, 2, 1), range, 60_003, capture);
        cache.get(world, changed, range, 1, capture);
        cache.clear();
        cache.get(world, changed, range, 2, capture);
        assertEquals(6, captures.get());
    }
}
