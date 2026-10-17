package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts bounded, immutable geometry data before the world drawing phase.
 * The in-world overlay originated in PR #14 by cev-api.
 */
public final class ChunkWorldOverlay {
    private static final long REBUILD_INTERVAL_MS = 500L;
    private static final long PERSISTENT_REFRESH_INTERVAL_MS = 5_000L;
    private static final int MAX_BUCKET_CELLS = 4_096;
    private static final int YELLOW = 0xFFFFD21F;
    private static final int GREEN = 0xFF20E060;
    private static final int BLUE = 0xFF2878E0;
    private static final WeakReference<Object> NO_LEVEL = new WeakReference<>(null);

    private static volatile OverlaySnapshot cached = OverlaySnapshot.EMPTY;
    private static boolean captureActive;
    private static long lastBuildMs;
    private static ResourceKey<Level> lastDimension;
    private static WeakReference<Object> lastLevel = NO_LEVEL;
    private static int lastCenterX;
    private static int lastCenterZ;
    private static int lastRadius;
    private static int lastHeight;
    private static WorldChunkOverlayColorMode lastColorMode;
    private static boolean lastShowInnerGrid;
    private static int lastOpacityPercent;
    private static long lastSessionStartedAtMs;

    private ChunkWorldOverlay() {}

    /** Called during extraction (or the client tick on older Minecraft versions). */
    public static synchronized void capture(Minecraft client, boolean guiHidden) {
        if (client == null || client.level == null || client.player == null) {
            deactivate();
            return;
        }
        ModConfig.ChunkMapConfig config = ModConfig.get().chunkMap;
        if ((!config.showWorldChunkOverlay && !config.showMirrorWorldChunkOverlay)
                || guiHidden && !config.showWorldChunkOverlayWithHiddenGui
                || config.worldChunkOverlayOpacityPercent <= 0
                || !config.isWorldChunkOverlayEnabled(MirrorWorldContext.current().isMirror())) {
            deactivate();
            return;
        }
        captureActive = true;
        ResourceKey<Level> dimension = client.level.dimension();
        int centerX = client.player.getBlockX() >> 4;
        int centerZ = client.player.getBlockZ() >> 4;
        int radius = Math.max(8, Math.min(16_384, config.worldChunkOverlayRenderDistance));
        int height = Math.max(-64, Math.min(319, config.worldChunkOverlayHeight));
        WorldChunkOverlayColorMode colorMode = config.worldChunkOverlayColorMode != null
                ? config.worldChunkOverlayColorMode : WorldChunkOverlayColorMode.SESSION;
        boolean showInnerGrid = config.showWorldChunkOverlayInnerGrid;
        int opacityPercent = Math.max(0, Math.min(100, config.worldChunkOverlayOpacityPercent));
        long now = System.currentTimeMillis();
        long sessionStartedAtMs = DownloadManager.downloadSessionStartedAtMs();
        // Wide views can scan many saved records; their coarse tiles do not
        // need the same update cadence as a nearby per-chunk view.
        long rebuildIntervalMs = radius <= 256 ? REBUILD_INTERVAL_MS
                : radius <= 2_048 ? 1_000L : 5_000L;
        if (now - lastBuildMs < rebuildIntervalMs
                && client.level == lastLevel.get() && dimension.equals(lastDimension)
                && sessionStartedAtMs == lastSessionStartedAtMs
                && centerX == lastCenterX && centerZ == lastCenterZ
                && radius == lastRadius && height == lastHeight
                && colorMode == lastColorMode
                && showInnerGrid == lastShowInnerGrid
                && opacityPercent == lastOpacityPercent) return;

        ChunkStatusSnapshot persistent = ChunkStatusCache.getOrScheduleRefresh(
                client, dimension, PERSISTENT_REFRESH_INTERVAL_MS);
        ChunkMapView.ChunkRange range = new ChunkMapView.ChunkRange(
                centerX - radius, centerX + radius, centerZ - radius, centerZ + radius);
        LiveChunkStatusSnapshot live = LiveChunkStatusSnapshot.capture(dimension, range, persistent);
        cached = build(persistent, live, range, height, sessionStartedAtMs,
                showInnerGrid, opacityPercent, colorMode, now);
        lastBuildMs = now;
        lastDimension = dimension;
        lastLevel = new WeakReference<>(client.level);
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastRadius = radius;
        lastHeight = height;
        lastColorMode = colorMode;
        lastShowInnerGrid = showInnerGrid;
        lastOpacityPercent = opacityPercent;
        lastSessionStartedAtMs = sessionStartedAtMs;
    }

    private static void deactivate() {
        if (!captureActive) return;
        captureActive = false;
        cached = OverlaySnapshot.EMPTY;
        lastLevel = NO_LEVEL;
        lastBuildMs = 0L;
    }

    /** Drawing callbacks only consume this immutable value. */
    public static OverlaySnapshot snapshot() {
        return cached;
    }

