package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/** Collects opt-in download telemetry for the active capture session. */
public final class DownloadPerformanceDiagnostics {
    private DownloadPerformanceDiagnostics() {}

    private static final LatencyWindow worldFrameIntervals = new LatencyWindow(8192);
    private static final LatencyWindow worldMirrorTickWork = new LatencyWindow(4096);
    private static volatile long lastDiagnosticLogMs;
    private static volatile long diagnosticSessionStartedMs;
    private static volatile boolean diagnosticSessionActive;
    private static volatile boolean recordPerformanceTimings;
    private static volatile long lastWorldFrameNs;
    private static final Map<String, GcSnapshot> lastGcByCollector = new HashMap<>();

    private record GcSnapshot(long count, long timeMs) {}
    static boolean isRecording() {
        return recordPerformanceTimings;
    }

    static void beginSession(Minecraft client) {
        resetDiagnosticSession();
        diagnosticSessionActive = ModConfig.get().performance.diagnosticPerformanceLogging;
        recordPerformanceTimings = diagnosticSessionActive;
        if (diagnosticSessionActive)
            DownloadConfigDiagnostics.log(client);
    }

    static void beginTick() {
        boolean recording = DownloadManager.isCaptureEnabled()
                && ModConfig.get().performance.diagnosticPerformanceLogging;
        if (recording && !diagnosticSessionActive)
            resetDiagnosticSession();
        diagnosticSessionActive = recording;
        recordPerformanceTimings = recording;
    }

    static void endTick(long startedNs) {
        if (recordPerformanceTimings)
            worldMirrorTickWork.record((System.nanoTime() - startedNs) / 1_000L);
    }

    /** Records gameplay frame spacing without doing work when diagnostics are disabled. */
    public static void recordWorldFrame() {
        if (!recordPerformanceTimings || !DownloadManager.isCaptureEnabled()) {
            lastWorldFrameNs = 0L;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.isPaused()) {
            lastWorldFrameNs = 0L;
            return;
        }
        long nowNs = System.nanoTime();
        long previousNs = lastWorldFrameNs;
        lastWorldFrameNs = nowNs;
        if (previousNs > 0L)
            worldFrameIntervals.record((nowNs - previousNs) / 1_000L);
    }

