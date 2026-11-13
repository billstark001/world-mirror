package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;

/** Emits the effective configuration and current target at diagnostic startup. */
final class DownloadConfigDiagnostics {
    private DownloadConfigDiagnostics() {}

    static void log(Minecraft client) {
        ModConfig config = ModConfig.get();
        var graph = WorldGraphService.instance();
        var target = DownloadManager.captureTarget();
        WMLogger.info("[perf] config.global=" + globalJson(config));
        WMLogger.info("[perf] config.local="
                + localJson(target,
                        target == null ? null
                                       : graph.conflictStrategy(target.nodeId(), target.parentNodeId())));
    }

    static String globalJson(ModConfig config) {
        return JsonSupport.toCompactJsonWithNulls(config);
    }

    static String localJson(WorldGraphService.Target target, ModConfig.ConflictStrategy strategy) {
        return JsonSupport.toCompactJsonWithNulls(new LocalConfigSnapshot(
                target == null ? null : target.nodeId(), target == null ? null : target.parentNodeId(),
                target == null ? null : target.sourceId(), target == null ? null : target.folder().toString(),
                strategy));
    }

    private record LocalConfigSnapshot(String nodeId, String parentNodeId, String indexSourceId,
            String output, ModConfig.ConflictStrategy conflictStrategy) {}
}
