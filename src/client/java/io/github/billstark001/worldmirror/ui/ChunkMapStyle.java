package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode;
import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;

/** Color and status policies, independent of spatial indexing and drawing APIs. */
public final class ChunkMapStyle {
    static final int CONFLICT = ExportStatusMarkers.CONFLICT;
    private static final int EXTERNAL = 0xFFFF9000;
    private static final long AGE_MIN_MS = 10L * 60 * 1000;
    private static final long AGE_MAX_MS = 30L * 24 * 3600 * 1000;
    private static final double AGE_LOG_RANGE = Math.log((double) AGE_MAX_MS / AGE_MIN_MS);

    private ChunkMapStyle() { }

    public static boolean usesCaptureTimeGradient(String source) {
        return "world_mirror".equals(source) || "replay_import".equals(source);
    }

    public static int saved(ChunkRecord record, long now) {
        return record == null ? 0 : usesCaptureTimeGradient(record.updateSource()) ? freshness(now - record.updateTime()) : EXTERNAL;
    }

    public static int freshness(long age) {
        return freshness(progress(age));
    }

    private static int freshness(double progress) {
        return 0xFF000000 | ((int) (200 * (1 - progress)) << 8) | (int) (200 * progress);
    }

    static int live(LiveChunkStatusSnapshot.Record record, long now) {
        return switch (record.state()) {
            case PENDING_CAPTURE -> record.hasSavedRecord() ? 0xFFD8DC30 : 0xFFFFD21F;
            case PENDING_WRITE -> record.hasSavedRecord() ? 0xFF20E060 : 0xFF91E633;
            case CACHED -> freshness(now - record.capturedAtMs());
        };
    }

    static int world(ChunkBucketIndex.Summary summary, WorldChunkOverlayColorMode mode, long session, long now) {
        if (mode == WorldChunkOverlayColorMode.MAP) {
            if (summary.live() != null) return live(summary.live(), now);
            return summary.newest() != null ? saved(summary.newest(), now) : summary.conflict() ? CONFLICT : 0;
        }
        if (summary.pendingCapture()) return 0xFFFFD21F;
        if (session > 0 && (summary.newestMirrorTime() >= session || summary.newestLiveTime() >= session)) return 0xFF20E060;
        return summary.historicalCoverage() || summary.live() != null ? 0xFF2878E0 : 0;
    }

    static ChunkTileGeometry.Cell mapCell(int x, int z, ChunkBucketIndex.Summary summary, long now, boolean denseBoundary) {
        ChunkRecord newest = summary.newest();
        if (newest == null && !summary.conflict()) return null;
        double ageProgress = newest != null && usesCaptureTimeGradient(newest.updateSource())
                ? progress(now - newest.updateTime()) : -1.0D;
        int color = newest == null ? (denseBoundary ? 0 : CONFLICT) : ageProgress < 0 ? EXTERNAL : freshness(ageProgress);
        int key = summary.conflict() ? 1_000_000 : color == EXTERNAL ? 900_000 : 2 + Math.min(7, ((color & 255) * 8) / 201);
        int borderColor = color;
        int priority = summary.conflict() ? 1_000_000 : (color >> 8) & 255;
        if (denseBoundary) {
            if (summary.conflict()) borderColor = CONFLICT;
            else if (color == EXTERNAL) priority = 900_000;
            else {
                int bucket = Math.min(7, (int) (ageProgress * 8));
                key = 2 + bucket;
                borderColor = 0xFF000000 | ((int) Math.round(200 * (1 - bucket / 7.0)) << 8) | (int) Math.round(200 * bucket / 7.0);
                priority = (borderColor >> 8) & 255;
            }
        }
        return new ChunkTileGeometry.Cell(x, z, color, summary.conflict(), new ChunkTileGeometry.Border(key, borderColor, priority));
    }

    /** Age colors are constant until ten minutes and after thirty days. */
    static long nextFillRefresh(ChunkRecord record, long now, long intervalMs) {
        if (record == null || !usesCaptureTimeGradient(record.updateSource())) return Long.MAX_VALUE;
        long age = now - record.updateTime();
        if (age >= AGE_MAX_MS) return Long.MAX_VALUE;
        if (age >= AGE_MIN_MS) return now <= Long.MAX_VALUE - intervalMs ? now + intervalMs : Long.MAX_VALUE;
        return record.updateTime() <= Long.MAX_VALUE - AGE_MIN_MS
                ? record.updateTime() + AGE_MIN_MS : Long.MAX_VALUE;
    }

    private static double progress(long age) {
        if (age <= AGE_MIN_MS) return 0;
        if (age >= AGE_MAX_MS) return 1;
        return Math.log((double) age / AGE_MIN_MS) / AGE_LOG_RANGE;
    }
}
