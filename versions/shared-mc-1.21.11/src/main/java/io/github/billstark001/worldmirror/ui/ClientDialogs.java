package io.github.billstark001.worldmirror.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Thin Minecraft-1.21.11 adapter for the version-neutral prompt model. */
public final class ClientDialogs {
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

    private ClientDialogs() {}

    public static void confirm(Minecraft client, MirrorPrompt.Confirmation prompt,
                               Runnable accept, Runnable cancel) {
        Screen parent = client.screen;
        client.setScreen(new ConfirmScreen(yes -> {
            client.setScreen(parent);
            if (yes) accept.run(); else cancel.run();
        }, component(prompt.title()), component(prompt.message()),
                component(prompt.accept()), component(prompt.cancel())));
    }

    public static void alert(Minecraft client, MirrorPrompt.Alert prompt, Runnable done) {
        Screen parent = client.screen;
        client.setScreen(new AlertScreen(() -> {
            client.setScreen(parent);
            done.run();
        }, component(prompt.title()), component(prompt.message()), component(prompt.acknowledge()), true));
    }

    public static void upgrade(Minecraft client, MirrorPrompt.Upgrade prompt,
                               Runnable upgrade, Runnable skip, Runnable cancel) {
        Screen parent = client.screen;
        client.setScreen(new BackupConfirmScreen(
                () -> { client.setScreen(parent); cancel.run(); },
                (backup, ignored) -> { if (backup) upgrade.run(); else skip.run(); },
                component(prompt.title()), component(prompt.description()), component(prompt.upgrade()), false));
    }

    public static MirrorPrompt.ProgressHandle progress(Minecraft client, MirrorPrompt.Text title) {
        Screen parent = client.screen;
        ProgressScreen screen = new ProgressScreen(false);
        screen.progressStartNoAbort(component(title));
        client.setScreen(screen);
        return new MirrorPrompt.ProgressHandle() {
            @Override public void stage(MirrorPrompt.Text stage) { screen.progressStage(component(stage)); }
            @Override public void close() { screen.stop(); client.setScreen(parent); }
        };
    }

    public static void toast(Minecraft client, MirrorPrompt.Text title, MirrorPrompt.Text message) {
        SystemToast.addOrUpdate(client.getToastManager(), TOAST_ID, component(title), component(message));
    }

    private static Component component(MirrorPrompt.Text text) {
        return Component.translatable(text.key(), text.arguments());
    }
}
