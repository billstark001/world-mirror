package net.billstark001.worldmirror.util;

import net.billstark001.worldmirror.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralised logger for World Mirror.
 * <p>
 * Every message is written to the standard SLF4J log (always).
 * Messages whose level is at or above the configured in-game level are also
 * echoed to the player's chat.
 */
public final class WMLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldMirror");

    private WMLogger() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static void debug(String msg) {
        LOGGER.debug(msg);
        showInGame(ModConfig.LogLevel.DEBUG, msg);
    }

    public static void info(String msg) {
        LOGGER.info(msg);
        showInGame(ModConfig.LogLevel.INFO, msg);
    }

    public static void warn(String msg) {
        LOGGER.warn(msg);
        showInGame(ModConfig.LogLevel.WARNING, msg);
    }

    public static void warn(String msg, Throwable t) {
        LOGGER.warn(msg, t);
        showInGame(ModConfig.LogLevel.WARNING, msg + ": " + t.getMessage());
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void showInGame(ModConfig.LogLevel level, String msg) {
        if (level.ordinal() < ModConfig.get().logLevel.ordinal()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        String prefix = switch (level) {
            case DEBUG   -> "§7[WM DEBUG] ";
            case INFO    -> "§f[WM] ";
            case WARNING -> "§e[WM WARN] ";
        };
        Component text = Component.literal(prefix + msg);
        // Always dispatch onto the main (render) thread to avoid thread-safety issues.
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(text, false);
            }
        });
    }
}
