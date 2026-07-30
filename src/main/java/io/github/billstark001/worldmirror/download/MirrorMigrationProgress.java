package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.minecraft.client.Minecraft;

/** Bridges background migration work to the native client progress screen. */
final class MirrorMigrationProgress implements MirrorMigrationCoordinator.ProgressListener {
    private final Minecraft client;
    private final MirrorPrompt.ProgressHandle handle;
    private MirrorMigrationCoordinator.Phase lastPhase;
    private int lastPercent = -1;

    MirrorMigrationProgress(Minecraft client, MirrorPrompt.ProgressHandle handle) {
        this.client = client;
        this.handle = handle;
    }

    @Override
    public synchronized void update(MirrorMigrationCoordinator.Phase phase, int completed, int total) {
        int percent = total <= 0 ? 100 : Math.clamp(completed * 100 / total, 0, 100);
        if (phase == lastPhase && percent == lastPercent) return;
        lastPhase = phase;
        lastPercent = percent;
        MirrorPrompt.Text text = new MirrorPrompt.Text(switch (phase) {
            case SCANNING_VOID_CHUNKS -> "screen.worldmirror.upgrade.progressScan";
            case BACKING_UP -> "screen.worldmirror.upgrade.progressBackup";
            case WRITING_WORLD_DATA -> "screen.worldmirror.upgrade.progressWrite";
            case REMOVING_VOID_CHUNKS -> "screen.worldmirror.upgrade.progressCleanup";
        });
        client.execute(() -> {
            handle.stage(text);
            handle.progress(completed, total);
        });
    }
}
