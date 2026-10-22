package io.github.billstark001.worldmirror.download;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureRegionScanTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void scansMoreThanOneQueueCapacityWithoutDroppingOrRevisitingCoordinates() {
        var queue = new DownloadCaptureQueue(() -> false, 33, () -> 512);
        var scan = new CaptureRegionScan(Level.OVERWORLD, 0, 0, 33);
        Set<DownloadCaptureQueue.Key> captured = new HashSet<>();
        int ticks = 0;
        while (!scan.isComplete() || queue.hasWork()) {
            assertTrue(++ticks < 200, "a full queue must resume from its cursor instead of restarting");
            scan.fill(256, () -> queue.metrics().pendingCaptures() < 512,
                    key -> queue.queue(key, "bulk", false));
            for (int processed = 0; processed < 64; processed++) {
                var request = queue.pollPending();
                if (request == null) break;
                assertTrue(captured.add(request.key()), "bulk scan revisited an already captured chunk");
            }
        }
        assertEquals(67 * 67, captured.size());
        assertEquals(0, queue.metrics().droppedHints());
        assertTrue(captured.contains(new DownloadCaptureQueue.Key(Level.OVERWORLD, 33, 33)));
    }

    @Test
    void anExhaustedBudgetDoesNotConsumeTheNextCoordinate() {
        var scan = new CaptureRegionScan(Level.OVERWORLD, 5, -2, 1);
        var visited = new ArrayList<DownloadCaptureQueue.Key>();
        assertEquals(0, scan.fill(20, () -> false, key -> { visited.add(key); return true; }));
        assertFalse(scan.isComplete());
        assertEquals(1, scan.fill(1, () -> true, key -> { visited.add(key); return true; }));
        assertEquals(new DownloadCaptureQueue.Key(Level.OVERWORLD, 4, -3), visited.getFirst());
    }

    @Test
    void extremeCoordinatesClampWithoutWrappingTheCursor() {
        var scan = new CaptureRegionScan(Level.OVERWORLD, Integer.MAX_VALUE, Integer.MIN_VALUE, 2);
        Set<DownloadCaptureQueue.Key> visited = new HashSet<>();
        assertEquals(9, scan.fill(100, () -> true, visited::add));
        assertTrue(scan.isComplete());
        assertEquals(9, visited.size());
    }

    @Test
    void coalescingAnAuthoritativeBlockUpdateRetainsEmptyCaptureIntentAndFifoOrder() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 2);
        var first = new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0);
        var second = new DownloadCaptureQueue.Key(Level.OVERWORLD, 1, 0);
        assertTrue(queue.queue(first, "chunk-load", false));
        assertTrue(queue.queue(second, "chunk-load", false));
        assertFalse(queue.queue(first, "block-update", true));

        var upgraded = queue.pollPending();
        assertEquals(first, upgraded.key());
        assertTrue(upgraded.allowEmpty());
        assertEquals(second, queue.pollPending().key());
        assertNull(queue.pollPending());
        assertEquals(1, queue.metrics().coalescedHints());
    }

    @Test
    void authoritativeChangesSurviveAFullQueueAndRemainAvailableForFinalCapture() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 1);
        var ordinary = new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0);
        var changed = new DownloadCaptureQueue.Key(Level.OVERWORLD, 1, 0);
        var another = new DownloadCaptureQueue.Key(Level.OVERWORLD, 2, 0);
        assertTrue(queue.queue(ordinary, "chunk-load", false));
        queue.queue(changed, "block-update", true);
        queue.queue(another, "block-update", true);
        queue.queue(changed, "block-update", true);

        assertEquals(Set.of(changed, another), queue.finalBlockChanges(Level.OVERWORLD));
        assertEquals(ordinary, queue.pollPending().key());
        var firstChange = queue.pollPending();
        assertEquals(changed, firstChange.key());
        assertTrue(firstChange.allowEmpty());
        assertEquals(another, queue.pollPending().key());
        assertNull(queue.pollPending());
        assertFalse(queue.hasWork());
        assertEquals(0, queue.metrics().droppedHints());
    }
}
