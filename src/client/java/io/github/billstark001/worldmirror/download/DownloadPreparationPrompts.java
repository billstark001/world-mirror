package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.minecraft.client.Minecraft;

/** Localized prompts for an output that needs work before it can be written. */
final class DownloadPreparationPrompts {
    private DownloadPreparationPrompts() {}

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
