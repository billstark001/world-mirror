package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportSchedulerTest {
    @Test
    void captureReadinessAndNotificationAreIndependentRequestProperties() {
        var pending = new ExportScheduler.Pending();
        var source = new ExportWork.Source("server:request", "server");
        var quietScan = new ExportWork.Intent(new ExportWork.Context(ExportWork.Trigger.MANUAL, false, source),
                ExportWork.CapturePhase.READY, ExportWork.EntityScope.LIVE, true,
                java.util.Optional.empty(), io.github.billstark001.worldmirror.io.WorldSettingsSnapshot.defaults());
        pending.request(quietScan);
        assertEquals(null, pending.poll(true, 0));
        assertEquals(quietScan, pending.poll(false, 0));
        var notifiedExport = new ExportWork.Intent(new ExportWork.Context(ExportWork.Trigger.MANUAL, true, source),
                ExportWork.CapturePhase.READY, ExportWork.EntityScope.LIVE, false,
                java.util.Optional.empty(), quietScan.settings());
        pending.request(notifiedExport);
        assertEquals(notifiedExport, pending.poll(true, 0));
    }

    @Test
    void pressureAndFailedAttemptsUseOneScheduleWithoutConsumingAQuery() {
        var scheduler = new ExportScheduler();
        scheduler.reset(config(), 0);
        assertEquals(ExportWork.Trigger.RESOURCE_PRESSURE, scheduler.automatic(0, 1, true, true));
        assertEquals(ExportWork.Trigger.RESOURCE_PRESSURE, scheduler.automatic(500, 1, true, true));
        scheduler.attempted(ExportWork.Trigger.RESOURCE_PRESSURE, ExportScheduler.Dispatch.WAITING_SOURCE, 500);
        assertEquals(null, scheduler.automatic(1_000, 1, true, true));
        assertEquals(null, scheduler.automatic(1_500, 1, true, true));
        assertEquals(ExportWork.Trigger.RESOURCE_PRESSURE, scheduler.automatic(5_500, 1, true, true));
        scheduler.accepted(5_500, 1);
        scheduler.attempted(ExportWork.Trigger.RESOURCE_PRESSURE, ExportScheduler.Dispatch.STARTED, 5_500);
        assertEquals(null, scheduler.automatic(5_501, 1, true, true));
    }

    @Test
    void manualExportsResetPeriodicCadenceUsingTheSameMonotonicClock() {
        var scheduler = new ExportScheduler();
        scheduler.reset(config(), -20_000);
        scheduler.accepted(-10_000, 1);
        assertEquals(null, scheduler.automatic(19_999, 1, true, false));
        assertEquals(ExportWork.Trigger.PERIODIC, scheduler.automatic(20_000, 1, true, false));
    }

    @Test
    void stablePipelineExportsOnlyAtMaximumLatency() {
        ModConfig config = config();
        config.pipelineMode = ModConfig.DownloadPipelineMode.STABLE_PERIODIC;
        ExportScheduler pipeline = new ExportScheduler();
        pipeline.reset(config, 1_000L);

        assertEquals(null,
                pipeline.automatic(30_999L, 900, true, false));
        assertEquals(ExportWork.Trigger.PERIODIC,
                pipeline.automatic(31_000L, 1, true, false));
        assertEquals(null,
                pipeline.automatic(60_000L, 0, false, false));
    }

    @Test
    void adaptivePipelineUsesHighWatermarkAndHysteresis() {
        ModConfig config = config();
        config.pipelineMode = ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE;
        ExportScheduler pipeline = new ExportScheduler();
        pipeline.reset(config, 1_000L);

        assertEquals(ExportWork.Trigger.ADAPTIVE_HIGH_WATERMARK,
                pipeline.automatic(11_000L, 512, true, false));
        pipeline.accepted(11_000L, 512);
        assertEquals(null,
                pipeline.automatic(21_000L, 512, true, false));
        assertEquals(null,
                pipeline.automatic(21_000L, 256, true, false));
        assertEquals(ExportWork.Trigger.ADAPTIVE_HIGH_WATERMARK,
                pipeline.automatic(21_000L, 512, true, false));
    }

    @Test
    void adaptivePipelineKeepsMaximumLatencyCeiling() {
        ModConfig config = config();
        config.pipelineMode = ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE;
        ExportScheduler pipeline = new ExportScheduler();
        pipeline.reset(config, 5_000L);

        assertEquals(null,
                pipeline.automatic(34_999L, 1, true, false));
        assertEquals(ExportWork.Trigger.ADAPTIVE_MAX_LATENCY,
                pipeline.automatic(35_000L, 1, true, false));
    }

    private static ModConfig config() {
        ModConfig config = new ModConfig();
        config.syncIntervalSeconds = 30;
        config.performance.adaptiveDirtyHighWatermark = 512;
        config.performance.adaptiveExportCooldownSeconds = 10;
        return config;
    }
}
