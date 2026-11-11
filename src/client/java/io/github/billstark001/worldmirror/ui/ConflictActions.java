package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.download.MirrorMapping;
import io.github.billstark001.worldmirror.download.OperationFailures;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.io.IOException;

/** One background boundary for both map-cell and bulk conflict decisions. */
public final class ConflictActions {
    private ConflictActions() {}

    public static boolean canResolve(MirrorTarget target) {
        if (MirrorMapping.getInstance().isAvailable()) return true;
        var current = io.github.billstark001.worldmirror.download.MirrorWorldContext.current();
        return current.isMirror() && target.folder().equals(current.worldFolder())
                && target.sourceId().equals(current.sourceId());
    }

    public static void resolve(MirrorTarget target, ChunkPos chunk, ResourceKey<Level> dimension,
                               boolean overwrite, Runnable completed) {
        submit(target, () -> ConflictManager.resolveConflict(target, chunk, dimension, overwrite), completed);
    }

    public static void resolveAll(MirrorTarget target, boolean overwrite, Runnable completed) {
        submit(target, () -> ConflictManager.clearAllConflicts(target, overwrite), completed);
    }

    private static void submit(MirrorTarget target, OfflineTasks.Work<Boolean> work, Runnable completed) {
        OfflineTasks.start("WM-ResolveConflicts", () -> {
            if (!canResolve(target)) throw new IOException("Mirror routing is unavailable");
            boolean success = work.run();
            if (!success) throw new IOException("Some conflict decisions failed; pending entries retained");
            OperationFailures.recovered(target.sourceId(), "conflict_resolution");
            return true;
        }, failure -> {
            OperationFailures.report(target.sourceId(), "conflict_resolution", target.folder(), failure);
            return false;
        }, Minecraft.getInstance()::execute, result -> {
            ChunkStatusCache.invalidate();
            completed.run();
        }, () -> {});
    }
}
