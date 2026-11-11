package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureBudgetTest {
    @Test
    void resourceModesProduceExplicitLimitsForTheQueueAndOptionalWork() {
        var normal = CaptureBudget.forMode(CapturePolicy.Mode.NORMAL, 4_000);
        var reduced = CaptureBudget.forMode(CapturePolicy.Mode.REDUCED, 4_000);
        var paused = CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 4_000);
        assertEquals(4_000_000, normal.tickNanos());
        assertEquals(500_000, reduced.tickNanos());
        assertTrue(reduced.maxChunks() < normal.maxChunks());
        assertTrue(reduced.scanChecks() < normal.scanChecks());
        assertTrue(reduced.scanOnlyWhenIdle());
        assertFalse(reduced.refreshEntities());
        assertFalse(reduced.preExportScan());
        assertEquals(0, paused.maxChunks());
        assertEquals(0, paused.scanChecks());
        assertEquals(0, paused.tickNanos());
        assertTrue(normal.refreshEntities());
        assertTrue(normal.preExportScan());
    }
}
