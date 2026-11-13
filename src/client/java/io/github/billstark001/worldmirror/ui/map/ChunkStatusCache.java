package io.github.billstark001.worldmirror.ui.map;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.OperationFailures;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChunkStatusCache {
    private static final int MAX_CACHED_VIEWS = 8;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WorldMirror Chunk Status Loader");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<CacheKey, SnapshotEntry> snapshots = new LinkedHashMap<>(16, 0.75f, true);
    private static volatile CountSnapshot countSnapshot = new CountSnapshot(null, -1, 0L);
    private static long countGeneration;
    private static MirrorTarget counting;

    private ChunkStatusCache() {}

    public static synchronized ChunkStatusSnapshot getOrScheduleRefresh(
            StatusTarget target, ResourceKey<Level> dimension, long refreshIntervalMs) {
        if (target == null)
            return ChunkStatusSnapshot.EMPTY;
        CacheKey key = new CacheKey(target.mirror(), dimension != null ? dimension : Level.OVERWORLD);
        long now = System.currentTimeMillis();
        SnapshotEntry entry = snapshots.get(key);
        if (entry == null) {
            if (snapshots.size() >= MAX_CACHED_VIEWS) {
                var idle = snapshots.entrySet().iterator();
                while (idle.hasNext()) {
                    if (idle.next().getValue().loading)
                        continue;
                    idle.remove();
                    break;
                }
                if (snapshots.size() >= MAX_CACHED_VIEWS)
                    return ChunkStatusSnapshot.EMPTY;
            }
            entry = new SnapshotEntry();
            snapshots.put(key, entry);
        }
        if (!entry.loading && now - entry.loadedAtMs >= refreshIntervalMs)
            scheduleRefresh(key, entry);
        return entry.snapshot;
    }

    private static void scheduleRefresh(CacheKey key, SnapshotEntry entry) {
        long generation = entry.generation;
        ChunkStatusSnapshot previous = entry.snapshot;
        entry.loading = true;
        EXECUTOR.execute(() -> {
            try {
                ChunkStatusSnapshot loaded = loadSnapshot(key, previous);
                synchronized (ChunkStatusCache.class) {
                    entry.loadedAtMs = generation == entry.generation ? System.currentTimeMillis() : 0L;
                    entry.snapshot = loaded;
                }
            } finally {
                synchronized (ChunkStatusCache.class) {
                    entry.loading = false;
                }
            }
        });
    }

    public static synchronized void invalidate() {
        snapshots.clear();
        countGeneration++;
        countSnapshot = new CountSnapshot(null, -1, 0L);
    }

    /** Refresh the matching saved data after a committed export, even while a read is in flight. */
    public static synchronized void invalidateSaved(Path worldFolder, String nodeId, String sourceId) {
        MirrorTarget target = new MirrorTarget(worldFolder, nodeId, sourceId);
        snapshots.forEach((key, entry) -> {
            if (target.equals(key.mirror()))
                entry.invalidate();
        });
        CountSnapshot count = countSnapshot;
        if (target.equals(count.key()))
            invalidateCount(count);
        else if (target.equals(counting))
            countGeneration++;
    }

    private static void invalidateCount(CountSnapshot current) {
        countGeneration++;
        countSnapshot = new CountSnapshot(current.key(), current.count(), 0L);
    }

    /** Polls a cheap, source-wide durable count without blocking the UI thread. */
    public static int getOrScheduleSavedCount(MirrorTarget key, long refreshIntervalMs) {
        if (key == null)
            return -1;
        long now = System.currentTimeMillis();
        CountSnapshot current = countSnapshot;
        if (!key.equals(current.key()) || now - current.loadedAtMs() >= refreshIntervalMs)
            scheduleCount(key);
        return key.equals(current.key()) ? current.count() : -1;
    }

    private static synchronized void scheduleCount(MirrorTarget key) {
        if (counting != null)
            return;
        long generation = countGeneration;
        counting = key;
        EXECUTOR.execute(() -> {
            try {
                int loaded = key.matchesSavedIdentity()
                        ? ChunkDatabase.countAllReadOnly(key.folder(), key.sourceId())
                        : -1;
                if (loaded >= 0 && !key.matchesSavedIdentity())
                    loaded = -1;
                synchronized (ChunkStatusCache.class) {
                    if (generation == countGeneration) {
                        countSnapshot = new CountSnapshot(key, loaded, System.currentTimeMillis());
                    }
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
        StatusTarget opened = openedTarget(client);
        return opened == null ? downloadTarget(client) : opened;
    }

    public static StatusTarget openedTarget(Minecraft client) {
        if (client == null || client.level == null)
            return null;
        MirrorWorldContext.Snapshot current = MirrorWorldContext.current();
        var metadata = current.metadata();
        return !current.isMirror() || current.worldFolder() == null || metadata == null
                ? null
                : new StatusTarget(
                          new MirrorTarget(current.worldFolder(), metadata.nodeId, metadata.sourceId), true);
    }

    public static StatusTarget downloadTarget(Minecraft client) {
        if (client == null || client.level == null)
            return null;
        var graphs = WorldGraphService.instance();
        if (!graphs.observationIsCurrent(client))
            return null;
        var target = DownloadManager.captureTarget();
        if (target == null && !DownloadManager.isNewMirrorSelected())
            target = graphs.target(graphs.source());
        if (target != null) {
            var installed = graphs.targetById(target.nodeId());
            if (installed == null || !installed.parentNodeId().equals(target.parentNodeId())
                    || !installed.sourceId().equals(target.sourceId()))
                return null;
            target = installed;
        }
        return target == null
                ? null
                : new StatusTarget(
                          new MirrorTarget(target.folder(), target.nodeId(), target.sourceId()), false);
    }

    private static ChunkStatusSnapshot loadSnapshot(CacheKey key, ChunkStatusSnapshot previous) {
        MirrorTarget target = key.mirror();
        try {
            if (!target.matchesSavedIdentity())
                return ChunkStatusSnapshot.EMPTY;
            String dimensionId = key.dimension().identifier().toString();
            List<ChunkDatabase.ChunkRecord> records =
                    ChunkDatabase.queryAllReadOnly(target.folder(), target.sourceId(), dimensionId);
            Set<ChunkPos> conflicts = ConflictManager.listConflicts(target.folder(), key.dimension());
            if (!target.matchesSavedIdentity())
                return ChunkStatusSnapshot.EMPTY;
            OperationFailures.recovered(target.nodeId(), "status_read");
            if (previous.matchesData(records, conflicts))
                return previous;
            return new ChunkStatusSnapshot(records, conflicts);
        } catch (Exception t) {
            WMLogger.warnRateLimited(
                    "chunk-status-snapshot", 30_000L, "Chunk status snapshot load failed", t);
            OperationFailures.report(target.nodeId(), "status_read", target.folder(), t);
            return target.matchesSavedIdentity() ? previous : ChunkStatusSnapshot.EMPTY;
        }
    }

    private record CacheKey(MirrorTarget mirror, ResourceKey<Level> dimension) {}
    private record CountSnapshot(MirrorTarget key, int count, long loadedAtMs) {}
    private static final class SnapshotEntry {
        ChunkStatusSnapshot snapshot = ChunkStatusSnapshot.EMPTY;
        long loadedAtMs;
        long generation;
        boolean loading;

        void invalidate() {
            generation++;
            loadedAtMs = 0L;
        }
    }

    public record StatusTarget(MirrorTarget mirror, boolean currentWorldIsMirror) {}
}
