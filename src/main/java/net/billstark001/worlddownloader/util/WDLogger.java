package net.billstark001.worlddownloader.util;

import net.billstark001.worlddownloader.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralised logger for World Downloader.
 * <p>
 * Every message is written to the standard SLF4J log (always).
 * Messages whose level is at or above the configured in-game level are also
 * echoed to the player's chat.
 */
public final class WDLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldDownloader");

    private WDLogger() {}

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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        String prefix = switch (level) {
            case DEBUG   -> "§7[WDL DEBUG] ";
            case INFO    -> "§f[WDL] ";
            case WARNING -> "§e[WDL WARN] ";
        };
        Text text = Text.literal(prefix + msg);
        // Always dispatch onto the main (render) thread to avoid thread-safety issues.
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(text, false);
            }
        });
    }
}
