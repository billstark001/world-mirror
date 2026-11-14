package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.WMLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.billstark001.worldmirror.download.DownloadCaptureQueue.Key;

/** Captures diagnostic timing and hint counters without changing the queue policy. */
final class CaptureQueueDiagnostics {
    private static final int MAX_DIAGNOSTIC_CAPTURE_REASONS = 32;

    record DiagnosticSnapshot(LatencyWindow.Summary captureTickLatency, LatencyWindow.Summary unloadLatency,
            long processed, long completed, long budgetOverruns, long maximumBudgetOverrunUs,
            long unloadFailures, String hintSummary, String reasonLatencySummary) {}

    static final class HintCounters {
        private long received;
        private long queued;
        private long coalesced;
        private long dropped;
    }

    private long coalescedHints;
    private long droppedHints;
    private final Map<String, HintCounters> hintsByReason = new HashMap<>();
    private final Map<String, LatencyWindow> latenciesByReason = new HashMap<>();
    private final LatencyWindow captureTickLatencies = new LatencyWindow(4096);
    private final LatencyWindow unloadLatencies = new LatencyWindow(4096);
    private long unloadFailures;
    private long budgetOverruns;
    private long maximumBudgetOverrunUs;
    private long processedCaptures;
    private long completedCaptures;

    void reset() {
        coalescedHints = 0L;
        droppedHints = 0L;
        hintsByReason.clear();
        latenciesByReason.clear();

        captureTickLatencies.reset();
        unloadLatencies.reset();
        unloadFailures = 0L;
        budgetOverruns = 0L;
        maximumBudgetOverrunUs = 0L;
        processedCaptures = 0L;
        completedCaptures = 0L;
    }

    DiagnosticSnapshot snapshot() {
        var result = new DiagnosticSnapshot(captureTickLatencies.snapshotAndReset(),
                unloadLatencies.snapshotAndReset(), processedCaptures, completedCaptures, budgetOverruns,
                maximumBudgetOverrunUs, unloadFailures, hintSummary(), reasonLatencySummary());
        processedCaptures = completedCaptures = budgetOverruns = maximumBudgetOverrunUs = 0;
        return result;
    }

    HintCounters received(String reason) {
        String metricReason = metricReason(reason, hintsByReason);
        HintCounters counters = hintsByReason.computeIfAbsent(metricReason, ignored -> new HintCounters());
        counters.received++;
        return counters;
    }

    void coalesced(HintCounters counters) {
        coalescedHints++;
        if (counters != null)
            counters.coalesced++;
    }

    void dropped(HintCounters counters) {
        droppedHints++;
        if (counters != null)
            counters.dropped++;
    }

    void queued(HintCounters counters) {
        if (counters != null)
            counters.queued++;
    }

    long coalescedHints() {
        return coalescedHints;
    }

    long droppedHints() {
        return droppedHints;
    }

    void unloadFailed() {
        unloadFailures++;
    }

    void recordUnload(Key key, long elapsedUs) {
        unloadLatencies.record(elapsedUs);
        recordReasonLatency("unload-final", elapsedUs);
        logSlowCapture("unload", "unload-final", key, 0L, elapsedUs);
    }

    void recordIncremental(DownloadCaptureQueue.Pending request, long elapsedUs) {
        String bucket = recordReasonLatency(request.reason(), elapsedUs);
        long queueAge = Math.max(0L, (System.nanoTime() / 1_000_000L) - request.queuedAt());
        logSlowCapture("incremental", bucket, request.key(), queueAge, elapsedUs);
    }

    void recordTick(int processed, int captured, long elapsedNs, long budgetNs) {
        captureTickLatencies.record(elapsedNs / 1_000L);
        processedCaptures += processed;
        completedCaptures += captured;
        if (elapsedNs > budgetNs) {
            budgetOverruns++;
            maximumBudgetOverrunUs = Math.max(maximumBudgetOverrunUs, (elapsedNs - budgetNs) / 1_000L);
        }
    }

    private String recordReasonLatency(String reason, long elapsedUs) {
        String metricReason = metricReason(reason, latenciesByReason);
        latenciesByReason.computeIfAbsent(metricReason, ignored -> new LatencyWindow(512)).record(elapsedUs);
        return metricReason;
    }

    private static String metricReason(String reason, Map<String, ?> metrics) {
        String normalized = reason == null || reason.isBlank() ? "unknown" : reason;
        if (metrics.containsKey(normalized))
            return normalized;
        return metrics.size() < MAX_DIAGNOSTIC_CAPTURE_REASONS - 1 ? normalized : "other";
    }

    private void logSlowCapture(String origin, String reason, Key key, long queueAgeMs, long elapsedUs) {
        long thresholdUs = Math.max(5_000L, (long) ModConfig.get().performance.captureBudgetMicros * 4L);
        if (elapsedUs < thresholdUs)
            return;
        WMLogger.infoRateLimited("slow-capture-" + origin + '-' + reason, 30_000L,
                "[perf] slowCapture origin=" + origin + " reason=" + reason
                        + " dimension=" + key.dimension().identifier() + " chunk=" + key.chunkX() + ','
                        + key.chunkZ() + " queueAgeMs=" + queueAgeMs + " elapsedUs=" + elapsedUs
                        + " thresholdUs=" + thresholdUs);
    }

    private String hintSummary() {
        if (hintsByReason.isEmpty())
            return "none";
        List<Map.Entry<String, HintCounters>> entries = new ArrayList<>(hintsByReason.entrySet());
        entries.sort((left, right) -> Long.compare(right.getValue().received, left.getValue().received));
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, HintCounters> entry : entries) {
            if (summary.length() > 0)
                summary.append('|');
            HintCounters counters = entry.getValue();
            summary.append(entry.getKey())
                    .append(':')
                    .append(counters.received)
                    .append('/')
                    .append(counters.queued)
                    .append('/')
                    .append(counters.coalesced)
                    .append('/')
                    .append(counters.dropped);
        }
        return summary.toString();
    }

    private String reasonLatencySummary() {
        if (latenciesByReason.isEmpty())
            return "none";
        List<Map.Entry<String, LatencyWindow.Summary>> entries = new ArrayList<>();
        latenciesByReason.forEach(
                (reason, latency) -> entries.add(Map.entry(reason, latency.snapshotAndReset())));
        entries.removeIf(entry -> entry.getValue().observations() == 0L);
        if (entries.isEmpty())
            return "none";
        entries.sort(
                (left, right)
                        -> Long.compare(right.getValue().observations(), left.getValue().observations()));
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, LatencyWindow.Summary> entry : entries) {
            if (summary.length() > 0)
                summary.append('|');
            LatencyWindow.Summary latency = entry.getValue();
            summary.append(entry.getKey())
                    .append(':')
                    .append(latency.observations())
                    .append('/')
                    .append(latency.average())
                    .append('/')
                    .append(latency.p95())
                    .append('/')
                    .append(latency.p99())
                    .append('/')
                    .append(latency.maximum());
        }
        return summary.toString();
    }
}
