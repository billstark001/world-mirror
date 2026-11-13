package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import io.github.billstark001.worldmirror.legacy.LegacyWorldMetadata;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/** Coordinates an approved offline upgrade after vanilla finishes its own checks. */
public final class WorldOpenMigrationController {
    private WorldOpenMigrationController() {}

    /** @return true when vanilla opening was deferred for a prompt or migration. */
    public static boolean intercept(WorldOpenFlows flows, Path folder, String levelId,
            Runnable reloadCallback, Runnable closeWorldAccess, Runnable resumeStem) {
        Path worldFolder = folder.toAbsolutePath().normalize();
        boolean recovery = hasPendingOperation(worldFolder);
        if (reloadCallback instanceof Retry retry && retry.consume(worldFolder) && !recovery)
            return false;
        if (!recovery
                && Files.notExists(worldFolder.resolve(WorldMetadata.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(
                        worldFolder.resolve(LocalWorldIdentity.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        Runnable reopen =
                () -> client.schedule(() -> flows.openWorld(levelId, new Retry(worldFolder, reloadCallback)));
        inspect(client, worldFolder, "WM-InspectMirror",
                ()
                        -> {
                    // With vanilla's lease held, no offline World Mirror operation can change the save.
                    if (recovery || LegacyWorldMetadata.hasLegacyHeader(worldFolder)
                            || ambiguous(worldFolder))
                        return new OpenCheck(null, true);
                    return new OpenCheck(MirrorMigrationCoordinator.inspect(worldFolder), false);
                },
                check
                -> {
                    if (check != null && check.needsRecovery()) {
                        closeWorldAccess.run();
                        recover(client, worldFolder, reloadCallback, reopen);
                    } else if (check != null && canResume(check.plan())) {
                        resumeStem.run();
                    } else {
                        closeWorldAccess.run();
                        showDecision(client, worldFolder, reloadCallback, reopen,
                                check == null ? null : check.plan());
                    }
                },
                closeWorldAccess);
        return true;
    }

    private record OpenCheck(MirrorMigrationPlan.Inspection plan, boolean needsRecovery) {}

    @FunctionalInterface
    private interface ProgressWork<T> {
        T run(MirrorPrompt.ProgressHandle progress) throws Exception;
    }

    /** The native pipeline carries this callback even when its file fixer runs asynchronously. */
    private static final class Retry implements Runnable {
        private final Path world;
        private final Runnable reload;
        private boolean consumed;

        Retry(Path world, Runnable reload) {
            this.world = world;
            this.reload = reload instanceof Retry retry ? retry.reload : reload;
        }

        boolean consume(Path folder) {
            if (consumed || !world.equals(folder))
                return false;
            consumed = true;
            return true;
        }

        @Override
        public void run() {
            reload.run();
        }
    }

    private static boolean canResume(MirrorMigrationPlan.Inspection plan) {
        return plan != null && switch (plan.state()) {
            case NEW, UNMANAGED, CURRENT -> true;
            default -> false;
        };
    }

    private static boolean ambiguous(Path folder) {
        var location = new WorldGraph.Location(WorldGraph.Root.SAVES, folder.getFileName().toString());
        return WorldGraphService.instance().graph().duplicates().stream().anyMatch(duplicate
                -> location.equals(duplicate.copy().location())
                        || location.equals(duplicate.original().location()));
    }

    private static void recover(Minecraft client, Path folder, Runnable reload, Runnable reopen) {
        inspect(client, folder, "WM-RecoverMirror", () -> {
            var plan = MirrorMigrationCoordinator.recoverAndInspect(folder);
            if (LegacyWorldMetadata.hasLegacyHeader(folder) || hasRelocationJournal(folder)
                    || ambiguous(folder)) {
                WorldGraphService.instance().refresh();
                plan = MirrorMigrationCoordinator.recoverAndInspect(folder);
            }
            if (hasPendingOperation(folder) || ambiguous(folder))
                throw new IOException("Save recovery did not resolve the pending operation: " + folder);
            return plan;
        }, plan -> showDecision(client, folder, reload, reopen, plan), () -> {});
    }

    private static <T> void inspect(Minecraft client, Path folder, String name, OfflineTasks.Work<T> work,
            Consumer<T> completed, Runnable abandoned) {
        runWithProgress(client, new MirrorPrompt.Text("screen.worldmirror.upgrade.checking"), name,
                ignored -> work.run(), failure -> {
                    OperationFailures.report(folder.toString(), "worldgen_check", folder, failure);
                    return null;
                }, completed, abandoned);
    }

    private static void showDecision(Minecraft client, Path worldFolder, Runnable reloadCallback,
            Runnable reopen, MirrorMigrationPlan.Inspection plan) {
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
        runWithProgress(client, new MirrorPrompt.Text("screen.worldmirror.upgrade.progressTitle"),
                "WM-OpenMirrorMigration",
                progress
                -> MirrorMigrationCoordinator.migrateApproved(
                        target, new MirrorMigrationProgress(client, progress)),
                failure -> MirrorMigrationCoordinator.Result.failure("unexpected_failure"), result -> {
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
                }, () -> {});
    }

    private static <T> void runWithProgress(Minecraft client, MirrorPrompt.Text title, String name,
            ProgressWork<T> work, Function<Exception, T> failed, Consumer<T> completed, Runnable abandoned) {
        var progress = ClientDialogs.progress(client, title);
        // The worker never touches screens. An abandoned attempt releases its native save lease.
        OfflineTasks.start(name,
                ()
                        -> work.run(progress),
                failed, client::schedule,
                result
                -> {
                    if (!progress.isCurrent()) {
                        abandoned.run();
                        return;
                    }
                    progress.close();
                    try {
                        completed.accept(result);
                    } catch (RuntimeException | Error failure) {
                        abandoned.run();
                        throw failure;
                    }
                },
                ()
                        -> {},
                () -> {
                    progress.close();
                    abandoned.run();
                });
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
