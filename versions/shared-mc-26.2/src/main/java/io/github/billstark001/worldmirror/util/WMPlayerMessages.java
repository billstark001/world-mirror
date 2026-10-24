package io.github.billstark001.worldmirror.util;

import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.world.entity.player.Player;

/** Version-specific delivery of translated player-facing messages. */
public final class WMPlayerMessages {
    private static final SystemToast.SystemToastId EXPORT_TOAST =
            new SystemToast.SystemToastId(10_000L);

    public static void showExportToast(Minecraft client, Component title, Component body) {
        SystemToast.addOrUpdate(client.gui.toastManager(), EXPORT_TOAST, title, body);
    }

    private WMPlayerMessages() { }

    public static void sendSystemMessage(Player player, Component message) {
        if (player != null) player.sendSystemMessage(message);
    }

    public static void sendOverlayMessage(Player player, Component message) {
        if (player != null) player.sendOverlayMessage(message);
    }
}
