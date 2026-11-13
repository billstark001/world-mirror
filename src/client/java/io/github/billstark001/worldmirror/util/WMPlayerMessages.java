package io.github.billstark001.worldmirror.util;

import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.world.entity.player.Player;

/** Version-specific delivery of translated player-facing messages. */
public final class WMPlayerMessages {
    private static final SystemToast.SystemToastId EXPORT_TOAST = new SystemToast.SystemToastId(10_000L);

    public static void showExportToast(Minecraft client, Component title, Component body) {
#if MC_VERSION >= 260200
        SystemToast.addOrUpdate(client.gui.toastManager(), EXPORT_TOAST, title, body);
#else
        SystemToast.addOrUpdate(client.getToastManager(), EXPORT_TOAST, title, body);
#endif
    }

    private WMPlayerMessages() {}

    public static void sendSystemMessage(Player player, Component message) {
#if MC_VERSION >= 260102
        if (player != null)
            player.sendSystemMessage(message);
#else
        if (player != null)
            player.displayClientMessage(message, false);
#endif
    }

    public static void sendOverlayMessage(Player player, Component message) {
#if MC_VERSION >= 260102
        if (player != null)
            player.sendOverlayMessage(message);
#else
        if (player != null)
            player.displayClientMessage(message, true);
#endif
    }
}
