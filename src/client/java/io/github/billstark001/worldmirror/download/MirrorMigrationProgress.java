package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.minecraft.client.Minecraft;

import java.util.function.Function;

/** Bridges background migration work to the native client progress screen. */
final class MirrorMigrationProgress<P extends Enum<P>> {
    private final Minecraft client;
    private final MirrorPrompt.ProgressHandle handle;
    private final Function<P, String> translationKey;
    private P lastPhase;
    private int lastPercent = -1;

    private MirrorMigrationProgress(
            Minecraft client, MirrorPrompt.ProgressHandle handle, Function<P, String> translationKey) {
        this.client = client;
        this.handle = handle;
        this.translationKey = translationKey;
    }

    static MirrorMigrationCoordinator.ProgressListener worldgen(
            Minecraft client, MirrorPrompt.ProgressHandle handle) {
        return new MirrorMigrationProgress<MirrorMigrationCoordinator.Phase>(
                client, handle, phase -> switch (phase) {
                    case SCANNING_VOID_CHUNKS -> "screen.worldmirror.upgrade.progressScan";
                    case BACKING_UP -> "screen.worldmirror.upgrade.progressBackup";
                    case WRITING_WORLD_DATA -> "screen.worldmirror.upgrade.progressWrite";
                    case REMOVING_VOID_CHUNKS -> "screen.worldmirror.upgrade.progressCleanup";
                })::update;
    }

    static SeededTerrainRepair.Progress seedTerrain(Minecraft client, MirrorPrompt.ProgressHandle handle) {
        return new MirrorMigrationProgress<SeededTerrainRepair.Phase>(
                client, handle, phase -> switch (phase) {
                    case READING_BACKUP -> "screen.worldmirror.seed.repairRead";
                    case SCANNING -> "screen.worldmirror.seed.repairScan";
                    case STAGING -> "screen.worldmirror.seed.repairStage";
                    case PUBLISHING -> "screen.worldmirror.seed.repairPublish";
                })::update;
    }

    private synchronized void update(P phase, int completed, int total) {
        int percent = total <= 0 ? 0 : Math.clamp((int) ((long) completed * 100 / total), 0, 100);
        boolean stageChanged = phase != lastPhase;
        if (!stageChanged && percent == lastPercent)
            return;
        lastPhase = phase;
        lastPercent = percent;
        client.execute(() -> {
            if (!handle.isCurrent())
                return;
            if (stageChanged)
                handle.stage(new MirrorPrompt.Text(translationKey.apply(phase)));
            handle.progress(percent, 100);
        });
    }
}
