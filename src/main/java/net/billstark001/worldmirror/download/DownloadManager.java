package net.billstark001.worldmirror.download;

import net.billstark001.worldmirror.conflict.ConflictResolver;
import net.billstark001.worldmirror.conflict.IgnoreResolver;
import net.billstark001.worldmirror.conflict.ManualResolver;
import net.billstark001.worldmirror.conflict.OverwriteResolver;
import net.billstark001.worldmirror.config.ModConfig;
import net.billstark001.worldmirror.core.ChunkListener;
import net.billstark001.worldmirror.io.ChunkExporter;
import net.billstark001.worldmirror.io.ChunkSerializer;
import net.billstark001.worldmirror.core.ContainerTracker;
import net.billstark001.worldmirror.core.EntityTracker;
import net.billstark001.worldmirror.util.WMLogger;
import net.billstark001.worldmirror.io.WorldStructureCreator;
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
import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
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

    private static final AtomicBoolean currentActive = new AtomicBoolean(false);
    private static long lastPeriodicSyncMs = 0;
    private static final AtomicBoolean exportInProgress = new AtomicBoolean(false);
    /** Guards against starting a second initial-capture while one is still running. */
    private static final AtomicBoolean captureInProgress = new AtomicBoolean(false);

    // ── Lifecycle tracking ────────────────────────────────────────────────────

    /**
     * The dimension registry key observed on the previous client tick.
     * Used to detect dimension changes (e.g. Overworld → Nether).
     */
    private static volatile RegistryKey<World> lastDimension = null;

    /**
     * The sourceId observed on the previous client tick.
     * Used to detect server-side world changes (e.g. Multiverse world switch)
     * while remaining connected to the same server address.
     */
    private static volatile String lastSourceId = null;

    public static boolean isActive() {
        return currentActive.get();
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
        boolean nowActive = !currentActive.get();
        currentActive.set(nowActive);
        if (nowActive) {
            lastPeriodicSyncMs = System.currentTimeMillis();
            // Capture chunks that are already loaded so the player does not need
            // to walk through the area again after enabling download.
            if (client.world != null) {
                captureLoadedChunksAsync(client);
            }
        }
        Text msg = Text.translatable(
                nowActive ? "msg.worldmirror.downloadStart"
                          : "msg.worldmirror.downloadStop");
        if (client.player != null) {
            client.player.sendMessage(msg, true);
        }
        WMLogger.info(nowActive ? "Download activated" : "Download deactivated");
    }

    /**
     * Performs an immediate one-shot export regardless of the toggle state.
     */
    public static void exportNow(MinecraftClient client) {
        if (ChunkListener.isEmpty()) {
            Text msg = Text.translatable("msg.worldmirror.noChunks");
            if (client.player != null) client.player.sendMessage(msg, false);
            WMLogger.warn("No chunks to export.");
            return;
        }
        if (exportInProgress.get()) {
            Text msg = Text.translatable("msg.worldmirror.exportBusy");
            if (client.player != null) client.player.sendMessage(msg, false);
            WMLogger.warn("Export already in progress.");
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
        Text msg = Text.translatable("msg.worldmirror.cleared");
        if (client.player != null) client.player.sendMessage(msg, false);
        WMLogger.info("Cleared: " + chunks + " chunks, " + entities
                + " entities, " + containers + " containers.");
    }

    // ── Lifecycle event handlers ──────────────────────────────────────────────

    /**
     * Called when the player joins (or re-joins) a world / server.
     * Resets lifecycle tracking state and applies the configured
     * {@link ModConfig.LifecycleConfig#onJoinWorld} behaviour.
     */
    public static void onJoinWorld(MinecraftClient client) {
        // Reset tracking so subsequent change-detection starts fresh.
        lastDimension = (client.world != null) ? client.world.getRegistryKey() : null;
        lastSourceId  = WorldMetadata.detectSourceId(client);

        applyTransition(client, ModConfig.get().lifecycle.onJoinWorld, "join-world");
    }

    /**
     * Called when the player disconnects from a world / server.
     * Resets lifecycle tracking state so the next join starts clean.
     * Download state is NOT changed here — leaving the world inherently stops capture.
     */
    public static void onLeaveWorld() {
        lastDimension = null;
        lastSourceId  = null;
        currentActive.set(false); // no world to download — always stop
        WMLogger.info("Left world; download deactivated.");
    }

    /**
     * Should be called on every client tick.
     * Triggers a periodic background sync when the configured interval elapses,
     * applies cache-eviction rules, and detects dimension / server-world changes.
     */
    public static void onClientTick(MinecraftClient client) {
        if (client.world == null) return;

        RegistryKey<World> currentDim = client.world.getRegistryKey();
        String currentSourceId = WorldMetadata.detectSourceId(client);

        // ── Detect server-side world change (same address, different logical world) ──
        // Must be checked BEFORE dimension change, as a world change also implies
        // a dimension change.
        if (lastSourceId != null && !lastSourceId.equals(currentSourceId)) {
            WMLogger.info("Server world change detected: '" + lastSourceId
                    + "' → '" + currentSourceId + "'");
            lastSourceId  = currentSourceId;
            lastDimension = currentDim;
            applyTransition(client, ModConfig.get().lifecycle.onServerWorldChange,
                    "server-world-change");
            // Re-capture loaded chunks according to the new active state
            if (currentActive.get()) captureLoadedChunksAsync(client);
            if (!currentActive.get()) return;
        }

        // ── Detect dimension change (Overworld ↔ Nether ↔ End, etc.) ──────────
        if (lastDimension != null && !lastDimension.equals(currentDim)) {
            WMLogger.info("Dimension change detected: '" + lastDimension.getValue()
                    + "' → '" + currentDim.getValue() + "'");
            lastDimension = currentDim;
            applyTransition(client, ModConfig.get().lifecycle.onDimensionChange,
                    "dimension-change");
            // Re-capture loaded chunks according to the new active state
            if (currentActive.get()) captureLoadedChunksAsync(client);
        }

        // Update tracking even if player was in null world on the previous tick
        if (lastDimension == null) lastDimension = currentDim;
        if (lastSourceId  == null) lastSourceId  = currentSourceId;

        if (!currentActive.get()) return;

        long now = System.currentTimeMillis();
        long interval = (long) ModConfig.get().syncIntervalSeconds * 1000L;
        if (now - lastPeriodicSyncMs >= interval) {
            lastPeriodicSyncMs = now;
            applyCacheEviction(client);
            startBackgroundSync(client, false);
        }
    }

    /**
     * Applies a {@link ModConfig.TransitionBehavior} to the download state.
     * Sends an appropriate HUD message when the state actually changes.
     *
     * @param eventName human-readable event name used only for logging
     */
    private static void applyTransition(MinecraftClient client,
                                        ModConfig.TransitionBehavior behavior,
                                        String eventName) {
        boolean desired;
        switch (behavior) {
            case START -> desired = true;
            case STOP  -> desired = false;
            default    -> { return; } // KEEP — do nothing
        }
        if (currentActive.get() == desired) return; // already in the right state

        currentActive.set(desired);
        if (desired) lastPeriodicSyncMs = System.currentTimeMillis();

        Text msg = Text.translatable(
                desired ? "msg.worldmirror.downloadStart"
                       : "msg.worldmirror.downloadStop");
        if (client.player != null) client.player.sendMessage(msg, true);
        WMLogger.info("Download " + (desired ? "activated" : "deactivated")
                + " by lifecycle event: " + eventName);
    }

    // ── Output path ───────────────────────────────────────────────────────────

    /**
     * Returns the root folder for the mirror world.
     * Per-world save-location (from {@link MirrorMapping}) takes precedence over the global config.
     *
     * <p>Resolution strategy (prevents both collision clobbering and the {@code _2_2_2…}
     * suffix-accumulation bug):
     * <ol>
     *   <li>Determine the <em>base</em> folder name from {@code entries} (never contains a
     *       generated suffix).</li>
     *   <li>If {@code resolvedFolderNames} already contains an entry for this source
     *       <em>and</em> that folder still exists and is still owned by us, reuse it
     *       immediately — no rescan needed.</li>
     *   <li>Otherwise run the full collision scan starting from the base name, find a free
     *       or owned folder, and persist the winner into {@code resolvedFolderNames} only
     *       (the base name in {@code entries} is never touched).</li>
     * </ol>
     */
    public static Path getOutputPath(MinecraftClient client) {
        String sourceId   = WorldMetadata.detectSourceId(client);
        // Always the sanitised base name — no suffix appended here.
        String baseName   = MirrorMapping.getInstance().getMirrorFolderName(sourceId);

        // Per-world override wins over global config
        String perWorldLoc = MirrorMapping.getInstance().getPerWorldSaveLocation(sourceId);
        ModConfig.SaveLocation saveLocation;
        if (perWorldLoc != null) {
            try {
                saveLocation = ModConfig.SaveLocation.valueOf(perWorldLoc);
            } catch (IllegalArgumentException e) {
                saveLocation = ModConfig.get().defaultSaveLocation;
            }
        } else {
            saveLocation = ModConfig.get().defaultSaveLocation;
        }

        Path base = (saveLocation == ModConfig.SaveLocation.SAVES)
                ? FabricLoader.getInstance().getGameDir().resolve("saves")
                : FabricLoader.getInstance().getGameDir().resolve("downloaded_worlds");

        String saveLocName = saveLocation.name();

        // Fast path: if we already recorded a validated resolved name for this
        // (source, save-location) pair, getResolvedFolderName() has already
        // checked existence and ownership — reuse it immediately.
        String cachedResolved = MirrorMapping.getInstance()
                .getResolvedFolderName(sourceId, saveLocName, base);
        if (cachedResolved != null) {
            return base.resolve(cachedResolved);
        }

        // Full collision scan starting from the base name.
        Path resolved = resolveOutputPath(base, baseName, sourceId);
        String resolvedName = resolved.getFileName().toString();

        // Persist the winner — keyed by (sourceId, saveLocName) — never touch entries.
        MirrorMapping.getInstance().setResolvedFolderName(sourceId, saveLocName, resolvedName);
        if (!resolvedName.equals(baseName)) {
            WMLogger.info("Folder name collision resolved: '"
                    + baseName + "' → '" + resolvedName + "'");
        }
        return resolved;
    }

    /**
     * Resolves a collision-free output path under {@code base} for the given
     * {@code folderName} and {@code sourceId}.
     *
     * <ul>
     *   <li>If the candidate folder does not exist → return it.</li>
     *   <li>If it exists and is owned by {@code sourceId} (matching
     *       {@code worldmirror_meta.json}) → return it.</li>
     *   <li>Otherwise append {@code _2}, {@code _3}, … until a free or owned
     *       folder is found.</li>
     * </ul>
     */
    private static Path resolveOutputPath(Path base, String folderName, String sourceId) {
        Path candidate = base.resolve(folderName);
        if (isFolderFreeOrOwned(candidate, sourceId)) return candidate;

        for (int suffix = 2; suffix < 1000; suffix++) {
            candidate = base.resolve(folderName + "_" + suffix);
            if (isFolderFreeOrOwned(candidate, sourceId)) return candidate;
        }
        // Fallback (should never happen in practice)
        return base.resolve(folderName + "_" + System.currentTimeMillis());
    }

    /**
     * Returns {@code true} if the folder is safe to use:
     * either it does not exist yet, or it already belongs to {@code sourceId}.
     */
    private static boolean isFolderFreeOrOwned(Path folder, String sourceId) {
        if (!folder.toFile().exists()) return true;
        return WorldMetadata.isOwnedBy(folder, sourceId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     *
     * <p><b>Phase 1 (game thread, fast):</b> Iterates through chunk positions in a
     * {@code range×range} square around the player and collects references to loaded
     * {@link WorldChunk} objects.  Only reference lookups happen here — no NBT
     * serialisation — so this phase completes in microseconds.
     *
     * <p><b>Phase 2 (background thread, {@code WM-InitCapture}):</b> Serialises each
     * collected chunk to NBT and stores the result via {@link ChunkListener}.  The
     * capture is aborted early if download is deactivated mid-flight.
     *
     * <p>A CAS guard ({@link #captureInProgress}) ensures only one initial capture
     * runs at a time.
     */
    private static void captureLoadedChunksAsync(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;

        if (!captureInProgress.compareAndSet(false, true)) {
            WMLogger.debug("Initial chunk capture already in progress — skipping.");
            return;
        }

        RegistryKey<World> dimension = world.getRegistryKey();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        // 33 covers a ~66-chunk diameter, comfortably exceeding the maximum client-side
        // render distance (typically 2–32 chunks).  Unloaded positions return an
        // EmptyChunk and are filtered out by the instanceof check below.
        int range = 33;

        // ── Phase 1: collect WorldChunk references (game thread, no serialisation) ──
        List<WorldChunk> toCapture = new ArrayList<>();
        for (int cx = playerCX - range; cx <= playerCX + range; cx++) {
            for (int cz = playerCZ - range; cz <= playerCZ + range; cz++) {
                Chunk chunk = world.getChunk(cx, cz);
                if (chunk instanceof WorldChunk wc) {
                    toCapture.add(wc);
                }
            }
        }

        if (toCapture.isEmpty()) {
            captureInProgress.set(false);
            return;
        }

        WMLogger.info("Scheduling async initial capture of " + toCapture.size()
                + " loaded chunks in [" + dimension.getValue() + "]...");

        // ── Phase 2: serialise on a background thread ─────────────────────────
        Thread captureThread = getCaptureThread(toCapture, world, dimension);
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private static @NonNull Thread getCaptureThread(List<WorldChunk> toCapture, ClientWorld world, RegistryKey<World> dimension) {
        return new Thread(() -> {
            try {
                int count = 0;
                for (WorldChunk wc : toCapture) {
                    // `active` is declared volatile; reading it here is always fresh.
                    if (!currentActive.get()) break; // abort if download was deactivated mid-capture
                    try {
                        if (ChunkSerializer.isChunkEmpty(wc)) continue;
                        NbtCompound nbt = ChunkSerializer.serialize(world, wc);
                        ChunkListener.addChunkNbt(dimension, wc.getPos(), nbt);
                        count++;
                    } catch (Exception e) {
                        WMLogger.warn("captureLoadedChunksAsync: failed at "
                                + wc.getPos() + ": " + e.getMessage());
                    }
                }
                WMLogger.info("Async initial capture complete: " + count + "/"
                        + toCapture.size() + " chunks captured in ["
                        + dimension.getValue() + "]");
            } finally {
                captureInProgress.set(false);
            }
        }, "WM-InitCapture");
    }

    /**
     * Prepares a snapshot on the game thread, then hands it off to a background
     * daemon thread for the actual I/O work.
     */
    private static void startBackgroundSync(MinecraftClient client, boolean notify) {
        if (exportInProgress.get()) {
            WMLogger.warn("Export already in progress — skipping.");
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
            WMLogger.warn("Failed to prepare output directory: " + e.getMessage());
            return;
        }

        int totalChunks = snapshot.values().stream().mapToInt(Map::size).sum();
        WMLogger.info("Queuing background export of " + totalChunks
                + " chunks across " + snapshot.size() + " dimension(s)...");

        // ── Background thread ─────────────────────────────────────────────────
        exportInProgress.set(true);
        final Path finalWorldFolder = worldFolder;

        Thread worker = new Thread(() -> {
            ChunkDatabase db = null;
            try {
                WorldMetadata meta = WorldMetadata.loadOrCreate(
                        finalWorldFolder, sourceId, sourceType);

                // Open (or create) the chunk database for this mirror world
                try {
                    db = ChunkDatabase.open(finalWorldFolder, sourceId);
                } catch (SQLException e) {
                    WMLogger.warn("Could not open chunk database, export aborted: " + e.getMessage());
                    return;
                }

                // Migrate legacy chunkUpdateTimes from JSON → SQLite (idempotent)
                meta.migrateAndCleanChunkTimes(db, finalWorldFolder);

                ConflictResolver resolver = buildResolverForSource(sourceId);

                Map<RegistryKey<World>, Set<ChunkPos>> written =
                        ChunkExporter.exportChunks(finalWorldFolder, snapshot, entitySnapshot,
                                resolver, db);

                WorldStructureCreator.createLoadableWorld(finalWorldFolder.toFile(), sourceId);
                WorldMetadata.update(finalWorldFolder, sourceId, sourceType);

                // Record written chunks in the database
                for (Map.Entry<RegistryKey<World>, Set<ChunkPos>> dimEntry : written.entrySet()) {
                    String dimStr = dimEntry.getKey().getValue().toString();
                    db.recordUpdates(dimStr, dimEntry.getValue(), "world_mirror");
                }

                // Invalidate exported chunks if configured
                if (ModConfig.get().cache.invalidateAfterExport && !written.isEmpty()) {
                    ChunkListener.invalidateChunks(written);
                }

                int totalWritten = written.values().stream().mapToInt(Set::size).sum();
                WMLogger.info("Export complete: " + totalWritten + "/"
                        + totalChunks + " chunks written.");

                if (notify && totalWritten > 0) {
                    MinecraftClient.getInstance().execute(() -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.player != null) {
                            mc.player.sendMessage(
                                    Text.translatable("msg.worldmirror.exportDone"),
                                    false);
                        }
                    });
                }
            } catch (Exception e) {
                WMLogger.warn("Export failed: " + e.getMessage(), e);
            } finally {
                if (db != null) db.close();
                exportInProgress.set(false);
            }
        }, "WM-Export");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Builds a conflict resolver for the given source, checking per-world overrides first.
     * If {@code sourceId} is {@code null}, the global config is used directly.
     */
    public static ConflictResolver buildResolverForSource(String sourceId) {
        ModConfig.ConflictStrategy strategy = ModConfig.get().defaultConflictStrategy;
        if (sourceId != null) {
            String perWorld = MirrorMapping.getInstance().getPerWorldConflictStrategy(sourceId);
            if (perWorld != null) {
                try {
                    strategy = ModConfig.ConflictStrategy.valueOf(perWorld);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return switch (strategy) {
            case IGNORE -> new IgnoreResolver();
            case MANUAL -> new ManualResolver();
            default     -> new OverwriteResolver();
        };
    }

    /**
     * Applies cache-eviction rules from {@link ModConfig} to the chunk cache.
     * Safe to call on the game thread.
     */
    private static void applyCacheEviction(MinecraftClient client) {
        ModConfig cfg = ModConfig.get();
        long maxAgeMs = (long) cfg.cache.maxCacheAgeSeconds * 1000L;
        int maxCount = cfg.cache.maxCachedChunks;
        int maxDist = cfg.cache.maxCacheDistanceChunks;

        if (maxAgeMs <= 0 && maxCount <= 0 && maxDist <= 0) return;

        RegistryKey<World> playerDim = (client.world != null)
                ? client.world.getRegistryKey() : null;
        int playerCX = (client.player != null) ? (client.player.getBlockX() >> 4) : 0;
        int playerCZ = (client.player != null) ? (client.player.getBlockZ() >> 4) : 0;

        ChunkListener.evictStale(maxAgeMs, maxCount, playerDim, playerCX, playerCZ, maxDist);
    }
}