    /** Toggles and saves the setting for the world currently being played. */
    public static synchronized void toggleForCurrentWorld(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return;
        ModConfig config = ModConfig.get();
        boolean mirrorWorld = MirrorWorldContext.current().isMirror();
        config.chunkMap.toggleWorldChunkOverlay(mirrorWorld);
        config.save();
        if (!config.chunkMap.isWorldChunkOverlayEnabled(mirrorWorld)) {
            deactivate();
        }
    }

    static OverlaySnapshot build(ChunkStatusSnapshot persistent, LiveChunkStatusSnapshot live,
                                 ChunkMapView.ChunkRange range, int height, long sessionStartedAtMs) {
        return build(persistent, live, range, height, sessionStartedAtMs, true, 60);
    }

    static OverlaySnapshot build(ChunkStatusSnapshot persistent, LiveChunkStatusSnapshot live,
                                 ChunkMapView.ChunkRange range, int height, long sessionStartedAtMs,
                                 boolean showInnerGrid, int opacityPercent) {
        return build(persistent, live, range, height, sessionStartedAtMs,
                showInnerGrid, opacityPercent, WorldChunkOverlayColorMode.SESSION, 0L);
    }

    static OverlaySnapshot build(ChunkStatusSnapshot persistent, LiveChunkStatusSnapshot live,
                                 ChunkMapView.ChunkRange range, int height, long sessionStartedAtMs,
                                 boolean showInnerGrid, int opacityPercent,
                                 WorldChunkOverlayColorMode colorMode, long now) {
        int bucketSize = ChunkMapAggregation.bucketSizeFor(range.minX(), range.maxX(),
                range.minZ(), range.maxZ(), MAX_BUCKET_CELLS);
        Map<Long, Cell> cells = switch (colorMode) {
            case SESSION -> sessionCells(persistent, live, range, bucketSize, sessionStartedAtMs);
            case MAP -> mapCells(persistent, live, range, bucketSize, now);
        };

        ArrayList<Cell> sorted = new ArrayList<>(cells.values());
        sorted.sort(Comparator.comparingInt((Cell cell) -> cell.z).thenComparingInt(cell -> cell.x));
        ArrayList<OverlayChunk> runs = new ArrayList<>();
        for (Cell cell : sorted) {
            int startX = Math.max(range.minX(), cell.x * bucketSize);
            int endX = Math.min(range.maxX() + 1, (cell.x + 1) * bucketSize);
            int startZ = Math.max(range.minZ(), cell.z * bucketSize);
            int endZ = Math.min(range.maxZ() + 1, (cell.z + 1) * bucketSize);
            if (!runs.isEmpty()) {
                OverlayChunk previous = runs.getLast();
                if (previous.endChunkX() == startX && previous.startChunkZ() == startZ
                        && previous.endChunkZ() == endZ && previous.color() == cell.color) {
                    runs.set(runs.size() - 1, new OverlayChunk(previous.startChunkX(),
                            endX, startZ, endZ, height, cell.color));
                    continue;
                }
            }
            runs.add(new OverlayChunk(startX, endX, startZ, endZ, height, cell.color));
        }
        // Combining matching runs across rows also avoids translucent side faces
        // inside a uniform area when the optional inner grid is hidden.
        Map<RunSpan, Integer> previousBySpan = new HashMap<>();
        ArrayList<OverlayChunk> rectangles = new ArrayList<>();
        for (OverlayChunk run : runs) {
            RunSpan span = new RunSpan(run.startChunkX(), run.endChunkX(), run.color());
            Integer previousIndex = previousBySpan.get(span);
            if (previousIndex != null) {
                OverlayChunk previous = rectangles.get(previousIndex);
                if (previous.endChunkZ() == run.startChunkZ()) {
                    rectangles.set(previousIndex, new OverlayChunk(previous.startChunkX(),
                            previous.endChunkX(), previous.startChunkZ(),
                            run.endChunkZ(), height, run.color()));
                    continue;
                }
            }
            previousBySpan.put(span, rectangles.size());
            rectangles.add(run);
        }
        ArrayList<OverlayEdge> edges = new ArrayList<>();
        for (Cell cell : sorted) {
            int startX = Math.max(range.minX(), cell.x * bucketSize);
            int endX = Math.min(range.maxX() + 1, (cell.x + 1) * bucketSize);
            int startZ = Math.max(range.minZ(), cell.z * bucketSize);
            int endZ = Math.min(range.maxZ() + 1, (cell.z + 1) * bucketSize);
            Cell north = cells.get(ChunkStatusSnapshot.chunkKey(cell.x, cell.z - 1));
            Cell west = cells.get(ChunkStatusSnapshot.chunkKey(cell.x - 1, cell.z));
            if (showInnerGrid || north == null || north.color != cell.color) {
                edges.add(new OverlayEdge(startX, startZ, endX, startZ,
                        height, cell.color, north != null && north.color == cell.color,
                        false));
            }
            if (showInnerGrid || west == null || west.color != cell.color) {
                edges.add(new OverlayEdge(startX, startZ, startX, endZ,
                        height, cell.color, west != null && west.color == cell.color,
                        false));
            }
            if (!cells.containsKey(ChunkStatusSnapshot.chunkKey(cell.x + 1, cell.z))) {
                edges.add(new OverlayEdge(endX, startZ, endX, endZ,
                        height, cell.color, false, true));
            }
            if (!cells.containsKey(ChunkStatusSnapshot.chunkKey(cell.x, cell.z + 1))) {
                edges.add(new OverlayEdge(startX, endZ, endX, endZ,
                        height, cell.color, false, true));
            }
        }
        return new OverlaySnapshot(List.copyOf(rectangles), List.copyOf(edges),
                showInnerGrid, opacityPercent);
    }

