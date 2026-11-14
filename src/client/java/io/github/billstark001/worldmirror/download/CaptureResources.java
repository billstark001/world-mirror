package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Updates the capture budget from memory and outstanding work. */
final class CaptureResources {
    private static final CapturePolicy capturePolicy = new CapturePolicy();
    private static long nextResourceCheckMs;

    private CaptureResources() {}

    static void reset() {
        capturePolicy.reset();
        nextResourceCheckMs = Long.MIN_VALUE;
    }

    static CapturePolicy.Mode mode() {
        return capturePolicy.mode();
    }

    static void updateResourceState(Minecraft client, long nowMs) {
        if (nextResourceCheckMs != Long.MIN_VALUE && nowMs - nextResourceCheckMs < 0)
            return;
        nextResourceCheckMs = nowMs + 1_000L;
        var sample = DownloadManager.exportCoordinator.sampleResources();
        var previous = capturePolicy.mode();
        var state = capturePolicy.update(nowMs, sample);
        if (state == previous || !DownloadManager.isCaptureEnabled())
            return;

        if (state == CapturePolicy.Mode.NORMAL
                || (previous == CapturePolicy.Mode.PAUSED && state == CapturePolicy.Mode.REDUCED)) {
            DownloadCapture.queueLoadedScan(client, "resource-recovery");
        }
        String details = "source=" + WorldGraphService.instance().routing().source() + " state=" + previous
                + "->" + state + " reason=" + capturePolicy.reason() + " heapBytes=" + sample.usedHeap() + "/"
                + sample.maxHeap() + " dirty=" + sample.dirtyChunks()
                + " retainedChunks=" + sample.retainedChunks() + " frozen=" + sample.frozenBundles()
                + " inFlightChunks=" + sample.inFlightChunks()
                + " pendingCaptures=" + DownloadCapture.queue.metrics().pendingCaptures();
        if (state == CapturePolicy.Mode.NORMAL)
            WMLogger.info("Capture resource recovery " + details);
        else
            WMLogger.warn("Capture resource pressure " + details);

        if (client.player != null)
            try {
                String key = switch (state) {
                    case REDUCED -> "msg.worldmirror.capture.degraded";
                    case PAUSED -> "msg.worldmirror.capture.paused";
                    default -> "msg.worldmirror.capture.resumed";
                };
                Component cause = Component.translatable("msg.worldmirror.capture.reason."
                        + capturePolicy.reason().name().toLowerCase(java.util.Locale.ROOT));
                Component notice = Component.translatable(key, cause)
                                           .withStyle(state == CapturePolicy.Mode.NORMAL
                                                           ? net.minecraft.ChatFormatting.GREEN
                                                           : net.minecraft.ChatFormatting.YELLOW);
                WMPlayerMessages.sendSystemMessage(client.player, notice);
                WMPlayerMessages.showExportToast(
                        client, Component.translatable(DownloadManager.captureStatusKey()), cause);
            } catch (RuntimeException noticeFailure) {
                WMLogger.warnRateLimited("capture-resource-notice", 60_000L,
                        "Cannot display capture resource state; capture and export processing continue",
                        noticeFailure);
            }
    }

    static CaptureBudget currentBudget() {
        return CaptureBudget.forMode(capturePolicy.mode(), ModConfig.get().performance.captureBudgetMicros);
    }
}