    private static void resetDiagnosticSession() {
        diagnosticSessionStartedMs = System.currentTimeMillis();
        lastDiagnosticLogMs = diagnosticSessionStartedMs;
        DownloadCapture.queue.diagnostics.reset();
        DownloadManager.exportCoordinator.resetDiagnostics();
        worldFrameIntervals.reset();
        worldMirrorTickWork.reset();
        lastWorldFrameNs = 0L;
        lastGcByCollector.clear();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            lastGcByCollector.put(bean.getName(),
                    new GcSnapshot(
                            Math.max(0L, bean.getCollectionCount()), Math.max(0L, bean.getCollectionTime())));
        }
    }

    static void maybeLogPerformanceSnapshot(long nowMs) {
        if (!ModConfig.get().performance.diagnosticPerformanceLogging
                || nowMs - lastDiagnosticLogMs < 30_000L)
            return;
        lastDiagnosticLogMs = nowMs;
        DownloadCaptureQueue.Metrics queued = DownloadCapture.queue.metrics();
        Runtime runtime = Runtime.getRuntime();
        long usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long committedMiB = runtime.totalMemory() / (1024L * 1024L);
        long gcCount = 0L;
        long gcTimeMs = 0L;
        StringBuilder collectors = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collectors.length() > 0)
                collectors.append('|');
            long currentCount = Math.max(0L, bean.getCollectionCount());
            long currentTimeMs = Math.max(0L, bean.getCollectionTime());
            GcSnapshot previous = lastGcByCollector.get(bean.getName());
            long countDelta = previous == null ? 0L : Math.max(0L, currentCount - previous.count());
            long timeDelta = previous == null ? 0L : Math.max(0L, currentTimeMs - previous.timeMs());
            gcCount += countDelta;
            gcTimeMs += timeDelta;
            collectors.append(bean.getName().replace(' ', '_'))
                    .append(':')
                    .append(countDelta)
                    .append('/')
                    .append(timeDelta);
            lastGcByCollector.put(bean.getName(), new GcSnapshot(currentCount, currentTimeMs));
        }
        CaptureQueueDiagnostics.DiagnosticSnapshot capture = DownloadCapture.queue.diagnostics.snapshot();
        LatencyWindow.Summary captureLatency = capture.captureTickLatency();
        LatencyWindow.Summary unloadLatency = capture.unloadLatency();
        LatencyWindow.Summary frameInterval = worldFrameIntervals.snapshotAndReset();
        LatencyWindow.Summary tickWork = worldMirrorTickWork.snapshotAndReset();
        DownloadExportCoordinator.DiagnosticSnapshot export =
                DownloadManager.exportCoordinator.snapshotDiagnostics();
        ChunkExporter.ExportTimings timings = export.timings();
        WMLogger.info("[perf] pipeline=" + DownloadManager.exportScheduler.mode() + " captureState="
                + CaptureResources.mode() + " sessionMs=" + Math.max(0L, nowMs - diagnosticSessionStartedMs)
                + " cache=" + ChunkListener.getTotalCount() + " dirty=" + ChunkListener.getDirtyCount()
                + " captureQueue=" + queued.pendingCaptures() + " oldestHintMs=" + queued.oldestCaptureAgeMs()
                + " coalesced=" + queued.coalescedHints() + " dropped=" + queued.droppedHints()
                + " hintReasons=" + capture.hintSummary() + " captureReasonLatency="
                + capture.reasonLatencySummary() + " captureTickCount=" + captureLatency.observations()
                + " captureTickAvgUs=" + captureLatency.average() + " captureTickP95Us="
                + captureLatency.p95() + " captureTickP99Us=" + captureLatency.p99() + " captureTickMaxUs="
                + captureLatency.maximum() + " captureProcessed=" + capture.processed() + " captureCompleted="
                + capture.completed() + " captureBudgetUs=" + ModConfig.get().performance.captureBudgetMicros
                + " captureBudgetOverruns=" + capture.budgetOverruns() + " captureBudgetMaxOverrunUs="
                + capture.maximumBudgetOverrunUs() + " unloadCaptureCount=" + unloadLatency.observations()
                + " unloadCaptureAvgUs=" + unloadLatency.average() + " unloadCaptureP95Us="
                + unloadLatency.p95() + " unloadCaptureP99Us=" + unloadLatency.p99() + " unloadCaptureMaxUs="
                + unloadLatency.maximum() + " unloadCaptureFailures=" + capture.unloadFailures()
                + " frameCount=" + frameInterval.observations() + " frameAvgUs=" + frameInterval.average()
                + " frameP95Us=" + frameInterval.p95() + " frameP99Us=" + frameInterval.p99()
                + " frameMaxUs=" + frameInterval.maximum() + " wmTickCount=" + tickWork.observations()
                + " wmTickAvgUs=" + tickWork.average() + " wmTickP95Us=" + tickWork.p95()
                + " wmTickP99Us=" + tickWork.p99() + " wmTickMaxUs=" + tickWork.maximum()
                + " automaticExportSuppressed=" + export.automaticSuppressed() + " deferredExportCoalesced="
                + export.deferredCoalesced() + " exportWorkerDutyMs=" + export.workerWallMillis()
                + " lastExportMs=" + export.lastExportMillis() + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs() + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs() + " regionFlushMs="
                + timings.flushMs() + " regionVerifyMs=" + timings.verificationMs() + " entityWriteMs="
                + timings.entityMs() + " dbCommitMs=" + export.durabilityIndexMillis() + " written="
                + export.written() + " settled=" + export.settled() + " unreadable=" + export.unreadable()
                + " failures=" + export.failures() + " heapMiB=" + usedMiB + "/" + committedMiB
                + " gcCountDelta=" + gcCount + " gcTimeMsDelta=" + gcTimeMs + " gcCollectors=" + collectors);
    }
}
