package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the small, render-thread-safe data set used by the 3D chunk overlay. */
public final class ChunkWorldOverlay {
    private static final long REBUILD_INTERVAL_MS = 100L;

    private static final int YELLOW = 0xFFFFD21F;
    private static final int GREEN = 0xFF20E060;
    private static final int BLUE = 0xFF2878E0;

    private static volatile OverlaySnapshot cached = OverlaySnapshot.EMPTY;
    private static long lastBuildMs;
    private static ResourceKey<Level> lastDimension;
    private static int lastCenterX;
    private static int lastCenterZ;
    private static int lastRadius;
    private static int lastHeight;

    private ChunkWorldOverlay() {}

    /**
     * Returns a bounded snapshot.  Historical state is loaded asynchronously by
     * {@link ChunkStatusCache}; live queue/cache state is overlaid on top of it.
     */
    public static synchronized OverlaySnapshot snapshot(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            cached = OverlaySnapshot.EMPTY;
            return cached;
        }
        ModConfig.ChunkMapConfig config = ModConfig.get().chunkMap;
        if (!config.showWorldChunkOverlay) {
            cached = OverlaySnapshot.EMPTY;
            lastBuildMs = 0L;
            return cached;
        }

        ResourceKey<Level> dimension = client.level.dimension();
        int centerX = client.player.getBlockX() >> 4;
        int centerZ = client.player.getBlockZ() >> 4;
        int radius = clampRadius(config.worldChunkOverlayRenderDistance);
        int height = clampHeight(config.worldChunkOverlayHeight);
        long now = System.currentTimeMillis();
        if (now - lastBuildMs < REBUILD_INTERVAL_MS
                && dimension.equals(lastDimension)
                && centerX == lastCenterX && centerZ == lastCenterZ
                && radius == lastRadius && height == lastHeight) {
            return cached;
        }

        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;
        Map<Long, OverlayChunk> byChunk = new HashMap<>();

        // Persistent records are the historical (blue) layer.  World Mirror
        // intentionally ignores records written by unrelated integrations.
        ChunkStatusSnapshot persistent = ChunkStatusCache.getOrScheduleRefresh(
                client, dimension, 1_000L);
        persistent.forEachRecordInRange(minX, maxX, minZ, maxZ, record -> {
            if ("world_mirror".equals(record.updateSource())) {
                put(byChunk, record.x(), record.z(), height, BLUE);
            }
        });

        // A current in-memory capture is green, even if it has already been
        // acknowledged.  This gives the user a clear current-session layer.
        long sessionStartedAtMs = DownloadManager.downloadSessionStartedAtMs();
        for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry
                : ChunkListener.getDimension(dimension).entrySet()) {
            ChunkPos pos = entry.getKey();
            if (entry.getValue().capturedAtMs() < sessionStartedAtMs) continue;
            int x = pos.getMinBlockX() >> 4;
            int z = pos.getMinBlockZ() >> 4;
            if (inRange(x, z, minX, maxX, minZ, maxZ)) {
                put(byChunk, x, z, height, GREEN);
            }
        }

        // Queue entries have priority over both other layers while the capture
        // is waiting to be processed or its light update is coalescing.
        for (ChunkPos pos : DownloadManager.pendingChunkCaptures(dimension)) {
            int x = pos.getMinBlockX() >> 4;
            int z = pos.getMinBlockZ() >> 4;
            if (inRange(x, z, minX, maxX, minZ, maxZ)) {
                put(byChunk, x, z, height, YELLOW);
            }
        }

        List<OverlayChunk> chunks = new ArrayList<>(byChunk.values());
        cached = new OverlaySnapshot(List.copyOf(chunks));
        lastBuildMs = now;
        lastDimension = dimension;
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastRadius = radius;
        lastHeight = height;
        return cached;
    }

    private static void put(Map<Long, OverlayChunk> target, int x, int z, int y, int color) {
        target.put(ChunkStatusSnapshot.chunkKey(x, z), new OverlayChunk(x, z, y, color));
    }

    private static boolean inRange(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static int clampRadius(int radius) {
        return Math.max(6, Math.min(256, radius));
    }

    private static int clampHeight(int height) {
        return Math.max(-64, Math.min(319, height));
    }

    public record OverlayChunk(int chunkX, int chunkZ, int y, int color) {}

    public record OverlaySnapshot(List<OverlayChunk> chunks) {
        public static final OverlaySnapshot EMPTY = new OverlaySnapshot(List.of());

        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }
}
