package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode;
import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;

/** Color and status policies, independent of spatial indexing and drawing APIs. */
public final class ChunkMapStyle {
    static final int CONFLICT = 0xFFFF3030;
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
        double progress = progress(age);
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
        if (summary.newest() == null && !summary.conflict()) return null;
        int color = summary.newest() == null ? CONFLICT : saved(summary.newest(), now);
        int key = summary.conflict() ? 1_000_000 : color == EXTERNAL ? 900_000 : 2 + Math.min(7, ((color & 255) * 8) / 201);
        int borderColor = color;
        int priority = summary.conflict() ? 1_000_000 : (color >> 8) & 255;
        if (denseBoundary) {
            if (summary.conflict()) borderColor = CONFLICT;
            else if (color == EXTERNAL) priority = 900_000;
            else {
                int bucket = Math.min(7, (int) Math.floor(progress(now - summary.newest().updateTime()) * 8));
                key = 2 + bucket;
                borderColor = 0xFF000000 | ((int) Math.round(200 * (1 - bucket / 7.0)) << 8) | (int) Math.round(200 * bucket / 7.0);
                priority = (borderColor >> 8) & 255;
            }
        }
        return new ChunkTileGeometry.Cell(x, z, color, summary.conflict(), new ChunkTileGeometry.Border(key, borderColor, priority));
    }

    /** Earliest coarse boundary transition; callers can cache geometry until this deadline. */
    static long nextBoundaryChange(ChunkRecord record, long now) {
        if (record == null || !usesCaptureTimeGradient(record.updateSource())) return Long.MAX_VALUE;
        int bucket = Math.min(7, (int) Math.floor(progress(now - record.updateTime()) * 8));
        if (bucket == 7) return Long.MAX_VALUE;
        long nextAge = (long) Math.ceil(AGE_MIN_MS * Math.exp(AGE_LOG_RANGE * (bucket + 1) / 8.0));
        if (record.updateTime() > Long.MAX_VALUE - nextAge) return Long.MAX_VALUE;
        return Math.max(now + 1, record.updateTime() + nextAge);
    }

    private static double progress(long age) {
        if (age <= AGE_MIN_MS) return 0;
        if (age >= AGE_MAX_MS) return 1;
        return Math.log((double) age / AGE_MIN_MS) / AGE_LOG_RANGE;
    }
}
