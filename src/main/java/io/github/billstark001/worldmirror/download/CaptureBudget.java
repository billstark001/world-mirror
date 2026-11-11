package io.github.billstark001.worldmirror.download;

/** One tick's executable limits; unload-time capture is independent of this budget. */
record CaptureBudget(long tickNanos, int maxChunks, int scanChecks, boolean scanOnlyWhenIdle,
                     boolean refreshEntities, boolean preExportScan) {
    static CaptureBudget forMode(CapturePolicy.Mode mode, int configuredMicros) {
        long normalNanos = Math.max(250L, configuredMicros) * 1_000L;
        return switch (mode) {
            case NORMAL -> new CaptureBudget(normalNanos, 64, 256, false, true, true);
            case REDUCED -> new CaptureBudget(Math.min(normalNanos, 500_000L), 8, 16, true, false, false);
            case PAUSED -> new CaptureBudget(0, 0, 0, true, false, false);
        };
    }
}
