package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.util.WMLogger;
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

    private static volatile SnapshotState snapshotState =
            new SnapshotState(null, ChunkStatusSnapshot.EMPTY, 0L);
    private static final AtomicLong INVALIDATION = new AtomicLong();
    private static volatile CompletableFuture<?> refreshFuture;
    private static volatile CountSnapshot countSnapshot = new CountSnapshot(null, -1, 0L);
    private static volatile CompletableFuture<?> countFuture;

    private ChunkStatusCache() {}

    public static ChunkStatusSnapshot getOrScheduleRefresh(Minecraft client,
                                                           ResourceKey<Level> dimension,
                                                           long refreshIntervalMs) {
        CacheKey key = createKey(client, dimension);
        if (key == null) return ChunkStatusSnapshot.EMPTY;

        long now = System.currentTimeMillis();
        SnapshotState current = snapshotState;
        boolean contextChanged = !key.equals(current.key());
        boolean stale = now - current.loadedAtMs() >= refreshIntervalMs;
        if ((contextChanged || stale) && (refreshFuture == null || refreshFuture.isDone())) {
            long invalidation = INVALIDATION.get();
            refreshFuture = CompletableFuture.runAsync(() -> {
                ChunkStatusSnapshot loaded = loadSnapshot(key);
                synchronized (ChunkStatusCache.class) {
                    long loadedAtMs = invalidation == INVALIDATION.get()
                            ? System.currentTimeMillis() : 0L;
                    snapshotState = new SnapshotState(key, loaded, loadedAtMs);
                }
            }, EXECUTOR);
        }
        return key.equals(current.key()) ? current.snapshot() : ChunkStatusSnapshot.EMPTY;
    }

    public static synchronized void invalidate() {
        INVALIDATION.incrementAndGet();
        SnapshotState current = snapshotState;
        snapshotState = new SnapshotState(current.key(), current.snapshot(), 0L);
        CountSnapshot currentCount = countSnapshot;
        countSnapshot = new CountSnapshot(currentCount.key(), currentCount.count(), 0L);
    }

    /** Polls a cheap, source-wide durable count without blocking the UI thread. */
    public static int getOrScheduleSavedCount(Path worldFolder, String sourceId,
                                              long refreshIntervalMs) {
        if (worldFolder == null || sourceId == null) return -1;
        CountKey key = new CountKey(worldFolder.toAbsolutePath().normalize(), sourceId);
        long now = System.currentTimeMillis();
        CountSnapshot current = countSnapshot;
        if ((!key.equals(current.key()) || now - current.loadedAtMs() >= refreshIntervalMs)
                && (countFuture == null || countFuture.isDone())) {
            long invalidation = INVALIDATION.get();
            countFuture = CompletableFuture.runAsync(() -> {
                int loaded = ChunkDatabase.countAllReadOnly(key.worldFolder(), key.sourceId());
                synchronized (ChunkStatusCache.class) {
                    long loadedAtMs = invalidation == INVALIDATION.get()
                            ? System.currentTimeMillis() : 0L;
                    countSnapshot = new CountSnapshot(key, loaded, loadedAtMs);
                }
            }, EXECUTOR);
        }
        return key.equals(current.key()) ? current.count() : -1;
    }

    /**
     * Resolves the save whose persistent capture state should be drawn.  When a
     * mirror save itself is open, its own database is the authoritative source;
     * otherwise the source world's configured output mirror remains the target.
     */
    public static StatusTarget targetFor(Minecraft client) {
        MirrorWorldContext.Snapshot current = MirrorWorldContext.current();
        if (current.isMirror() && current.worldFolder() != null && current.sourceId() != null) {
            return new StatusTarget(current.worldFolder(), current.sourceId(), true);
        }
        if (client == null) return null;
        String sourceId = WorldMetadata.detectSourceId(client);
        Path worldFolder = DownloadManager.previewOutputPath(client);
        if (sourceId == null || worldFolder == null) return null;
        return new StatusTarget(worldFolder, sourceId, false);
    }

    private static CacheKey createKey(Minecraft client, ResourceKey<Level> dimension) {
        ResourceKey<Level> effectiveDimension = dimension != null ? dimension : Level.OVERWORLD;
        StatusTarget target = targetFor(client);
        if (target == null) return null;
        return new CacheKey(target.worldFolder(), target.sourceId(), effectiveDimension);
    }

    private static ChunkStatusSnapshot loadSnapshot(CacheKey key) {
        try {
            String dimensionId = key.dimension().identifier().toString();
            List<ChunkDatabase.ChunkRecord> records =
                    ChunkDatabase.queryAllReadOnly(key.worldFolder(), key.sourceId(), dimensionId);
            Set<ChunkPos> conflicts = ConflictManager.listConflicts(key.worldFolder(), key.dimension());
            io.github.billstark001.worldmirror.download.OperationFailures.recovered(key.sourceId(), "status_read");
            SnapshotState current = snapshotState;
            if (key.equals(current.key()) && current.snapshot().matchesData(records, conflicts)) {
                return current.snapshot();
            }
            return new ChunkStatusSnapshot(records, conflicts);
        } catch (Exception t) {
            WMLogger.warnRateLimited("chunk-status-snapshot", 30_000L,
                    "Chunk status snapshot load failed", t);
            io.github.billstark001.worldmirror.download.OperationFailures.report(
                    key.sourceId(), "status_read", key.worldFolder(), t);
            SnapshotState current = snapshotState;
            return key.equals(current.key()) ? current.snapshot() : ChunkStatusSnapshot.EMPTY;
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

    private record CountKey(Path worldFolder, String sourceId) {}
    private record CountSnapshot(CountKey key, int count, long loadedAtMs) {}
    private record SnapshotState(CacheKey key, ChunkStatusSnapshot snapshot, long loadedAtMs) {}

    public record StatusTarget(Path worldFolder, String sourceId, boolean currentWorldIsMirror) {}
}
