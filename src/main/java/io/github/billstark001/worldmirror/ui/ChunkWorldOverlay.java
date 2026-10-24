package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.ExportStatus;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts bounded, immutable geometry data before the world drawing phase.
 * The in-world overlay originated in PR #14 by cev-api.
 */
public final class ChunkWorldOverlay {
    private static final long PERSISTENT_REFRESH_INTERVAL_MS = 5_000L;
    private static final int MAX_BUCKET_CELLS = 4_096;
    private static final WeakReference<Object> NO_LEVEL = new WeakReference<>(null);

    private static volatile OverlaySnapshot cached = OverlaySnapshot.EMPTY;
    private static boolean captureActive;
    private static long lastBuildMs;
    private static WeakReference<Object> lastLevel = NO_LEVEL;
    private static ViewState lastView;
    private static final GeometryCache geometryCache = new GeometryCache();
    private static final ChunkViewportCache viewportCache = new ChunkViewportCache();
    private record ViewState(ResourceKey<Level> dimension, ChunkBounds range, Settings settings) { }

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
        ChunkBounds range = new ChunkBounds(centerX - radius, centerX + radius, centerZ - radius, centerZ + radius);
        long rebuildIntervalMs = ChunkViewportCache.refreshInterval(range);
        Settings settings = new Settings(height, sessionStartedAtMs, showInnerGrid, opacityPercent, colorMode);
        ViewState view = new ViewState(dimension, range, settings);
        if (now >= lastBuildMs && now - lastBuildMs < rebuildIntervalMs
                && client.level == lastLevel.get() && view.equals(lastView)) return;
        ChunkStatusSnapshot persistent = ChunkStatusCache.getOrScheduleRefresh(client, dimension, PERSISTENT_REFRESH_INTERVAL_MS);
        if (client.level != lastLevel.get() || lastView == null || !dimension.equals(lastView.dimension())) {
            geometryCache.clear();
            viewportCache.clear();
        }
        LiveChunkStatusSnapshot live = viewportCache.get(client, dimension, range, persistent, now);
        cached = geometryCache.get(persistent, live, range, settings, now);
        lastBuildMs = now;
        lastView = view;
        lastLevel = new WeakReference<>(client.level);
    }

    private static void deactivate() {
        if (!captureActive) return;
        captureActive = false;
        cached = OverlaySnapshot.EMPTY;
        geometryCache.clear();
        viewportCache.clear();
        lastView = null;
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

    record Settings(int height, long sessionStartedAtMs, boolean innerGrid, int opacityPercent,
                    WorldChunkOverlayColorMode mode) { }

    static OverlaySnapshot build(ChunkStatusSnapshot persistent, LiveChunkStatusSnapshot live,
                                 ChunkBounds range, Settings settings, long now) {
        ChunkBucketLayout layout = ChunkBucketLayout.withinBudget(range, MAX_BUCKET_CELLS);
        Map<Long, ChunkTileGeometry.Cell> cells = new Long2ObjectOpenHashMap<>();
        ArrayList<OverlayMark> marks = new ArrayList<>();
        ChunkBucketIndex.combine(persistent.index(), live.index(), layout).forEach((key, summary) -> {
            int color = ChunkMapStyle.world(summary, settings.mode(), settings.sessionStartedAtMs(), now);
            if (color != 0) cells.put(key, ChunkTileGeometry.Cell.world(ChunkBounds.x(key), ChunkBounds.z(key), color));
            if (summary.exports().total() > 0 || summary.conflict()) {
                var bounds = layout.clipped(ChunkBounds.x(key), ChunkBounds.z(key));
                marks.add(new OverlayMark(bounds.minX(), bounds.maxX() + 1, bounds.minZ(), bounds.maxZ() + 1,
                        settings.height(), summary.exports(), summary.conflict()));
            }
        });
        int size = layout.size();
        ArrayList<OverlayChunk> rectangles = new ArrayList<>();
        for (var rectangle : ChunkTileGeometry.rectangles(cells)) {
            rectangles.add(new OverlayChunk(
                    coordinate((long) rectangle.minX() * size, range.minX(), (long) range.maxX() + 1),
                    coordinate(((long) rectangle.maxX() + 1) * size, range.minX(), (long) range.maxX() + 1),
                    coordinate((long) rectangle.minZ() * size, range.minZ(), (long) range.maxZ() + 1),
                    coordinate(((long) rectangle.maxZ() + 1) * size, range.minZ(), (long) range.maxZ() + 1),
                    settings.height(), rectangle.color()));
        }
        ArrayList<OverlayEdge> edges = new ArrayList<>();
        for (var edge : ChunkTileGeometry.boundaries(cells, true, settings.innerGrid())) {
            long fixed = edge.fixed() * size, start = edge.start() * size, end = edge.end() * size;
            int x1 = coordinate(edge.vertical() ? fixed : start, range.minX(), (long) range.maxX() + 1);
            int x2 = coordinate(edge.vertical() ? fixed : end, range.minX(), (long) range.maxX() + 1);
            int z1 = coordinate(edge.vertical() ? start : fixed, range.minZ(), (long) range.maxZ() + 1);
            int z2 = coordinate(edge.vertical() ? end : fixed, range.minZ(), (long) range.maxZ() + 1);
            edges.add(new OverlayEdge(x1, z1, x2, z2, settings.height(), edge.color(), edge.interior(), edge.outwardPositive()));
        }
        return new OverlaySnapshot(List.copyOf(rectangles), List.copyOf(edges), settings.innerGrid(), settings.opacityPercent(), List.copyOf(marks));
    }

    private static int coordinate(long coordinate, long minimum, long maximum) {
        return Math.toIntExact(Math.max(minimum, Math.min(maximum, coordinate)));
    }

    /** A geometry key contains all semantic inputs; time affects MAP colors only. */
    static final class GeometryCache {
        private record Key(ChunkStatusSnapshot persistent, LiveChunkStatusSnapshot live, ChunkBounds range,
                           Settings settings, long colorEpoch) { }
        private Key key;
        private OverlaySnapshot snapshot;
        OverlaySnapshot get(ChunkStatusSnapshot persistent, LiveChunkStatusSnapshot live, ChunkBounds range,
                            Settings settings, long now) {
            Key next = new Key(persistent, live, range, settings,
                    settings.mode() == WorldChunkOverlayColorMode.MAP ? Math.floorDiv(now, 1_000L) : 0L);
            if (next.equals(key)) return snapshot;
            snapshot = build(persistent, live, range, settings, now);
            key = next;
            return snapshot;
        }
        void clear() { key = null; snapshot = null; }
    }

    public record OverlayChunk(int startChunkX, int endChunkX,
                               int startChunkZ, int endChunkZ, int y, int color) {}

    public record OverlayEdge(int startChunkX, int startChunkZ,
                              int endChunkX, int endChunkZ, int y, int color,
                              boolean sameColorInterior, boolean outwardPositive) {}

    public record OverlayMark(int startChunkX, int endChunkX, int startChunkZ, int endChunkZ, int y,
                              ExportStatus.Counts counts, boolean conflict) { }

    public record OverlaySnapshot(List<OverlayChunk> chunks, List<OverlayEdge> edges,
                                  boolean showInnerGrid, int opacityPercent, List<OverlayMark> marks) {
        public static final OverlaySnapshot EMPTY =
                new OverlaySnapshot(List.of(), List.of(), true, 60, List.of());

        public boolean isEmpty() {
            return chunks.isEmpty() && marks.isEmpty() || opacityPercent <= 0;
        }
    }
}
