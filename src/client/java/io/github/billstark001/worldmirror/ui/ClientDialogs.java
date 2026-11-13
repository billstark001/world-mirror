package io.github.billstark001.worldmirror.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

#if MC_VERSION >= 260200
/** Thin Minecraft-26.2 adapter for the version-neutral prompt model. */
#elif MC_VERSION >= 260102
/** Thin Minecraft-26.1.2 adapter for the version-neutral prompt model. */
#else
/** Thin Minecraft-1.21.11 adapter for the version-neutral prompt model. */
#endif
public final class ClientDialogs {
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

    private ClientDialogs() {}

    public static Screen currentScreen(Minecraft client) {
#if MC_VERSION >= 260200
        return client.gui.screen();
#else
        return client.screen;
#endif
    }

    public static void confirm(Minecraft client, MirrorPrompt.Confirmation prompt,
                               Runnable accept, Runnable cancel) {
#if MC_VERSION >= 260200
        Screen parent = client.gui.screen();
        client.gui.setScreen(new ConfirmScreen(yes -> {
            client.gui.setScreen(parent);
#else
        Screen parent = client.screen;
        client.setScreen(new ConfirmScreen(yes -> {
            client.setScreen(parent);
#endif
            if (yes) accept.run(); else cancel.run();
        }, component(prompt.title()), component(prompt.message()),
                component(prompt.accept()), component(prompt.cancel())));
    }

    public static void alert(Minecraft client, MirrorPrompt.Alert prompt, Runnable done) {
#if MC_VERSION >= 260200
        Screen parent = client.gui.screen();
        client.gui.setScreen(new AlertScreen(() -> {
            client.gui.setScreen(parent);
#else
        Screen parent = client.screen;
        client.setScreen(new AlertScreen(() -> {
            client.setScreen(parent);
#endif
            done.run();
        }, component(prompt.title()), component(prompt.message()), component(prompt.acknowledge()), true));
    }

    public static void upgrade(Minecraft client, MirrorPrompt.Upgrade prompt,
                               Runnable upgrade, Runnable skip, Runnable cancel) {
#if MC_VERSION >= 260200
        Screen parent = client.gui.screen();
        client.gui.setScreen(new BackupConfirmScreen(
                () -> { client.gui.setScreen(parent); cancel.run(); },
#else
        Screen parent = client.screen;
        client.setScreen(new BackupConfirmScreen(
                () -> { client.setScreen(parent); cancel.run(); },
#endif
                (backup, ignored) -> { if (backup) upgrade.run(); else skip.run(); },
                component(prompt.title()), component(prompt.description()), component(prompt.upgrade()), false));
    }

    public static MirrorPrompt.ProgressHandle progress(Minecraft client, MirrorPrompt.Text title) {
#if MC_VERSION >= 260200
        Screen parent = client.gui.screen();
        ProgressScreen screen = new ProgressScreen(false);
        screen.progressStartNoAbort(component(title));
        client.gui.setScreen(screen);
#else
        Screen parent = client.screen;
        ProgressScreen screen = new ProgressScreen(false);
        screen.progressStartNoAbort(component(title));
        client.setScreen(screen);
#endif
        return new MirrorPrompt.ProgressHandle() {
            @Override public void stage(MirrorPrompt.Text stage) { screen.progressStage(component(stage)); }
            @Override public void progress(int completed, int total) {
                screen.progressStagePercentage(total <= 0 ? 100 : Math.clamp(completed * 100 / total, 0, 100));
            }
#if MC_VERSION >= 260200
            @Override public void close() { screen.stop(); client.gui.setScreen(parent); }
#else
            @Override public void close() { screen.stop(); client.setScreen(parent); }
#endif
        };
    }

    public static void toast(Minecraft client, MirrorPrompt.Text title, MirrorPrompt.Text message) {
#if MC_VERSION >= 260200
        SystemToast.addOrUpdate(client.gui.toastManager(), TOAST_ID, component(title), component(message));
#else
        SystemToast.addOrUpdate(client.getToastManager(), TOAST_ID, component(title), component(message));
#endif
    }

    private static Component component(MirrorPrompt.Text text) {
        return Component.translatable(text.key(), text.arguments());
    }
}
