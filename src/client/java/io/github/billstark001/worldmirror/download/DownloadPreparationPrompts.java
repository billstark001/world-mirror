package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Localized prompts for an output that needs work before it can be written. */
final class DownloadPreparationPrompts {
    private DownloadPreparationPrompts() {}

    static String sourceStatusKey() {
        return WorldGraphService.instance().routing().strictlyRequiresIdentity()
                ? "screen.worldmirror.status.downloadAwaitingId"
                : "screen.worldmirror.status.downloadAwaitingSource";
    }

    static void waitForSource(
            Minecraft client, boolean canChoose, WorldSourceResolver.Resolution resolution) {
        WMLogger.info("Download route awaiting " + (canChoose ? "source selection" : "a bound ID")
                + " state=" + resolution.state() + " choices=" + resolution.candidateSourceIds().size());
        WMPlayerMessages.sendSystemMessage(client.player,
                Component.translatable(canChoose ? "screen.worldmirror.captureRoute.selectionRequired"
                                                 : "screen.worldmirror.captureRoute.waiting"));
    }

    static void future(Minecraft client, WorldGraphService.Target target, boolean showDialog) {
        WMLogger.warn("Mirror output is newer than this client world=" + target.folder());
        alert(client, showDialog, new MirrorPrompt.Text("screen.worldmirror.upgrade.futureTitle"),
                new MirrorPrompt.Text("screen.worldmirror.upgrade.futureBody"));
    }

    static void unavailable(Minecraft client, WorldGraphService.Target target,
            MirrorMigrationPlan.Inspection plan, boolean showDialog) {
        WMLogger.warn("Mirror output unavailable world=" + target.folder() + " state=" + plan.state());
        alert(client, showDialog, new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                plan.problem() != null ? new MirrorPrompt.Text("screen.worldmirror.repair.unavailableBody")
                                       : new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody",
                                                 plan.state().name()));
    }

    static MirrorPrompt.Confirmation upgrade(
            WorldGraphService.Target target, MirrorMigrationPlan.Inspection plan) {
        boolean repair = plan.worldgenWork() == MirrorMigrationPlan.WorldgenWork.REPAIR_VOID_GENERATOR;
        var title = new MirrorPrompt.Text(
                repair ? "screen.worldmirror.repair.title" : "screen.worldmirror.upgrade.title");
        var message = new MirrorPrompt.Text(
                repair ? "screen.worldmirror.repair.downloadBody" : "screen.worldmirror.upgrade.downloadBody",
                target.folder().getFileName());
        var confirm = new MirrorPrompt.Text(repair ? "screen.worldmirror.repair.downloadConfirm"
                                                   : "screen.worldmirror.upgrade.downloadConfirm");
        return new MirrorPrompt.Confirmation(title, message, confirm, new MirrorPrompt.Text("gui.cancel"));
    }

    static void alert(
            Minecraft client, boolean showDialog, MirrorPrompt.Text title, MirrorPrompt.Text message) {
        if (showDialog)
            ClientDialogs.alert(client,
                    new MirrorPrompt.Alert(title, message, new MirrorPrompt.Text("gui.done")), () -> {});
        else
            ClientDialogs.toast(client, title, message);
    }
}
