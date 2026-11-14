package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;

/** Packet hints, loaded-chunk scans and cache maintenance on the client thread. */
public final class DownloadCapture {
    private DownloadCapture() {}

    private static final int INITIAL_CAPTURE_RANGE = 33;
    private static final int STOP_CAPTURE_RANGE = 6;
    static final DownloadCaptureQueue queue =
            new DownloadCaptureQueue(DownloadPerformanceDiagnostics::isRecording, INITIAL_CAPTURE_RANGE,
                    () -> ModConfig.get().performance.maxPendingCaptureHints);
    public static boolean canCapture(ClientLevel world) {
        if (!DownloadManager.isCaptureEnabled() || world == null)
            return false;
        Minecraft client = Minecraft.getInstance();
        return client.level == world && DownloadManager.sessionIsCurrent(client);
    }

    public static void markEntitiesDirty(ClientLevel world) {
        if (canCapture(world))
            DownloadManager.exportCoordinator.markEntitiesDirty();
    }

    /** Coalesces a packet/event hint into one main-thread capture per chunk. */
    public static void queueChunkCapture(ClientLevel world, ChunkPos pos, String reason) {
        if (pos == null || !canCapture(world))
            return;
        queue.queueChunk(world, pos, reason);
    }

    /** Captures authoritative block changes, including removal of the final block in a chunk. */
    public static void queueBlockChange(ClientLevel world, ChunkPos pos, String reason) {
        if (pos == null || !canCapture(world))
            return;
        queue.queueBlockChange(world, pos, reason);
    }

    /** Last-chance main-thread capture before Fabric removes a loaded chunk. */
    public static void captureChunkBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (chunk == null)
            return;
        if (canCapture(world))
            queue.captureBeforeUnload(world, chunk);
        RemoteChunkProvenance.forget(world, chunk.getPos());
    }

    /** Queues a normal capture when a light packet arrives before its base chunk. */
    public static void queueLightUpdateCapture(ClientLevel world, ChunkPos pos) {
        if (!canCapture(world))
            return;
        queue.queueLightUpdate(world, pos);
    }

    public static Set<ChunkPos> pendingChunkCaptures(
            ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
        if (!DownloadManager.isCaptureEnabled() || dimension == null)
            return Set.of();
        return queue.pendingChunkPositions(dimension, minX, maxX, minZ, maxZ);
    }

    public static long pendingCaptureStatusVersion(ResourceKey<Level> dimension) {
        return queue.statusVersion(dimension);
    }

    /**
     * Coalesces repeated light-only packets before allowing export. The overlay
     * and its revision change immediately; export waits for the short window.
     */
    public static void markLightUpdateDirty(ClientLevel world, ChunkPos pos) {
        if (!canCapture(world))
            return;
        queue.markLightDirty(world, pos);
    }

    /**
     * Queues a wide area of already-loaded chunks for incremental capture on the
     * game thread. Capturing is spread across subsequent ticks to avoid long render
     * thread stalls while still keeping all chunk/world access on the correct thread.
     */
    static void queueLoadedScan(Minecraft client, String reason) {
        ClientLevel world = client.level;
        if (world == null || client.player == null || !DownloadManager.sessionIsCurrent(client))
            return;

        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        boolean scheduled = queue.queueLoaded(world, playerCX, playerCZ, INITIAL_CAPTURE_RANGE, reason);
        if (scheduled) {
            WMLogger.debug("Queued a loaded-chunk scan for incremental capture in [" + dimension.identifier()
                    + "]...");
        }
    }

    /**
     * Captures loaded chunks around the player synchronously on the game thread.
     *
     * <p><b>Use only at disconnect / toggle-off time</b> (a one-shot event where a
     * brief freeze is acceptable and the world is still fully accessible). During
     * normal gameplay, the export coordinator instead schedules incremental
     * chunk capture across subsequent client ticks so large nearby refreshes do not
     * block a single render frame.</p>
     */
    static void captureNearbyLoadedChunksSync(Minecraft client, String reason) {
        ClientLevel world = client.level;
        if (world == null || client.player == null)
            return;

        long startedNs = System.nanoTime();
        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int captured = 0;

        for (int cx = playerCX - STOP_CAPTURE_RANGE; cx <= playerCX + STOP_CAPTURE_RANGE; cx++) {
            for (int cz = playerCZ - STOP_CAPTURE_RANGE; cz <= playerCZ + STOP_CAPTURE_RANGE; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk wc) || !RemoteChunkProvenance.canSample(world, cx, cz)
                        || SeedChunkTracking.hidden(world, wc))
                    continue;
                try {
                    if (ChunkSerializer.isChunkEmpty(wc))
                        continue;
                    CompoundTag nbt = ChunkSerializer.serialize(world, wc);
                    ChunkListener.addChunkNbt(dimension, wc.getPos(), nbt);
                    captured++;
                } catch (Exception e) {
                    WMLogger.warnRateLimited("capture-stop-" + reason, 30_000L,
                            "Stop-time capture failed chunk=" + wc.getPos() + " reason=" + reason, e);
                }
            }
        }

        if (captured > 0) {
            WMLogger.debug("Captured " + captured + " nearby chunks (range=" + STOP_CAPTURE_RANGE + ") for "
                    + reason + " elapsedMs=" + ((System.nanoTime() - startedNs) / 1_000_000L) + ".");
        }
    }
    static void finishPendingBlockChanges(Minecraft client) {
        if (client.level != null && DownloadManager.liveCaptureTarget() != null) {
            queue.finishBlockChanges(client.level);
        }
    }

    static void clearPendingCaptureState(boolean preserveLightUpdates) {
        if (preserveLightUpdates)
            queue.finishLightUpdates();
        queue.clear();
        DownloadManager.exportCoordinator.cancelRequest();
    }

    /** Drops source-scoped captured data before a different logical world becomes active. */
    static void clearCapturedWorldState() {
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
        DownloadManager.exportCoordinator.markEntitiesDirty();
    }

    /**
     * Applies cache-eviction rules from {@link ModConfig} to the chunk cache.
     * Safe to call on the game thread.
     */
    static void applyCacheEviction(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        long maxAgeMs = (long) cfg.cache.maxCacheAgeSeconds * 1000L;
        int maxCount = cfg.cache.maxCachedChunks;
        int maxDist = cfg.cache.maxCacheDistanceChunks;

        if (maxAgeMs <= 0 && maxCount <= 0 && maxDist <= 0)
            return;

        ResourceKey<Level> playerDim = (client.level != null) ? client.level.dimension() : null;
        int playerCX = (client.player != null) ? (client.player.getBlockX() >> 4) : 0;
        int playerCZ = (client.player != null) ? (client.player.getBlockZ() >> 4) : 0;

        ChunkListener.evictStale(maxAgeMs, maxCount, playerDim, playerCX, playerCZ, maxDist);
    }

    /** Clears all in-memory caches. */
    public static void clearAll(Minecraft client) {
        clearPendingCaptureState(false);
        int chunks = ChunkListener.getTotalCount();
        int entities = EntityTracker.getTotalTrackedEntities();
        int containers = ContainerTracker.getTotalSavedContainers();
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
        Component msg = Component.translatable("msg.worldmirror.cleared");
        WMPlayerMessages.sendSystemMessage(client.player, msg);
        WMLogger.debug(
                "Cleared: " + chunks + " chunks, " + entities + " entities, " + containers + " containers.");
    }
}
