package net.billstark001.worlddownloader.download;

import net.billstark001.worlddownloader.conflict.ConflictResolver;
import net.billstark001.worlddownloader.conflict.IgnoreResolver;
import net.billstark001.worlddownloader.conflict.ManualResolver;
import net.billstark001.worlddownloader.conflict.OverwriteResolver;
import net.billstark001.worlddownloader.config.ModConfig;
import net.billstark001.worlddownloader.util.ChunkListener;
import net.billstark001.worlddownloader.util.ContainerTracker;
import net.billstark001.worlddownloader.util.EntityTracker;
import net.billstark001.worlddownloader.util.Exporter;
import net.billstark001.worlddownloader.util.WDLogger;
import net.billstark001.worlddownloader.util.WorldExporter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Central controller for the download lifecycle.
 *
 * <ul>
 *   <li>Tracks whether downloading is currently active.</li>
 *   <li>Drives the periodic sync tick (called from the client tick event).</li>
 *   <li>Exposes helpers for a one-shot manual export and for cache clearing.</li>
 * </ul>
 */
public final class DownloadManager {

    private DownloadManager() {}

    // ── State ─────────────────────────────────────────────────────────────────

    private static boolean active = false;
    private static long lastPeriodicSyncMs = 0;

    public static boolean isActive() {
        return active;
    }

    // ── Public commands ───────────────────────────────────────────────────────

    /** Toggles downloading on/off and notifies the player via the action bar. */
    public static void toggle(MinecraftClient client) {
        active = !active;
        if (active) {
            lastPeriodicSyncMs = System.currentTimeMillis();
        }
        Text msg = Text.translatable(
                active ? "msg.worlddownloader.downloadStart"
                       : "msg.worlddownloader.downloadStop");
        if (client.player != null) {
            client.player.sendMessage(msg, true);
        }
        WDLogger.info(active ? "Download activated" : "Download deactivated");
    }

    /**
     * Should be called on every client tick.  Triggers a periodic sync when
     * the configured interval has elapsed.
     */
    public static void onClientTick(MinecraftClient client) {
        if (!active) return;
        long now = System.currentTimeMillis();
        long interval = (long) ModConfig.get().syncIntervalSeconds * 1000L;
        if (now - lastPeriodicSyncMs >= interval) {
            lastPeriodicSyncMs = now;
            runSync(client, false);
        }
    }

    /**
     * Performs an immediate one-shot export regardless of the toggle state.
     * Equivalent to the old "O key" behaviour, but uses the configured output
     * path and conflict strategy.
     */
    public static void exportNow(MinecraftClient client) {
        if (ChunkListener.getAll().isEmpty()) {
            Text msg = Text.translatable("msg.worlddownloader.noChunks");
            if (client.player != null) client.player.sendMessage(msg, false);
            WDLogger.warn("No chunks to export.");
            return;
        }
        runSync(client, true);
    }

    /** Clears all in-memory caches and tells the player what was cleared. */
    public static void clearAll(MinecraftClient client) {
        int chunks     = ChunkListener.getAll().size();
        int entities   = EntityTracker.getTotalTrackedEntities();
        int containers = ContainerTracker.getTotalSavedContainers();
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
        Text msg = Text.translatable("msg.worlddownloader.cleared");
        if (client.player != null) client.player.sendMessage(msg, false);
        WDLogger.info("Cleared: " + chunks + " chunks, " + entities
                + " entities, " + containers + " containers.");
    }

    // ── Internal sync ─────────────────────────────────────────────────────────

    private static void runSync(MinecraftClient client, boolean notify) {
        int chunkCount = ChunkListener.getAll().size();
        if (chunkCount == 0) return;

        try {
            Path worldFolder = getOutputPath();
            Files.createDirectories(worldFolder.resolve("region"));

            WDLogger.info("Syncing " + chunkCount + " chunks → " + worldFolder);

            EntityTracker.captureAllEntities();

            ConflictResolver resolver = buildResolver();
            Set<ChunkPos> written = Exporter.exportChunks(worldFolder, resolver);
            WorldExporter.createLoadableWorld(worldFolder.toFile());
            WorldMetadata.update(worldFolder, client, written);

            WDLogger.info("Sync done: " + written.size() + " chunks written, "
                    + EntityTracker.getTotalTrackedEntities() + " entities, "
                    + ContainerTracker.getTotalSavedContainers() + " containers.");

            if (notify && client.player != null) {
                client.player.sendMessage(
                        Text.translatable("msg.worlddownloader.exportDone"), false);
            }
        } catch (Exception e) {
            WDLogger.warn("Sync failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the root folder for the mirror world, taking the configured save
     * location into account.  Always uses {@code "downloaded_world"} as the
     * directory name for now (future: derive from source ID / mapping table).
     */
    public static Path getOutputPath() {
        ModConfig config = ModConfig.get();
        String name = "downloaded_world";
        if (config.defaultSaveLocation == ModConfig.SaveLocation.SAVES) {
            return FabricLoader.getInstance().getGameDir().resolve("saves").resolve(name);
        }
        return FabricLoader.getInstance().getGameDir().resolve(name);
    }

    private static ConflictResolver buildResolver() {
        return switch (ModConfig.get().defaultConflictStrategy) {
            case IGNORE -> new IgnoreResolver();
            case MANUAL -> new ManualResolver();
            default     -> new OverwriteResolver();
        };
    }
}
