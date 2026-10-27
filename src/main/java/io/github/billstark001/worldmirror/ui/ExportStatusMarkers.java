package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ExportStatus;

/** Failure and conflict markers for the built-in chunk map, independent of base colors. */
public final class ExportStatusMarkers {
    public static final int CONFLICT = 0xFFB060FF;
    public static final int FAILED = 0xFFFF4040;
    public static final int PARTIAL = 0xFFFF9090;
    private static final int SAVED_PART = 0xFF40E080;
    private static final int BORDER = 0xFF202020;
    private ExportStatusMarkers() { }
    @FunctionalInterface public interface Rects {
        void fill(double left, double top, double right, double bottom, int color);
    }
    /** Small cells use a warning dot; zero means that no export marker is needed. */
    public static int compact(ExportStatus.Counts counts) {
        if (counts.failed() > 0) return FAILED;
        if (counts.partial() > 0) return PARTIAL;
        return counts.unresolved() > 0 ? FAILED : 0;
    }
    /** Coordinates are fractions of a bucket. Counts retain failures hidden by aggregation. */
    public static void draw(ExportStatus.Counts counts, boolean conflict, Rects target) {
        drawConflict(conflict, target);
        if (counts.failed() > 0 || counts.partial() > 0) {
            target.fill(.48, .48, .94, .94, BORDER);
            if (counts.failed() > 0) {
                target.fill(.55, .55, .65, .65, FAILED);
                target.fill(.65, .65, .77, .77, FAILED);
                target.fill(.77, .77, .87, .87, FAILED);
                target.fill(.77, .55, .87, .65, FAILED);
                target.fill(.55, .77, .65, .87, FAILED);
            } else {
                target.fill(.55, .55, .71, .87, FAILED);
                target.fill(.71, .55, .87, .87, SAVED_PART);
            }
        } else if (counts.unresolved() > 0) {
            // Retrying a write does not resolve its previous failure.
            target.fill(.25, .08, .75, .18, FAILED);
        }
    }
    private static void drawConflict(boolean conflict, Rects target) {
        if (conflict) {
            target.fill(0, 0, 1, .08, CONFLICT);
            target.fill(0, .92, 1, 1, CONFLICT);
            target.fill(0, .08, .08, .92, CONFLICT);
            target.fill(.92, .08, 1, .92, CONFLICT);
        }
    }
}
