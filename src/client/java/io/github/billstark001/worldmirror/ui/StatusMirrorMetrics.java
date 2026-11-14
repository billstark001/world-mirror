package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.download.OperationFailures;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.Objects;

/** Polls the current mirror's persistent status off the client thread. */
final class StatusMirrorMetrics {
    private final StatusScreen screen;
    private long lastSyncTime;
    private long nextStatusReadMs;
    private int conflictCount;
    private boolean readingStatus;
    private MirrorTarget statusReadTarget;

    StatusMirrorMetrics(StatusScreen screen) {
        this.screen = screen;
    }

    int conflictCount() {
        return conflictCount;
    }

    void requestRefresh() {
        nextStatusReadMs = 0L;
    }

    private record PersistentStatus(long lastSyncTime, int conflicts) {}

    void updateTarget(MirrorTarget target) {
        if (!Objects.equals(target, statusReadTarget)) {
            statusReadTarget = target;
            lastSyncTime = target == null ? 0L : -1L;
            conflictCount = target == null ? 0 : -1;
            nextStatusReadMs = 0L;
        }
        poll();
    }

    void poll() {
        MirrorTarget target = statusReadTarget;
        long now = System.nanoTime() / 1_000_000L;
        if (target == null || readingStatus || (nextStatusReadMs != 0L && now - nextStatusReadMs < 0L)
                || screen.hasSelectionPopup())
            return;
        var client = Minecraft.getInstance();
        boolean readConflicts = screen.isConflictsTab();
        readingStatus = true;
        nextStatusReadMs = now + (readConflicts ? 2_000L : 1_000L);
        OfflineTasks.start("WM-ReadMirrorStatus",
                ()
                        -> {
                    var metadata = WorldMetadata.loadIfPresent(target.folder()).filter(target::matches);
                    if (metadata.isEmpty()) {
                        boolean exists =
                                Files.isRegularFile(target.folder().resolve(WorldMetadata.FILE_NAME));
                        return new PersistentStatus(exists ? -1L : 0L, exists ? -1 : 0);
                    }
                    int count = -1;
                    if (readConflicts) {
                        count = ConflictManager.countAllConflicts(target.folder());
                        if (!target.matchesSavedIdentity())
                            return new PersistentStatus(-1L, -1);
                        OperationFailures.recovered(target.nodeId(), "conflict_read");
                    }
                    return new PersistentStatus(metadata.get().lastSyncTime, count);
                },
                failure
                -> {
                    OperationFailures.report(target.nodeId(), "conflict_read", target.folder(), failure);
                    return new PersistentStatus(-1L, -1);
                },
                client::execute,
                status
                -> {
                    if (ClientDialogs.currentScreen(client) != screen || !target.equals(statusReadTarget))
                        return;
                    lastSyncTime = status.lastSyncTime();
                    if (readConflicts && screen.isConflictsTab() && !screen.hasSelectionPopup()) {
                        boolean changedActions = (conflictCount > 0) != (status.conflicts() > 0);
                        conflictCount = status.conflicts();
                        if (changedActions)
                            screen.refresh();
                    }
                },
                () -> readingStatus = false);
    }

    Component lastSync() {
        if (lastSyncTime < 0L)
            return Component.literal("?");
        if (lastSyncTime == 0L) {
            return Component.translatable("screen.worldmirror.status.lastSyncNever");
        }
        return formatAge((System.currentTimeMillis() - lastSyncTime) / 1_000L);
    }

    private static Component formatAge(long seconds) {
        if (seconds < 0)
            seconds = 0;
        if (seconds < 60) {
            return Component.translatable("screen.worldmirror.status.age.seconds", seconds);
        }
        if (seconds < 3_600) {
            return Component.translatable("screen.worldmirror.status.age.minutes", seconds / 60);
        }
        return Component.translatable("screen.worldmirror.status.age.hours", seconds / 3_600);
    }
}
