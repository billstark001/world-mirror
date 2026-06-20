package net.billstark001.worldmirror.ui;

import net.billstark001.worldmirror.conflict.ConflictManager;
import net.billstark001.worldmirror.download.ChunkDatabase;
import net.billstark001.worldmirror.download.DownloadManager;
import net.billstark001.worldmirror.download.WorldMetadata;
import net.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkStatusCache {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WorldMirror Chunk Status Loader");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicLong VERSION = new AtomicLong();

    private static volatile ChunkStatusSnapshot snapshot = ChunkStatusSnapshot.EMPTY;
    private static volatile CacheKey snapshotKey;
    private static volatile long lastRefreshMs;
    private static volatile CompletableFuture<?> refreshFuture;

    private ChunkStatusCache() {}

    public static ChunkStatusSnapshot loadNow(Minecraft client, ResourceKey<Level> dimension) {
        CacheKey key = createKey(client, dimension);
        if (key == null) {
            snapshot = ChunkStatusSnapshot.EMPTY;
            snapshotKey = null;
            return snapshot;
        }
        ChunkStatusSnapshot loaded = loadSnapshot(key);
        snapshot = loaded;
        snapshotKey = key;
        lastRefreshMs = System.currentTimeMillis();
        return loaded;
    }

    public static ChunkStatusSnapshot getOrScheduleRefresh(Minecraft client,
                                                           ResourceKey<Level> dimension,
                                                           long refreshIntervalMs) {
        CacheKey key = createKey(client, dimension);
        if (key == null) return ChunkStatusSnapshot.EMPTY;

        long now = System.currentTimeMillis();
        boolean contextChanged = !key.equals(snapshotKey);
        boolean stale = now - lastRefreshMs >= refreshIntervalMs;
        if ((contextChanged || stale) && (refreshFuture == null || refreshFuture.isDone())) {
            refreshFuture = CompletableFuture.runAsync(() -> {
                ChunkStatusSnapshot loaded = loadSnapshot(key);
                snapshot = loaded;
                snapshotKey = key;
                lastRefreshMs = System.currentTimeMillis();
            }, EXECUTOR);
        }
        return key.equals(snapshotKey) ? snapshot : ChunkStatusSnapshot.EMPTY;
    }

    public static void invalidate() {
        lastRefreshMs = 0L;
    }

    private static CacheKey createKey(Minecraft client, ResourceKey<Level> dimension) {
        if (client == null) return null;
        ResourceKey<Level> effectiveDimension = dimension != null ? dimension : Level.OVERWORLD;
        String sourceId = WorldMetadata.detectSourceId(client);
        Path worldFolder = DownloadManager.getOutputPath(client);
        if (sourceId == null || worldFolder == null) return null;
        return new CacheKey(worldFolder, sourceId, effectiveDimension);
    }

    private static ChunkStatusSnapshot loadSnapshot(CacheKey key) {
        try {
            String dimensionId = key.dimension().identifier().toString();
            List<ChunkDatabase.ChunkRecord> records =
                    ChunkDatabase.queryAllReadOnly(key.worldFolder(), key.sourceId(), dimensionId);
            Set<ChunkPos> conflicts = ConflictManager.listConflicts(key.worldFolder(), key.dimension());
            return new ChunkStatusSnapshot(records, conflicts, VERSION.incrementAndGet());
        } catch (Throwable t) {
            WMLogger.warn("Chunk status snapshot load failed: " + t.getMessage());
            return ChunkStatusSnapshot.EMPTY;
        }
    }

    private record CacheKey(Path worldFolder, String sourceId, ResourceKey<Level> dimension) {
        private CacheKey {
            worldFolder = worldFolder.toAbsolutePath().normalize();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CacheKey key)) return false;
            return Objects.equals(worldFolder, key.worldFolder)
                    && Objects.equals(sourceId, key.sourceId)
                    && Objects.equals(dimension, key.dimension);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldFolder, sourceId, dimension);
        }
    }
}
