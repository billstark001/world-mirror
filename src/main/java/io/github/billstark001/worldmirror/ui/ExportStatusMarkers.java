package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ExportStatus;

/** Small corner glyphs shared by screen maps and world geometry, independent of base colors. */
public final class ExportStatusMarkers {
    public static final int CONFLICT = 0xFFB060FF;
    public static final int QUEUED = 0xFFFFDA40;
    public static final int WRITING = 0xFFE0E0E0;
    public static final int FAILED = 0xFFFF4040;
    public static final int PARTIAL = 0xFFFF9090;
    public static final int SUCCEEDED = 0xFF40E080;
    private static final int BORDER = 0xFF202020;
    private ExportStatusMarkers() { }
    @FunctionalInterface public interface Rects {
        void fill(double left, double top, double right, double bottom, int color);
    }
    /** Pixel-sized cells cannot show a split glyph; keep partial failure distinct from success. */
    public static int compact(ExportStatus.Counts counts) {
        return switch (counts.dominant()) {
            case QUEUED -> QUEUED;
            case WRITING -> WRITING;
            case FAILED -> FAILED;
            case PARTIAL -> PARTIAL;
            case SUCCEEDED -> SUCCEEDED;
        };
    }
    /** Coarse world tiles use two quads per status instead of subpixel glyph details. */
    public static void drawCompact(ExportStatus.Counts counts, boolean conflict, Rects target) {
        drawConflict(conflict, target);
        if (counts.total() == 0) return;
        target.fill(.48, .48, .94, .94, BORDER);
        target.fill(.55, .55, .87, .87, compact(counts));
        drawUnresolved(counts, target);
    }
    /** Coordinates are fractions of a bucket. Counts retain failures hidden by aggregation. */
    public static void draw(ExportStatus.Counts counts, boolean conflict, Rects target) {
        drawConflict(conflict, target);
        if (counts.total() == 0) return;
        target.fill(.48, .48, .94, .94, BORDER);
        drawState(counts.dominant(), target);
        drawUnresolved(counts, target);
    }
    private static void drawConflict(boolean conflict, Rects target) {
        if (conflict) {
            target.fill(0, 0, 1, .08, CONFLICT);
            target.fill(0, .92, 1, 1, CONFLICT);
            target.fill(0, .08, .08, .92, CONFLICT);
            target.fill(.92, .08, 1, .92, CONFLICT);
        }
    }
    private static void drawState(ExportStatus.State state, Rects target) {
        switch (state) {
            case QUEUED -> {
                target.fill(.55, .55, .87, .64, QUEUED);
                target.fill(.55, .64, .64, .87, QUEUED);
            }
            case WRITING -> target.fill(.55, .55, .87, .87, WRITING);
            case FAILED -> {
                target.fill(.55, .55, .65, .65, FAILED);
                target.fill(.65, .65, .77, .77, FAILED);
                target.fill(.77, .77, .87, .87, FAILED);
                target.fill(.77, .55, .87, .65, FAILED);
                target.fill(.55, .77, .65, .87, FAILED);
            }
            case PARTIAL -> {
                target.fill(.55, .55, .71, .87, FAILED);
                target.fill(.71, .55, .87, .87, SUCCEEDED);
            }
            case SUCCEEDED -> {
                target.fill(.55, .67, .65, .77, SUCCEEDED);
                target.fill(.65, .77, .75, .87, SUCCEEDED);
                target.fill(.75, .55, .87, .77, SUCCEEDED);
            }
        }
    }
    private static void drawUnresolved(ExportStatus.Counts counts, Rects target) {
        if (counts.unresolved() > 0 && counts.failed() + counts.partial() == 0)
            target.fill(.25, .08, .75, .18, FAILED);
    }
}
