package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates an approved offline upgrade after vanilla finishes its own checks. */
public final class WorldOpenMigrationController {
    private static final Set<Path> BYPASS_ONCE = ConcurrentHashMap.newKeySet();

    private WorldOpenMigrationController() {}

    /** @return true when vanilla opening was deferred for a prompt or migration. */
    public static boolean intercept(WorldOpenFlows flows, String levelId, Runnable reloadCallback,
                                    Runnable closeWorldAccess) {
        Path worldFolder = resolveSave(levelId);
        if (worldFolder == null) return false;
        boolean recovery = io.github.billstark001.worldmirror.util.WorldRecovery.required(worldFolder);
        if (BYPASS_ONCE.remove(worldFolder) && !recovery) return false;
        if (!recovery && !java.nio.file.Files.isRegularFile(worldFolder.resolve(WorldMetadata.FILE_NAME))) {
            return false;
        }
        // Decoding bounded NBT can still take time: release vanilla's access and inspect off-thread.
        closeWorldAccess.run();
        Minecraft client = Minecraft.getInstance();
        MirrorPrompt.ProgressHandle progress = ClientDialogs.progress(client,
                new MirrorPrompt.Text("screen.worldmirror.upgrade.checking"));
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-InspectMirror",
                () -> MirrorMigrationCoordinator.recoverAndInspect(worldFolder), failure -> {
            OperationFailures.report(worldFolder.toString(), "worldgen_check", worldFolder, failure);
            return null;
        }, client::execute, plan -> showDecision(client, flows, levelId, worldFolder, reloadCallback, plan),
                progress::close);
        return true;
    }

    private static void showDecision(Minecraft client, WorldOpenFlows flows, String levelId,
                                     Path worldFolder, Runnable reloadCallback,
                                     MirrorMigrationPlan.Inspection plan) {
        Runnable reopen = () -> {
            BYPASS_ONCE.add(worldFolder);
            client.execute(() -> flows.openWorld(levelId, reloadCallback));
        };
        if (plan == null || plan.state() == MirrorMigrationPlan.State.UNREADABLE) {
            ClientDialogs.alert(client, new MirrorPrompt.Alert(
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                    plan != null && plan.problem() != null
                            ? new MirrorPrompt.Text("screen.worldmirror.repair.unavailableBody")
                            : new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody", "inspection_failed"),
                    new MirrorPrompt.Text("gui.done")), reloadCallback);
        } else if (plan.state() == MirrorMigrationPlan.State.FUTURE && plan.problem() != null) {
            ClientDialogs.alert(client, new MirrorPrompt.Alert(
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.futureTitle"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.futureBody"),
                    new MirrorPrompt.Text("gui.done")), reloadCallback);
        } else if (!plan.requiresApproval()) {
            reopen.run();
        } else if (plan.repairWorldgen()) {
            ClientDialogs.confirm(client, new MirrorPrompt.Confirmation(
                    new MirrorPrompt.Text("screen.worldmirror.repair.title"),
                    new MirrorPrompt.Text("screen.worldmirror.repair.entryBody", worldFolder.getFileName()),
                    new MirrorPrompt.Text("screen.worldmirror.repair.entryConfirm"),
                    new MirrorPrompt.Text("gui.cancel")),
                    () -> migrateThenReopen(client, worldFolder, reopen, reloadCallback), reloadCallback);
        } else {
            ClientDialogs.upgrade(client, new MirrorPrompt.Upgrade(
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.title"),
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.entryBody", worldFolder.getFileName()),
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.entryConfirm")),
                    () -> migrateThenReopen(client, worldFolder, reopen, reloadCallback), reopen, reloadCallback);
        }
    }

    private static void migrateThenReopen(Minecraft client, Path worldFolder,
                                          Runnable reopen, Runnable reloadCallback) {
        MirrorPrompt.ProgressHandle progress = ClientDialogs.progress(client,
                new MirrorPrompt.Text("screen.worldmirror.upgrade.progressTitle"));
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-OpenMirrorMigration",
                () -> MirrorMigrationCoordinator.migrateApproved(worldFolder,
                        new MirrorMigrationProgress(client, progress)),
                failure -> MirrorMigrationCoordinator.Result.failure("unexpected_failure"),
                client::execute, result -> {
                if (result.success()) {
                    ClientDialogs.toast(client,
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.completeTitle"),
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.completeBody"));
                    reopen.run();
                    return;
                }
                ClientDialogs.alert(client, new MirrorPrompt.Alert(
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.failedTitle"),
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.failedBody", result.failure()),
                        new MirrorPrompt.Text("gui.done")), reloadCallback);

                }, progress::close);
    }

    private static Path resolveSave(String levelId) {
        if (levelId == null || levelId.isBlank()) return null;
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves").toAbsolutePath().normalize();
        Path save = saves.resolve(levelId).normalize();
        return save.startsWith(saves) ? save : null;
    }
}