    private static Map<Long, Cell> sessionCells(ChunkStatusSnapshot persistent,
                                               LiveChunkStatusSnapshot live,
                                               ChunkMapView.ChunkRange range,
                                               int bucketSize, long sessionStartedAtMs) {
        Map<Long, Cell> cells = new HashMap<>();
        persistent.forEachRecordInRange(range.minX(), range.maxX(), range.minZ(), range.maxZ(), record -> {
            boolean captured = "world_mirror".equals(record.updateSource());
            if (!captured && !"replay_import".equals(record.updateSource())) return;
            int color = captured && sessionStartedAtMs > 0 && record.updateTime() >= sessionStartedAtMs
                    ? GREEN : BLUE;
            put(cells, record.x(), record.z(), bucketSize, color);
        });
        live.forEachRecord(record -> {
            if (!inRange(record, range)) return;
            int color = record.state() == LiveChunkStatusSnapshot.State.PENDING_CAPTURE ? YELLOW
                    : sessionStartedAtMs > 0 && record.capturedAtMs() >= sessionStartedAtMs ? GREEN : BLUE;
            put(cells, record.x(), record.z(), bucketSize, color);
        });
        return cells;
    }

    private static Map<Long, Cell> mapCells(ChunkStatusSnapshot persistent,
                                           LiveChunkStatusSnapshot live,
                                           ChunkMapView.ChunkRange range,
                                           int bucketSize, long now) {
        Map<Long, Cell> cells = new HashMap<>();
        // Reuse the maps' newest-record selection and age/source colors even at
        // coarse distances; session-color priority must not affect map colors.
        var saved = ChunkMapAggregation.aggregate(persistent, range.minX(), range.maxX(),
                range.minZ(), range.maxZ(), now, MAX_BUCKET_CELLS);
        for (var run : saved.runs()) {
            for (int x = run.startBucketX(); x <= run.endBucketX(); x++) {
                cells.put(ChunkStatusSnapshot.chunkKey(x, run.bucketZ()),
                        new Cell(x, run.bucketZ(), run.color()));
            }
        }
        Map<Long, LiveChunkStatusSnapshot.Record> liveBuckets = new HashMap<>();
        live.forEachRecord(record -> {
            if (!inRange(record, range)) return;
            long key = ChunkStatusSnapshot.chunkKey(
                    Math.floorDiv(record.x(), bucketSize), Math.floorDiv(record.z(), bucketSize));
            if (LiveChunkStatusSnapshot.hasPriorityOver(record, liveBuckets.get(key))) {
                liveBuckets.put(key, record);
            }
        });
        liveBuckets.forEach((key, record) -> cells.put(key, new Cell(
                Math.floorDiv(record.x(), bucketSize), Math.floorDiv(record.z(), bucketSize),
                ChunkMapView.liveColor(record, now))));
        return cells;
    }

    private static boolean inRange(LiveChunkStatusSnapshot.Record record, ChunkMapView.ChunkRange range) {
        return record.x() >= range.minX() && record.x() <= range.maxX()
                && record.z() >= range.minZ() && record.z() <= range.maxZ();
    }

    private static void put(Map<Long, Cell> cells, int x, int z, int bucketSize, int color) {
        int bucketX = Math.floorDiv(x, bucketSize);
        int bucketZ = Math.floorDiv(z, bucketSize);
        long key = ChunkStatusSnapshot.chunkKey(bucketX, bucketZ);
        Cell previous = cells.get(key);
        if (previous == null || priority(color) > priority(previous.color)) {
            cells.put(key, new Cell(bucketX, bucketZ, color));
        }
    }

    private static int priority(int color) {
        return color == YELLOW ? 3 : color == GREEN ? 2 : 1;
    }

    private record Cell(int x, int z, int color) {}

    private record RunSpan(int startChunkX, int endChunkX, int color) {}

    public record OverlayChunk(int startChunkX, int endChunkX,
                               int startChunkZ, int endChunkZ, int y, int color) {}

    public record OverlayEdge(int startChunkX, int startChunkZ,
                              int endChunkX, int endChunkZ, int y, int color,
                              boolean sameColorInterior, boolean outwardPositive) {}

    public record OverlaySnapshot(List<OverlayChunk> chunks, List<OverlayEdge> edges,
                                  boolean showInnerGrid, int opacityPercent) {
        public static final OverlaySnapshot EMPTY =
                new OverlaySnapshot(List.of(), List.of(), true, 60);

        public boolean isEmpty() {
            return chunks.isEmpty() || opacityPercent <= 0;
        }
    }
}
