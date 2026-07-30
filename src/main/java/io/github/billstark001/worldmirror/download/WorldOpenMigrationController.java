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
        if (BYPASS_ONCE.remove(worldFolder)) return false;

        MirrorMigrationPlan.Inspection plan = MirrorMigrationCoordinator.inspect(worldFolder);
        // Respect the normal entry path for current, foreign, unreadable, and
        // future saves.  Entry confirmation is reserved for schema-outdated
        // mirrors as requested; download-time operations still protect writes.
        if (plan.state() != MirrorMigrationPlan.State.OUTDATED) return false;

        // Vanilla has already performed any game-version migration when this
        // hook runs.  Release its access before our offline writer starts, and
        // also before the player can cancel back to the world list.
        closeWorldAccess.run();

        Minecraft client = Minecraft.getInstance();
        Runnable reopen = () -> {
            BYPASS_ONCE.add(worldFolder);
            client.execute(() -> flows.openWorld(levelId, reloadCallback));
        };
        ClientDialogs.upgrade(client, new MirrorPrompt.Upgrade(
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.title"),
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.entryBody", worldFolder.getFileName()),
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.entryConfirm")),
                () -> migrateThenReopen(client, worldFolder, reopen, reloadCallback),
                reopen,
                reloadCallback);
        return true;
    }

    private static void migrateThenReopen(Minecraft client, Path worldFolder,
                                          Runnable reopen, Runnable reloadCallback) {
        MirrorPrompt.ProgressHandle progress = ClientDialogs.progress(client,
                new MirrorPrompt.Text("screen.worldmirror.upgrade.progressTitle"));
        Thread worker = new Thread(() -> {
            client.execute(() -> progress.stage(
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.progressBackup")));
            MirrorMigrationCoordinator.Result result = MirrorMigrationCoordinator.migrateApproved(worldFolder);
            client.execute(() -> {
                progress.close();
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
            });
        }, "WM-OpenMirrorMigration");
        worker.setDaemon(false);
        worker.start();
    }

    private static Path resolveSave(String levelId) {
        if (levelId == null || levelId.isBlank()) return null;
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves").toAbsolutePath().normalize();
        Path save = saves.resolve(levelId).normalize();
        return save.startsWith(saves) ? save : null;
    }
}
