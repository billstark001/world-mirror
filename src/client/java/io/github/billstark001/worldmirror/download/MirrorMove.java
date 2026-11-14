package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** Moves the selected download target from the in-game settings workflow. */
public final class MirrorMove {
    private MirrorMove() {}

    public static void move(Minecraft client, MirrorTarget expected, ModConfig.SaveLocation targetLocation,
            java.util.function.Consumer<MirrorMoveResult> completed) {
        if (DownloadManager.isCaptureEnabled()) {
            completed.accept(MirrorMoveResult.failure("download_active"));
            return;
        }
        if (DownloadManager.isExportInProgress()) {
            completed.accept(MirrorMoveResult.failure("export_in_progress"));
            return;
        }
        var graph = WorldGraphService.instance();
        var target = graph.targetById(expected.nodeId());
        if (target == null || !graph.routing().observationIsCurrent(client)
                || !expected.equals(new MirrorTarget(target.folder(), target.nodeId(), target.sourceId()))) {
            completed.accept(MirrorMoveResult.failure("source_missing"));
            return;
        }
        OfflineTasks.start("WM-MoveMirror",
                ()
                        -> {
                    var result = graph.worlds().move(target, targetLocation);
                    return new MirrorMoveResult(true, null, result.source(), result.target());
                },
                failure
                -> {
                    OperationFailures.report(target.nodeId(), "relocation", target.folder(), failure);
                    return MirrorMoveResult.failure("io_error");
                },
                client::execute, completed, () -> {});
    }

    public record MirrorMoveResult(boolean success, String failureCode, Path source, Path target) {
        private static MirrorMoveResult failure(String failureCode) {
            return new MirrorMoveResult(false, failureCode, null, null);
        }
    }
}
