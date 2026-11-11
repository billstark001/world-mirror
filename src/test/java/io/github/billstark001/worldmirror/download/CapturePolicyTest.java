package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;

import static io.github.billstark001.worldmirror.download.CapturePolicy.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class CapturePolicyTest {
    @Test
    void monotonicClockMayHaveANegativeOrigin() {
        var controller = new CapturePolicy();
        assertEquals(REDUCED, controller.update(-10_000, sample(91, 1, 0)));
        assertEquals(PAUSED, controller.update(-8_000, sample(91, 1, 0)));
        assertEquals(PAUSED, controller.update(-7_000, sample(50, 0, 0)));
        assertEquals(NORMAL, controller.update(-4_000, sample(50, 0, 0)));
    }

    private static CapturePolicy.Sample sample(int heapPercent, long chunks, int bundles) {
        return new CapturePolicy.Sample(heapPercent, 100, (int) chunks, chunks, bundles, 0);
    }

    @Test
    void aSingleHeapPeakDoesNotCancelCaptureAndRecoversWithoutRestarting() {
        var controller = new CapturePolicy();
        assertEquals(NORMAL, controller.update(0, sample(79, 1, 0)));
        assertEquals(REDUCED, controller.update(50, sample(81, 1, 0)));
        assertFalse(CaptureBudget.forMode(controller.mode(), 1500).refreshEntities());
        assertEquals(REDUCED, controller.update(100, sample(70, 0, 0)));
        assertEquals(NORMAL, controller.update(3_100, sample(70, 0, 0)));
        assertTrue(CaptureBudget.forMode(controller.mode(), 1500).refreshEntities());
    }

    @Test
    void sustainedPressurePausesAndRequiresStableLowWatermarksToResume() {
        var controller = new CapturePolicy();
        assertEquals(REDUCED, controller.update(0, sample(91, 1, 0)));
        assertEquals(REDUCED, controller.update(1_999, sample(91, 1, 0)));
        assertEquals(PAUSED, controller.update(2_000, sample(91, 1, 0)));
        assertEquals(PAUSED, controller.update(3_000, sample(70, 0, 0)));
        assertEquals(PAUSED, controller.update(5_000, sample(86, 1, 0)), "reset the recovery window");
        assertEquals(PAUSED, controller.update(6_000, sample(70, 0, 0)));
        assertEquals(PAUSED, controller.update(8_999, sample(70, 0, 0)));
        assertEquals(NORMAL, controller.update(9_000, sample(70, 0, 0)));
    }

    @Test
    void exportDrainCanResumeReducedCaptureBeforeTheJvmCollectsGarbage() {
        var controller = new CapturePolicy();
        controller.update(0, sample(91, 1, 0));
        assertEquals(PAUSED, controller.update(2_000, sample(91, 1, 0)));
        assertEquals(PAUSED, controller.update(3_000, sample(91, 0, 0)));
        assertEquals(REDUCED, controller.update(6_000, sample(91, 0, 0)));
        assertFalse(CaptureBudget.forMode(controller.mode(), 1500).refreshEntities());
        assertEquals(REDUCED, controller.update(7_000, sample(70, 0, 0)));
        assertEquals(NORMAL, controller.update(10_000, sample(70, 0, 0)));
    }

    @Test
    void partialHeapRecoveryRestartsWithASmallerBudget() {
        var controller = new CapturePolicy();
        controller.update(0, sample(91, 10, 0));
        controller.update(2_000, sample(91, 10, 0));
        assertEquals(PAUSED, controller.update(3_000, sample(84, 10, 0)));
        assertEquals(REDUCED, controller.update(6_000, sample(84, 10, 0)));
        assertEquals(REDUCED, controller.update(60_000, sample(84, 10, 0)));
    }

    @Test
    void smallRetryBundlesCannotHoldCaptureInResourcePause() {
        var controller = new CapturePolicy();
        assertEquals(PAUSED, controller.update(0, sample(98, 8, 8)));
        assertEquals(PAUSED, controller.update(1_000, sample(50, 8, 8)));
        assertEquals(REDUCED, controller.update(4_000, sample(50, 8, 8)));
    }

    @Test
    void aNewSessionClearsPreviousPressureWindows() {
        var controller = new CapturePolicy();
        assertEquals(PAUSED, controller.update(0, sample(98, 1, 0)));
        controller.reset();
        assertEquals(NORMAL, controller.mode());
        assertEquals(REDUCED, controller.update(11_000, sample(91, 1, 0)));
    }

    @Test
    void backlogPausesIndependentlyOfHeapButSmallRetryBundlesOnlyDegrade() {
        var controller = new CapturePolicy();
        assertEquals(REDUCED, controller.update(0, sample(50, 8, 8)));
        assertEquals(REDUCED, controller.update(60_000, sample(50, 8, 8)));
        assertEquals(REDUCED, controller.update(61_000, sample(50, 16_384, 1)));
        assertEquals(PAUSED, controller.update(63_000, sample(50, 16_384, 1)));
        assertEquals(PAUSED, controller.update(64_000, sample(50, 8_192, 0)));
        assertEquals(PAUSED, controller.update(65_000, sample(50, 8_191, 0)));
        assertEquals(NORMAL, controller.update(68_000, sample(50, 8_191, 0)));
    }

    @Test
    void aBriefCriticalPeakAndAHigherHeapWithoutRetainedWorkDoNotPause() {
        var controller = new CapturePolicy();
        controller.update(0, sample(91, 1, 0));
        assertEquals(REDUCED, controller.update(50, sample(70, 1, 0)));
        assertEquals(REDUCED, controller.update(1_000, sample(91, 1, 0)));
        assertEquals(REDUCED, controller.update(2_050, sample(91, 0, 0)));
        assertEquals(REDUCED, controller.update(20_000, sample(91, 0, 0)));
    }
}
