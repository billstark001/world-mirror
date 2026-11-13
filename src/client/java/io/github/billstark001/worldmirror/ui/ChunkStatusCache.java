package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.OperationFailures;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChunkStatusCache {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WorldMirror Chunk Status Loader");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile SnapshotState snapshotState =
            new SnapshotState(null, ChunkStatusSnapshot.EMPTY, 0L);
    private static long snapshotGeneration;
    private static CacheKey refreshing;
    private static volatile CountSnapshot countSnapshot = new CountSnapshot(null, -1, 0L);
    private static long countGeneration;
    private static MirrorTarget counting;

    private ChunkStatusCache() {}

    public static ChunkStatusSnapshot getOrScheduleRefresh(StatusTarget target, ResourceKey<Level> dimension,
                                                           long refreshIntervalMs) {
        if (target == null) return ChunkStatusSnapshot.EMPTY;
        CacheKey key = new CacheKey(target.mirror(), dimension != null ? dimension : Level.OVERWORLD);
        long now = System.currentTimeMillis();
        SnapshotState current = snapshotState;
        boolean contextChanged = !key.equals(current.key());
        boolean stale = now - current.loadedAtMs() >= refreshIntervalMs;
        if (contextChanged || stale) scheduleRefresh(key);
        return key.equals(current.key()) ? current.snapshot() : ChunkStatusSnapshot.EMPTY;
    }

    private static synchronized void scheduleRefresh(CacheKey key) {
        if (refreshing != null) return;
        long generation = snapshotGeneration;
        refreshing = key;
        EXECUTOR.execute(() -> {
            try {
                ChunkStatusSnapshot loaded = loadSnapshot(key);
                synchronized (ChunkStatusCache.class) {
                    long loadedAt = generation == snapshotGeneration ? System.currentTimeMillis() : 0L;
                    snapshotState = new SnapshotState(key, loaded, loadedAt);
                }
            } finally {
                synchronized (ChunkStatusCache.class) {
                    refreshing = null;
                }
            }
        });
    }

    public static synchronized void invalidate() {
        invalidateSnapshot(snapshotState);
        invalidateCount(countSnapshot);
    }

    /** Refresh the matching saved data after a committed export, even while a read is in flight. */
    public static synchronized void invalidateSaved(Path worldFolder, String nodeId, String sourceId) {
        MirrorTarget target = new MirrorTarget(worldFolder, nodeId, sourceId);
        SnapshotState current = snapshotState;
        if (matches(target, current.key())) invalidateSnapshot(current);
        else if (matches(target, refreshing)) snapshotGeneration++;
        CountSnapshot count = countSnapshot;
        if (target.equals(count.key())) invalidateCount(count);
        else if (target.equals(counting)) countGeneration++;
    }

    private static void invalidateSnapshot(SnapshotState current) {
        snapshotGeneration++;
        snapshotState = new SnapshotState(current.key(), current.snapshot(), 0L);
    }

    private static void invalidateCount(CountSnapshot current) {
        countGeneration++;
        countSnapshot = new CountSnapshot(current.key(), current.count(), 0L);
    }

    /** Polls a cheap, source-wide durable count without blocking the UI thread. */
    public static int getOrScheduleSavedCount(MirrorTarget key, long refreshIntervalMs) {
        if (key == null) return -1;
        long now = System.currentTimeMillis();
        CountSnapshot current = countSnapshot;
        if (!key.equals(current.key()) || now - current.loadedAtMs() >= refreshIntervalMs) scheduleCount(key);
        return key.equals(current.key()) ? current.count() : -1;
    }

    private static synchronized void scheduleCount(MirrorTarget key) {
        if (counting != null) return;
        long generation = countGeneration;
        counting = key;
        EXECUTOR.execute(() -> {
            try {
                int loaded = hasIdentity(key) ? ChunkDatabase.countAllReadOnly(key.folder(), key.sourceId()) : -1;
                if (loaded >= 0 && !hasIdentity(key)) loaded = -1;
                synchronized (ChunkStatusCache.class) {
                    long loadedAt = generation == countGeneration ? System.currentTimeMillis() : 0L;
                    countSnapshot = new CountSnapshot(key, loaded, loadedAt);
                }
            } finally {
                synchronized (ChunkStatusCache.class) {
                    counting = null;
                }
            }
        });
    }

    /**
     * Resolves the save whose persistent capture state should be drawn.  When a
     * mirror save itself is open, its own database is the authoritative source;
     * otherwise the source world's configured output mirror remains the target.
     */
    public static StatusTarget targetFor(Minecraft client) {
        if (client == null || client.level == null) return null;
        MirrorWorldContext.Snapshot current = MirrorWorldContext.current();
        if (current.isMirror() && current.worldFolder() != null) {
            var metadata = current.metadata();
            return metadata == null ? null : new StatusTarget(
                    new MirrorTarget(current.worldFolder(), metadata.nodeId, metadata.sourceId), true);
        }
        var graphs = WorldGraphService.instance();
        if (!graphs.observationIsCurrent(client)) return null;
        var target = graphs.target(graphs.source());
        return target == null ? null
                : new StatusTarget(new MirrorTarget(target.folder(), target.nodeId(), target.sourceId()), false);
    }

    private static ChunkStatusSnapshot loadSnapshot(CacheKey key) {
        MirrorTarget target = key.mirror();
        try {
            if (!hasIdentity(target)) return ChunkStatusSnapshot.EMPTY;
            String dimensionId = key.dimension().identifier().toString();
            List<ChunkDatabase.ChunkRecord> records =
                    ChunkDatabase.queryAllReadOnly(target.folder(), target.sourceId(), dimensionId);
            Set<ChunkPos> conflicts = ConflictManager.listConflicts(target.folder(), key.dimension());
            if (!hasIdentity(target)) return ChunkStatusSnapshot.EMPTY;
            OperationFailures.recovered(target.nodeId(), "status_read");
            SnapshotState current = snapshotState;
            if (key.equals(current.key()) && current.snapshot().matchesData(records, conflicts)) {
                return current.snapshot();
            }
            return new ChunkStatusSnapshot(records, conflicts);
        } catch (Exception t) {
            WMLogger.warnRateLimited("chunk-status-snapshot", 30_000L,
                    "Chunk status snapshot load failed", t);
            OperationFailures.report(target.nodeId(), "status_read", target.folder(), t);
            SnapshotState current = snapshotState;
            return key.equals(current.key()) && hasIdentity(target) ? current.snapshot() : ChunkStatusSnapshot.EMPTY;
        }
    }

    static boolean hasIdentity(MirrorTarget target) {
        return WorldMetadata.loadIfPresent(target.folder()).filter(target::matches).isPresent();
    }

    private static boolean matches(MirrorTarget target, CacheKey key) {
        return key != null && target.equals(key.mirror());
    }

    private record CacheKey(MirrorTarget mirror, ResourceKey<Level> dimension) {}
    private record CountSnapshot(MirrorTarget key, int count, long loadedAtMs) {}
    private record SnapshotState(CacheKey key, ChunkStatusSnapshot snapshot, long loadedAtMs) {}

    public record StatusTarget(MirrorTarget mirror, boolean currentWorldIsMirror) {}
}
