package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteChunkProvenanceTest {
    @Test
    void cachedChunksFromTheSameClientLevelRequireFullRefreshAfterSourceChange() {
        var tracker = new RemoteChunkProvenance(2);
        var reused = new Object();
        var another = new Object();
        assertTrue(tracker.accepts(reused, 1));
        tracker.enter(reused);
        assertFalse(tracker.accepts(reused, 1));
        assertTrue(tracker.accepts(another, 1));
        tracker.record(another, 1);
        assertFalse(tracker.accepts(reused, 1));
        tracker.record(reused, 1);
        assertTrue(tracker.accepts(reused, 1));
        tracker.enter(reused);
        assertFalse(tracker.accepts(reused, 1));
    }

    @Test
    void fullRefreshEvidenceStaysBounded() {
        var tracker = new RemoteChunkProvenance(2);
        var world = new Object();
        tracker.enter(world);
        tracker.record(world, 3);
        tracker.record(world, 4);
        tracker.record(world, 3);
        tracker.record(world, 5);
        assertFalse(tracker.accepts(world, 4));
        assertTrue(tracker.accepts(world, 3));
        assertTrue(tracker.accepts(world, 5));
    }
}
