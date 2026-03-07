package net.billstark001.worlddownloader.download;

import net.billstark001.worlddownloader.conflict.ConflictResolver;
import net.billstark001.worlddownloader.conflict.IgnoreResolver;
import net.billstark001.worlddownloader.conflict.ManualResolver;
import net.billstark001.worlddownloader.conflict.OverwriteResolver;
import net.billstark001.worlddownloader.config.ModConfig;
import net.billstark001.worlddownloader.util.ChunkListener;
import net.billstark001.worlddownloader.util.ClientChunkSerializer;
import net.billstark001.worlddownloader.util.ContainerTracker;
import net.billstark001.worlddownloader.util.EntityTracker;
import net.billstark001.worlddownloader.util.Exporter;
import net.billstark001.worlddownloader.util.WDLogger;
import net.billstark001.worlddownloader.util.WorldExporter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.client.world.ClientWorld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central controller for the download lifecycle.
 *
 * <ul>
 *   <li>Tracks whether downloading is currently active.</li>
 *   <li>Drives the periodic sync tick (called from the client tick event).</li>
 *   <li>Runs the actual export on a background daemon thread to avoid freezing the game.</li>
 *   <li>Exposes helpers for a one-shot manual export and for cache clearing.</li>
 * </ul>
 */
public final class DownloadManager {

    private DownloadManager() {}

    // ── State ─────────────────────────────────────────────────────────────────

    private static volatile boolean active = false;
    private static long lastPeriodicSyncMs = 0;
    private static final AtomicBoolean exportInProgress = new AtomicBoolean(false);

    public static boolean isActive() {
        return active;
    }

    public static boolean isExportInProgress() {
        return exportInProgress.get();
    }

    // ── Public commands ───────────────────────────────────────────────────────

