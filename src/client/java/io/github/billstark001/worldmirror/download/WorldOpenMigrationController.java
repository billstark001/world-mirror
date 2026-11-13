package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import io.github.billstark001.worldmirror.legacy.LegacyWorldMetadata;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates an approved offline upgrade after vanilla finishes its own checks. */
public final class WorldOpenMigrationController {
    private static final Set<Path> BYPASS_ONCE = ConcurrentHashMap.newKeySet();

    private WorldOpenMigrationController() {}

    /** @return true when vanilla opening was deferred for a prompt or migration. */
    public static boolean intercept(
            WorldOpenFlows flows, String levelId, Runnable reloadCallback, Runnable closeWorldAccess) {
        Path worldFolder = resolveSave(levelId);
        if (worldFolder == null)
            return false;
        boolean recovery = hasPendingOperation(worldFolder);
        if (BYPASS_ONCE.remove(worldFolder) && !recovery)
            return false;
        if (!recovery
                && Files.notExists(worldFolder.resolve(WorldMetadata.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(
                        worldFolder.resolve(LocalWorldIdentity.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        // Decoding bounded NBT can still take time: release vanilla's access and inspect off-thread.
        closeWorldAccess.run();
        Minecraft client = Minecraft.getInstance();
        MirrorPrompt.ProgressHandle progress =
                ClientDialogs.progress(client, new MirrorPrompt.Text("screen.worldmirror.upgrade.checking"));
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-InspectMirror",
                ()
                        -> {
                    var service = WorldGraphService.instance();
                    MirrorMigrationPlan.Inspection inspection =
                            MirrorMigrationCoordinator.recoverAndInspect(worldFolder);
                    if (LegacyWorldMetadata.hasLegacyHeader(worldFolder)
                            || hasRelocationJournal(worldFolder)) {
                        service.refresh();
                        inspection = MirrorMigrationCoordinator.recoverAndInspect(worldFolder);
                    }
                    if (hasRelocationJournal(worldFolder)) {
                        throw new IOException("Mirror relocation still requires recovery: " + worldFolder);
                    }
                    var graph = service.graph();
                    var location = new WorldGraph.Location(
                            WorldGraph.Root.SAVES, worldFolder.getFileName().toString());
                    boolean ambiguous = graph.duplicates().stream().anyMatch(duplicate
                            -> location.equals(duplicate.copy().location())
                                    || location.equals(duplicate.original().location()));
                    if (ambiguous) {
                        service.refresh();
                        if (service.graph().duplicates().stream().anyMatch(duplicate
                                    -> location.equals(duplicate.copy().location())
                                            || location.equals(duplicate.original().location()))) {
                            throw new IOException("Save identity is still ambiguous: " + worldFolder);
                        }
                        inspection = MirrorMigrationCoordinator.recoverAndInspect(worldFolder);
                    }
                    return inspection;
                },
                failure
                -> {
                    OperationFailures.report(worldFolder.toString(), "worldgen_check", worldFolder, failure);
                    return null;
                },
                client::execute,
                plan
                -> showDecision(client, flows, levelId, worldFolder, reloadCallback, plan),
                progress::close);
        return true;
    }

    private static void showDecision(Minecraft client, WorldOpenFlows flows, String levelId, Path worldFolder,
            Runnable reloadCallback, MirrorMigrationPlan.Inspection plan) {
        Runnable reopen = () -> {
            BYPASS_ONCE.add(worldFolder);
            client.execute(() -> flows.openWorld(levelId, reloadCallback));
        };
        if (plan == null || plan.state() == MirrorMigrationPlan.State.UNREADABLE) {
            ClientDialogs.alert(client,
                    new MirrorPrompt.Alert(
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                            plan != null && plan.problem() != null
                                    ? new MirrorPrompt.Text("screen.worldmirror.repair.unavailableBody")
                                    : new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody",
                                              "inspection_failed"),
                            new MirrorPrompt.Text("gui.done")),
                    reloadCallback);
        } else if (plan.state() == MirrorMigrationPlan.State.FUTURE) {
            ClientDialogs.alert(client,
                    new MirrorPrompt.Alert(new MirrorPrompt.Text("screen.worldmirror.upgrade.futureTitle"),
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.futureBody"),
                            new MirrorPrompt.Text("gui.done")),
                    reloadCallback);
        } else if (!plan.requiresApproval()) {
            reopen.run();
        } else {
            var metadata = plan.metadata();
            var target = new MirrorTarget(worldFolder, metadata.nodeId, metadata.sourceId);
            Runnable upgrade = () -> migrateThenReopen(client, target, reopen, reloadCallback);
            if (plan.repairWorldgen()) {
                ClientDialogs.confirm(client,
                        new MirrorPrompt.Confirmation(
                                new MirrorPrompt.Text("screen.worldmirror.repair.title"),
                                new MirrorPrompt.Text(
                                        "screen.worldmirror.repair.entryBody", worldFolder.getFileName()),
                                new MirrorPrompt.Text("screen.worldmirror.repair.entryConfirm"),
                                new MirrorPrompt.Text("gui.cancel")),
                        upgrade, reloadCallback);
            } else {
                ClientDialogs.upgrade(client,
                        new MirrorPrompt.Upgrade(new MirrorPrompt.Text("screen.worldmirror.upgrade.title"),
                                new MirrorPrompt.Text(
                                        "screen.worldmirror.upgrade.entryBody", worldFolder.getFileName()),
                                new MirrorPrompt.Text("screen.worldmirror.upgrade.entryConfirm")),
                        upgrade, reopen, reloadCallback);
            }
        }
    }

    private static void migrateThenReopen(
            Minecraft client, MirrorTarget target, Runnable reopen, Runnable reloadCallback) {
        MirrorPrompt.ProgressHandle progress = ClientDialogs.progress(
                client, new MirrorPrompt.Text("screen.worldmirror.upgrade.progressTitle"));
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-OpenMirrorMigration",
                ()
                        -> MirrorMigrationCoordinator.migrateApproved(
                                target, new MirrorMigrationProgress(client, progress)),
                failure
                -> MirrorMigrationCoordinator.Result.failure("unexpected_failure"),
                client::execute, result -> {
                    if (result.success()) {
                        ClientDialogs.toast(client,
                                new MirrorPrompt.Text("screen.worldmirror.upgrade.completeTitle"),
                                new MirrorPrompt.Text("screen.worldmirror.upgrade.completeBody"));
                        reopen.run();
                        return;
                    }
                    ClientDialogs.alert(client,
                            new MirrorPrompt.Alert(
                                    new MirrorPrompt.Text("screen.worldmirror.upgrade.failedTitle"),
                                    new MirrorPrompt.Text(
                                            "screen.worldmirror.upgrade.failedBody", result.failure()),
                                    new MirrorPrompt.Text("gui.done")),
                            reloadCallback);
                }, progress::close);
    }

    private static Path resolveSave(String levelId) {
        if (levelId == null || levelId.isBlank())
            return null;
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves").toAbsolutePath().normalize();
        Path save = saves.resolve(levelId).normalize();
        return save.startsWith(saves) ? save : null;
    }

    private static boolean hasPendingOperation(Path worldFolder) {
        try {
            return hasRelocationJournal(worldFolder) || MirrorMigrationCoordinator.needsRecovery(worldFolder);
        } catch (IOException unavailable) {
            // Check again with diagnostics on the offline worker.
            return true;
        }
    }

    private static boolean hasRelocationJournal(Path worldFolder) {
        return !Files.notExists(worldFolder.resolve(WorldRelocation.JOURNAL), LinkOption.NOFOLLOW_LINKS);
    }
}
