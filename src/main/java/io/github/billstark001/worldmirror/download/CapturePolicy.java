package io.github.billstark001.worldmirror.download;

/** Resource policy, independent of whether the user enabled continuous capture. */
final class CapturePolicy {
    enum Mode { NORMAL, REDUCED, PAUSED }
    enum Reason { NONE, HEAP, BACKLOG, RETRIES }
    private static final long PAUSE_CHUNKS = 16_384, REDUCE_CHUNKS = PAUSE_CHUNKS / 2;
    private static final double REDUCE_HEAP = 0.80, PAUSE_HEAP = 0.90, EMERGENCY_HEAP = 0.97;
    private static final double NORMAL_RECOVERY_HEAP = 0.75, REDUCED_RECOVERY_HEAP = 0.85;

    record Sample(long usedHeap, long maxHeap, int dirtyChunks, long retainedChunks,
                  int frozenBundles, int inFlightChunks) {
        double heapFraction() { return maxHeap > 0 ? (double) usedHeap / maxHeap : 0.0; }
    }

    private record Pressure(Reason reason, boolean critical, boolean emergency,
                            boolean normalRecovery, boolean reducedRecovery) {
        static Pressure assess(Sample sample) {
            double heap = sample.heapFraction();
            long retained = sample.retainedChunks();
            boolean lowBacklog = retained < REDUCE_CHUNKS;
            Reason reason = Reason.NONE;
            if (retained >= PAUSE_CHUNKS) reason = Reason.BACKLOG;
            else if (heap >= REDUCE_HEAP) reason = Reason.HEAP;
            else if (!lowBacklog) reason = Reason.BACKLOG;
            else if (sample.frozenBundles() >= 4) reason = Reason.RETRIES;
            return new Pressure(reason, retained >= PAUSE_CHUNKS || (heap >= PAUSE_HEAP && retained > 0),
                    heap >= EMERGENCY_HEAP && retained > 0,
                    heap < NORMAL_RECOVERY_HEAP && lowBacklog && sample.frozenBundles() < 4,
                    lowBacklog && (heap < REDUCED_RECOVERY_HEAP || (retained == 0 && heap < EMERGENCY_HEAP)));
        }
    }

    /** Tracks how long one condition has remained true, including negative clock origins. */
    private static final class Hold {
        private long since = Long.MIN_VALUE;
        boolean observe(boolean condition, long now, long duration) {
            if (!condition) since = Long.MIN_VALUE;
            else if (since == Long.MIN_VALUE) since = now;
            return since != Long.MIN_VALUE && now - since >= duration;
        }
    }

    private final Hold highPressure = new Hold(), normalRecovery = new Hold(), reducedRecovery = new Hold();
    private Mode mode = Mode.NORMAL;
    private Reason reason = Reason.NONE;

    Mode mode() { return mode; }
    Reason reason() { return reason; }

    void reset() {
        mode = Mode.NORMAL;
        reason = Reason.NONE;
        highPressure.observe(false, 0, 0);
        normalRecovery.observe(false, 0, 0);
        reducedRecovery.observe(false, 0, 0);
    }

    Mode update(long now, Sample sample) {
        Pressure pressure = Pressure.assess(sample);
        boolean pause = highPressure.observe(pressure.critical(), now, 2_000) || pressure.emergency();
        boolean normal = normalRecovery.observe(!pause && pressure.normalRecovery(), now, 3_000);
        boolean reduced = reducedRecovery.observe(!pause && mode == Mode.PAUSED && pressure.reducedRecovery(), now, 3_000);
        mode = pause ? Mode.PAUSED : switch (mode) {
            case NORMAL -> pressure.reason() == Reason.NONE ? Mode.NORMAL : Mode.REDUCED;
            case REDUCED -> normal ? Mode.NORMAL : Mode.REDUCED;
            case PAUSED -> normal ? Mode.NORMAL : reduced ? Mode.REDUCED : Mode.PAUSED;
        };
        if (mode == Mode.NORMAL) reason = Reason.NONE;
        else if (pressure.reason() != Reason.NONE) reason = pressure.reason();
        return mode;
    }
}