    /**
     * Toggles downloading on/off.
     * When enabling, all currently loaded chunks in the active dimension are
     * immediately captured so that the player does not need to reload them.
     */
    public static void toggle(MinecraftClient client) {
        active = !active;
        if (active) {
            lastPeriodicSyncMs = System.currentTimeMillis();
            // Capture chunks that are already loaded so the player does not need
            // to walk through the area again after enabling download.
            if (client.world != null) {
                captureLoadedChunks(client);
            }
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
     * Should be called on every client tick.
     * Triggers a periodic background sync when the configured interval elapses.
     */
    public static void onClientTick(MinecraftClient client) {
        if (!active) return;
        long now = System.currentTimeMillis();
        long interval = (long) ModConfig.get().syncIntervalSeconds * 1000L;
        if (now - lastPeriodicSyncMs >= interval) {
            lastPeriodicSyncMs = now;
            startBackgroundSync(client, false);
        }
    }

    /**
     * Performs an immediate one-shot export regardless of the toggle state.
     */
    public static void exportNow(MinecraftClient client) {
        if (ChunkListener.isEmpty()) {
            Text msg = Text.translatable("msg.worlddownloader.noChunks");
            if (client.player != null) client.player.sendMessage(msg, false);
            WDLogger.warn("No chunks to export.");
            return;
        }
        if (exportInProgress.get()) {
            Text msg = Text.translatable("msg.worlddownloader.exportBusy");
            if (client.player != null) client.player.sendMessage(msg, false);
            WDLogger.warn("Export already in progress.");
            return;
        }
        startBackgroundSync(client, true);
    }

    /** Clears all in-memory caches. */
    public static void clearAll(MinecraftClient client) {
        int chunks     = ChunkListener.getTotalCount();
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

    // ── Output path ───────────────────────────────────────────────────────────

    /**
     * Returns the root folder for the mirror world.
     * The folder name is derived from the current server/world name and persisted
     * in {@code config/worlddownloader/mirrors.json}.
     */
    public static Path getOutputPath(MinecraftClient client) {
        ModConfig config = ModConfig.get();
        String sourceId    = WorldMetadata.detectSourceId(client);
        String folderName  = MirrorMapping.getInstance().getMirrorFolderName(sourceId);
        if (config.defaultSaveLocation == ModConfig.SaveLocation.SAVES) {
            return FabricLoader.getInstance().getGameDir().resolve("saves").resolve(folderName);
        }
        return FabricLoader.getInstance().getGameDir()
                .resolve("downloaded_worlds").resolve(folderName);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Captures all currently loaded chunks in the active dimension.
     * Called on the game thread when download is toggled ON.
     */
    private static void captureLoadedChunks(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;

        RegistryKey<World> dimension = world.getRegistryKey();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        // Use a generous radius; unloaded chunks are skipped via instanceof check
        int range = 33;
        int count = 0;

        for (int cx = playerCX - range; cx <= playerCX + range; cx++) {
            for (int cz = playerCZ - range; cz <= playerCZ + range; cz++) {
                // getChunk returns EmptyChunk for unloaded positions; skip those
                Chunk chunk = world.getChunk(cx, cz);
                if (!(chunk instanceof WorldChunk wc)) continue;
                try {
                    NbtCompound nbt = ClientChunkSerializer.serialize(world, wc);
                    ChunkListener.addChunkNbt(dimension, new ChunkPos(cx, cz), nbt);
                    count++;
                } catch (Exception e) {
                    WDLogger.warn("captureLoadedChunks: failed at "
                            + cx + "," + cz + ": " + e.getMessage());
                }
            }
        }
        WDLogger.info("Captured " + count + " already-loaded chunks in ["
                + dimension.getValue() + "]");
    }

    /**
     * Prepares a snapshot on the game thread, then hands it off to a background
     * daemon thread for the actual I/O work.
     */
    private static void startBackgroundSync(MinecraftClient client, boolean notify) {
        if (exportInProgress.get()) {
            WDLogger.warn("Export already in progress — skipping.");
            return;
        }

        // ── Game-thread preparations ──────────────────────────────────────────
        Map<RegistryKey<World>, Map<ChunkPos, ChunkListener.CapturedChunk>> snapshot =
                ChunkListener.snapshot();
        if (snapshot.isEmpty() || snapshot.values().stream().allMatch(Map::isEmpty)) {
            return;
        }

        // Capture entities for the current dimension (must happen on game thread)
        if (client.world != null) {
            EntityTracker.captureEntitiesForWorld(client.world);
        }
        Map<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> entitySnapshot =
                EntityTracker.snapshot();

        // Collect source info while on the game thread
        String sourceId   = WorldMetadata.detectSourceId(client);
        String sourceType = WorldMetadata.detectSourceType(client);
        Path worldFolder;
        try {
            worldFolder = getOutputPath(client);
            Files.createDirectories(worldFolder);
        } catch (Exception e) {
            WDLogger.warn("Failed to prepare output directory: " + e.getMessage());
            return;
        }

        int totalChunks = snapshot.values().stream().mapToInt(Map::size).sum();
        WDLogger.info("Queuing background export of " + totalChunks
                + " chunks across " + snapshot.size() + " dimension(s)...");

        // ── Background thread ─────────────────────────────────────────────────
        exportInProgress.set(true);
        final Path finalWorldFolder = worldFolder;

        Thread worker = new Thread(() -> {
            try {
                WorldMetadata meta = WorldMetadata.loadOrCreate(
                        finalWorldFolder, sourceId, sourceType);
                ConflictResolver resolver = buildResolver();

                Map<RegistryKey<World>, Set<ChunkPos>> written =
                        Exporter.exportChunks(finalWorldFolder, snapshot, entitySnapshot,
                                resolver, meta);

                WorldExporter.createLoadableWorld(finalWorldFolder.toFile());
                WorldMetadata.update(finalWorldFolder, sourceId, sourceType, written);

                int totalWritten = written.values().stream().mapToInt(Set::size).sum();
                WDLogger.info("Export complete: " + totalWritten + "/"
                        + totalChunks + " chunks written.");

                if (notify && totalWritten > 0) {
                    MinecraftClient.getInstance().execute(() -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.player != null) {
                            mc.player.sendMessage(
                                    Text.translatable("msg.worlddownloader.exportDone"),
                                    false);
                        }
                    });
                }
            } catch (Exception e) {
                WDLogger.warn("Export failed: " + e.getMessage(), e);
            } finally {
                exportInProgress.set(false);
            }
        }, "WDL-Export");
        worker.setDaemon(true);
        worker.start();
    }

    private static ConflictResolver buildResolver() {
        return switch (ModConfig.get().defaultConflictStrategy) {
            case IGNORE -> new IgnoreResolver();
            case MANUAL -> new ManualResolver();
            default     -> new OverwriteResolver();
        };
    }
}
