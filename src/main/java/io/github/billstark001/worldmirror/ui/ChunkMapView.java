package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ChunkDatabase;

import java.util.Map;

/**
 * Library-independent chunk-map geometry and drawing.
 *
 * <p>The Minecraft and Xaero entry points adapt their concrete drawing APIs to
 * {@link Canvas}; this class owns viewport calculations, colour selection and
 * the draw-call ordering shared by both renderers.
 */
public final class ChunkMapView {
    public static final int CELL_SIZE_MIN = 1;
    public static final int CELL_SIZE_MAX = 16;
    public static final int CELL_SIZE_DEFAULT = 6;

    private static final int CHUNK_BLOCK_SIZE = 16;
    private static final int MAX_WORLD_CHUNK = 1_875_000;
    private static final int COLOR_FRESH_GREEN = 0xFF00C800;
    private static final int COLOR_OLD_BLUE = 0xFF0000C8;
    private static final int COLOR_EXTERNAL = 0xFFFF9000;
    private static final int COLOR_PENDING_CAPTURE_NEW = 0xFFFFD21F;
    private static final int COLOR_PENDING_CAPTURE_SAVED = 0xFFD8DC30;
    private static final int COLOR_PENDING_WRITE_NEW = 0xFF91E633;
    private static final int COLOR_PENDING_WRITE_SAVED = 0xFF20E060;
    private static final int COLOR_CONFLICT_BORDER = 0xFFFF3030;
    private static final int COLOR_GRID = 0x14FFFFFF;
    private static final int COLOR_BACKGROUND = 0xFF101010;
    private static final int COLOR_DIALOG_BACKGROUND = 0xFF202020;
    private static final int COLOR_DIALOG_BORDER = 0xFFAA4444;
    private static final int COLOR_PLAYER = 0xFFFFFFFF;
    private static final int TRANSPARENT_FILL_ALPHA = 0x7F;
    private static final int OVERLAY_ALPHA = 0x5F;
    private static final int OVERLAY_CONFLICT_COLOR = 0x66FF3030;
    private static final long AGE_MAX_MS = 30L * 24 * 3600 * 1000;
    private static final long AGE_MIN_MS = 10L * 60 * 1000;
    private static final int DIALOG_WIDTH = 220;
    private static final int DIALOG_HEIGHT = 90;

    private ChunkMapView() {}

    @FunctionalInterface
    public interface Canvas {
        void fill(int left, int top, int right, int bottom, int color);
    }

    @FunctionalInterface
    public interface CoordinateProjection {
        int project(double coordinate);
    }

    public record BuiltInViewport(int width, int height,
                                  double centerChunkX, double centerChunkZ,
                                  int cellSize, boolean transparentBackground) {
        public int halfWidth() {
            return width / 2;
        }

        public int halfHeight() {
            return height / 2;
        }

        public int screenX(double chunkX) {
            return halfWidth() + (int) Math.round((chunkX - centerChunkX) * cellSize);
        }

        public int screenZ(double chunkZ) {
            return halfHeight() + (int) Math.round((chunkZ - centerChunkZ) * cellSize);
        }
    }

    public record ChunkRange(int minX, int maxX, int minZ, int maxZ) {}

    public record ChunkCoordinate(int x, int z) {}

    public record DialogBounds(int left, int top, int right, int bottom) {
        public int centerX() {
            return (left + right) / 2;
        }

        public boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    public static ChunkRange renderBuiltIn(Canvas canvas,
                                           ChunkStatusSnapshot snapshot,
                                           BuiltInViewport viewport,
                                           long now,
                                           int sparseThreshold) {
        return renderBuiltIn(canvas, snapshot, LiveChunkStatusSnapshot.EMPTY,
                viewport, now, sparseThreshold);
    }

    public static ChunkRange renderBuiltIn(Canvas canvas,
                                           ChunkStatusSnapshot snapshot,
                                           LiveChunkStatusSnapshot live,
                                           BuiltInViewport viewport,
                                           long now,
                                           int sparseThreshold) {
        if (!viewport.transparentBackground()) {
            canvas.fill(0, 0, viewport.width(), viewport.height(), COLOR_BACKGROUND);
        }
        ChunkRange range = visibleRange(viewport);
        if (viewport.cellSize() <= clampSparseThreshold(sparseThreshold)) {
            renderSparse(canvas, snapshot, live, viewport, range, now);
        } else {
            renderDense(canvas, snapshot, live, viewport, range, now);
        }
        return range;
    }

    public static void renderOverlay(Canvas canvas,
                                     CoordinateProjection screenX,
                                     CoordinateProjection screenY,
                                     ChunkStatusSnapshot snapshot,
                                     int width,
                                     int height,
                                     double cameraX,
                                     double cameraZ,
                                     double pixelsPerBlock,
                                     long now,
                                     int maxCells) {
        renderOverlay(canvas, screenX, screenY, snapshot, LiveChunkStatusSnapshot.EMPTY,
                width, height, cameraX, cameraZ, pixelsPerBlock, now, maxCells);
    }

    public static ChunkRange overlayVisibleRange(int width, int height,
                                                 double cameraX, double cameraZ,
                                                 double pixelsPerBlock) {
        if (width <= 0 || height <= 0 || !Double.isFinite(cameraX)
                || !Double.isFinite(cameraZ) || !Double.isFinite(pixelsPerBlock)
                || pixelsPerBlock <= 0.0D) return null;
        double halfBlocksX = width / (2.0D * pixelsPerBlock);
        double halfBlocksZ = height / (2.0D * pixelsPerBlock);
        double minChunkX = Math.floor((cameraX - halfBlocksX) / CHUNK_BLOCK_SIZE) - 1;
        double maxChunkX = Math.ceil((cameraX + halfBlocksX) / CHUNK_BLOCK_SIZE) + 1;
        double minChunkZ = Math.floor((cameraZ - halfBlocksZ) / CHUNK_BLOCK_SIZE) - 1;
        double maxChunkZ = Math.ceil((cameraZ + halfBlocksZ) / CHUNK_BLOCK_SIZE) + 1;
        if (minChunkX > MAX_WORLD_CHUNK || maxChunkX < -MAX_WORLD_CHUNK
                || minChunkZ > MAX_WORLD_CHUNK || maxChunkZ < -MAX_WORLD_CHUNK) return null;
        int minX = (int) Math.max(-MAX_WORLD_CHUNK, minChunkX);
        int maxX = (int) Math.min(MAX_WORLD_CHUNK, maxChunkX);
        int minZ = (int) Math.max(-MAX_WORLD_CHUNK, minChunkZ);
        int maxZ = (int) Math.min(MAX_WORLD_CHUNK, maxChunkZ);
        return new ChunkRange(minX, maxX, minZ, maxZ);
    }

    public static void renderOverlay(Canvas canvas,
                                     CoordinateProjection screenX,
                                     CoordinateProjection screenY,
                                     ChunkStatusSnapshot snapshot,
                                     LiveChunkStatusSnapshot live,
                                     int width,
                                     int height,
                                     double cameraX,
                                     double cameraZ,
                                     double pixelsPerBlock,
                                     long now,
                                     int maxCells) {
        ChunkRange range = overlayVisibleRange(width, height, cameraX, cameraZ, pixelsPerBlock);
        if (range == null) return;
        ChunkMapAggregation.Result aggregate = ChunkMapAggregation.aggregate(
                snapshot, range.minX(), range.maxX(), range.minZ(), range.maxZ(),
                now, clampOverlayMaxCells(maxCells));
        int bucketSize = aggregate.bucketSize();
        double blockSize = (double) bucketSize * CHUNK_BLOCK_SIZE;

        for (ChunkMapAggregation.Run run : aggregate.runs()) {
            int x1 = screenX.project((double) run.startBucketX() * blockSize);
            int y1 = screenY.project((double) run.bucketZ() * blockSize);
            int x2 = screenX.project((double) (run.endBucketX() + 1) * blockSize);
            int y2 = screenY.project((double) (run.bucketZ() + 1) * blockSize);
            if (x2 <= x1) x2 = x1 + 1;
            if (y2 <= y1) y2 = y1 + 1;
            if (x2 <= 0 || y2 <= 0 || x1 >= width || y1 >= height) continue;

            int left = Math.max(0, x1);
            int top = Math.max(0, y1);
            int right = Math.min(width, x2);
            int bottom = Math.min(height, y2);
            canvas.fill(left, top, right, bottom, withAlpha(run.color(), OVERLAY_ALPHA));
        }
        renderProjectedLive(canvas, screenX, screenY, live, range,
                bucketSize, blockSize, width, height, now);
        for (ChunkMapAggregation.Run run : aggregate.runs()) {
            if (!run.conflict()) continue;
            int x1 = screenX.project((double) run.startBucketX() * blockSize);
            int y1 = screenY.project((double) run.bucketZ() * blockSize);
            int x2 = Math.max(x1 + 1, screenX.project((double) (run.endBucketX() + 1) * blockSize));
            int y2 = Math.max(y1 + 1, screenY.project((double) (run.bucketZ() + 1) * blockSize));
            if (x2 <= 0 || y2 <= 0 || x1 >= width || y1 >= height) continue;
            canvas.fill(Math.max(0, x1), Math.max(0, y1),
                    Math.min(width, x2), Math.min(height, y2), OVERLAY_CONFLICT_COLOR);
        }
        renderProjectedBoundaries(canvas, screenX, screenY, aggregate, blockSize,
                width, height);
    }

    private static void renderProjectedLive(Canvas canvas,
                                            CoordinateProjection screenX,
                                            CoordinateProjection screenY,
                                            LiveChunkStatusSnapshot live,
                                            ChunkRange range,
                                            int bucketSize,
                                            double blockSize,
                                            int width,
                                            int height,
                                            long now) {
        if (live.isEmpty()) return;
        int minBucketX = Math.floorDiv(range.minX(), bucketSize);
        int maxBucketX = Math.floorDiv(range.maxX(), bucketSize);
        int minBucketZ = Math.floorDiv(range.minZ(), bucketSize);
        int maxBucketZ = Math.floorDiv(range.maxZ(), bucketSize);
        for (Map.Entry<Long, LiveChunkStatusSnapshot.Record> entry : live.bucketRecords(bucketSize).entrySet()) {
            int bucketX = (int) (entry.getKey() >> 32);
            int bucketZ = (int) (long) entry.getKey();
            if (bucketX < minBucketX || bucketX > maxBucketX
                    || bucketZ < minBucketZ || bucketZ > maxBucketZ) continue;
            int x1 = screenX.project((double) bucketX * blockSize);
            int y1 = screenY.project((double) bucketZ * blockSize);
            int x2 = Math.max(x1 + 1, screenX.project((double) (bucketX + 1) * blockSize));
            int y2 = Math.max(y1 + 1, screenY.project((double) (bucketZ + 1) * blockSize));
            if (x2 <= 0 || y2 <= 0 || x1 >= width || y1 >= height) continue;
            canvas.fill(Math.max(0, x1), Math.max(0, y1),
                    Math.min(width, x2), Math.min(height, y2),
                    withAlpha(liveColor(entry.getValue(), now), OVERLAY_ALPHA));
        }
    }

    public static void drawPlayerMarker(Canvas canvas,
                                        BuiltInViewport viewport,
                                        double playerChunkX,
                                        double playerChunkZ) {
        int x = viewport.screenX(playerChunkX);
        int z = viewport.screenZ(playerChunkZ);
        canvas.fill(x - 1, z - 1, x + 2, z + 2, COLOR_PLAYER);
    }

    public static DialogBounds drawDialogFrame(Canvas canvas, int width, int height) {
        DialogBounds bounds = dialogBounds(width, height);
        canvas.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), COLOR_DIALOG_BACKGROUND);
        canvas.fill(bounds.left(), bounds.top(), bounds.right(), bounds.top() + 1, COLOR_DIALOG_BORDER);
        canvas.fill(bounds.left(), bounds.top(), bounds.left() + 1, bounds.bottom(), COLOR_DIALOG_BORDER);
        canvas.fill(bounds.right() - 1, bounds.top(), bounds.right(), bounds.bottom(), COLOR_DIALOG_BORDER);
        canvas.fill(bounds.left(), bounds.bottom() - 1, bounds.right(), bounds.bottom(), COLOR_DIALOG_BORDER);
        return bounds;
    }

    public static DialogBounds dialogBounds(int width, int height) {
        int left = (width - DIALOG_WIDTH) / 2;
        int top = (height - DIALOG_HEIGHT) / 2;
        return new DialogBounds(left, top, left + DIALOG_WIDTH, top + DIALOG_HEIGHT);
    }

    public static ChunkCoordinate screenToChunk(BuiltInViewport viewport, double screenX, double screenZ) {
        int chunkX = (int) Math.floor(viewport.centerChunkX()
                + (screenX - viewport.halfWidth()) / viewport.cellSize() + 0.5D);
        int chunkZ = (int) Math.floor(viewport.centerChunkZ()
                + (screenZ - viewport.halfHeight()) / viewport.cellSize() + 0.5D);
        return new ChunkCoordinate(chunkX, chunkZ);
    }

    public static int adjustCellSize(int current, double scrollDelta) {
        int delta = scrollDelta > 0 ? 1 : -1;
        return Math.max(CELL_SIZE_MIN, Math.min(CELL_SIZE_MAX, current + delta));
    }

    public static int computeColor(ChunkDatabase.ChunkRecord record, long now) {
        if (record == null) return 0;
        if (usesCaptureTimeGradient(record.updateSource())) {
            return interpolateGreenBlue(now - record.updateTime());
        }
        return COLOR_EXTERNAL;
    }

    public static boolean usesCaptureTimeGradient(String updateSource) {
        return "world_mirror".equals(updateSource) || "replay_import".equals(updateSource);
    }

    public static int interpolateGreenBlue(long ageMs) {
        if (ageMs <= AGE_MIN_MS) return COLOR_FRESH_GREEN;
        if (ageMs >= AGE_MAX_MS) return COLOR_OLD_BLUE;
        double progress = Math.log((double) ageMs / AGE_MIN_MS)
                / Math.log((double) AGE_MAX_MS / AGE_MIN_MS);
        progress = Math.max(0.0D, Math.min(1.0D, progress));
        int green = (int) (200 * (1.0D - progress));
        int blue = (int) (200 * progress);
        return 0xFF000000 | (green << 8) | blue;
    }

    public static ChunkRange visibleRange(BuiltInViewport viewport) {
        int visibleX = viewport.width() / viewport.cellSize() / 2 + 2;
        int visibleZ = viewport.height() / viewport.cellSize() / 2 + 2;
        return new ChunkRange(
                (int) Math.floor(viewport.centerChunkX()) - visibleX,
                (int) Math.ceil(viewport.centerChunkX()) + visibleX,
                (int) Math.floor(viewport.centerChunkZ()) - visibleZ,
                (int) Math.ceil(viewport.centerChunkZ()) + visibleZ);
    }

    private static void renderDense(Canvas canvas,
                                    ChunkStatusSnapshot snapshot,
                                    LiveChunkStatusSnapshot live,
                                    BuiltInViewport viewport,
                                    ChunkRange range,
                                    long now) {
        snapshot.forEachRecordInRange(range.minX(), range.maxX(), range.minZ(), range.maxZ(), record -> {
            int fill = computeColor(record, now);
            if (viewport.transparentBackground()) {
                fill = withAlpha(fill, TRANSPARENT_FILL_ALPHA);
            }
            if (fill != 0) {
                int x = viewport.screenX(record.x());
                int z = viewport.screenZ(record.z());
                canvas.fill(x, z, x + viewport.cellSize(), z + viewport.cellSize(), fill);
            }
        });
        renderLive(canvas, live, viewport, range, now, 1);
        renderGrid(canvas, viewport, range, gridIntervalForCellSize(viewport.cellSize()));
        snapshot.forEachBoundaryInRange(range.minX(), range.maxX(), range.minZ(), range.maxZ(),
                boundary -> renderBoundary(canvas, viewport, boundary));
        snapshot.forEachConflictCoordinatesInRange(range.minX(), range.maxX(), range.minZ(), range.maxZ(),
                (x, z) -> drawConflictBorder(
                        canvas, viewport.screenX(x), viewport.screenZ(z), viewport.cellSize()));
    }

    private static void renderSparse(Canvas canvas,
                                     ChunkStatusSnapshot snapshot,
                                     LiveChunkStatusSnapshot live,
                                     BuiltInViewport viewport,
                                     ChunkRange range,
                                     long now) {
        ChunkMapAggregation.Result aggregate = ChunkMapAggregation.aggregate(
                snapshot, range.minX(), range.maxX(), range.minZ(), range.maxZ(), now);
        int bucketSize = aggregate.bucketSize();
        for (ChunkMapAggregation.Run run : aggregate.runs()) {
            int x1 = viewport.screenX((double) run.startBucketX() * bucketSize);
            int z1 = viewport.screenZ((double) run.bucketZ() * bucketSize);
            int x2 = viewport.screenX((double) (run.endBucketX() + 1) * bucketSize);
            int z2 = viewport.screenZ((double) (run.bucketZ() + 1) * bucketSize);
            int fill = viewport.transparentBackground()
                    ? withAlpha(run.color(), TRANSPARENT_FILL_ALPHA)
                    : run.color();
            canvas.fill(x1, z1, Math.max(x1 + 1, x2), Math.max(z1 + 1, z2), fill);
        }
        renderLive(canvas, live, viewport, range, now, bucketSize);
        for (ChunkMapAggregation.Run run : aggregate.runs()) {
            if (!run.conflict()) continue;
            int x1 = viewport.screenX((double) run.startBucketX() * bucketSize);
            int z1 = viewport.screenZ((double) run.bucketZ() * bucketSize);
            int x2 = viewport.screenX((double) (run.endBucketX() + 1) * bucketSize);
            int z2 = viewport.screenZ((double) (run.bucketZ() + 1) * bucketSize);
            canvas.fill(x1, z1, Math.max(x1 + 1, x2), Math.max(z1 + 1, z2),
                    COLOR_CONFLICT_BORDER);
        }
        for (ChunkMapAggregation.Boundary boundary : aggregate.boundaries()) {
            renderAggregatedBoundary(canvas, viewport, boundary, bucketSize);
        }
        renderGrid(canvas, viewport, range, sparseGridIntervalForCellSize(viewport.cellSize()));
    }

    private static void renderLive(Canvas canvas, LiveChunkStatusSnapshot live,
                                   BuiltInViewport viewport, ChunkRange range,
                                   long now, int bucketSize) {
        if (live.isEmpty()) return;
        int minBucketX = Math.floorDiv(range.minX(), bucketSize);
        int maxBucketX = Math.floorDiv(range.maxX(), bucketSize);
        int minBucketZ = Math.floorDiv(range.minZ(), bucketSize);
        int maxBucketZ = Math.floorDiv(range.maxZ(), bucketSize);
        for (Map.Entry<Long, LiveChunkStatusSnapshot.Record> entry
                : live.bucketRecords(bucketSize).entrySet()) {
            int bucketX = (int) (entry.getKey() >> 32);
            int bucketZ = (int) (long) entry.getKey();
            if (bucketX < minBucketX || bucketX > maxBucketX
                    || bucketZ < minBucketZ || bucketZ > maxBucketZ) continue;
            int x1 = viewport.screenX((double) bucketX * bucketSize);
            int z1 = viewport.screenZ((double) bucketZ * bucketSize);
            int x2 = viewport.screenX((double) (bucketX + 1) * bucketSize);
            int z2 = viewport.screenZ((double) (bucketZ + 1) * bucketSize);
            LiveChunkStatusSnapshot.Record record = entry.getValue();
            int color = liveColor(record, now);
            if (viewport.transparentBackground()) color = withAlpha(color, TRANSPARENT_FILL_ALPHA);
            canvas.fill(x1, z1, Math.max(x1 + 1, x2), Math.max(z1 + 1, z2), color);
        }
    }

    private static int liveColor(LiveChunkStatusSnapshot.Record record, long now) {
        return switch (record.state()) {
            case PENDING_CAPTURE -> record.hasSavedRecord()
                    ? COLOR_PENDING_CAPTURE_SAVED : COLOR_PENDING_CAPTURE_NEW;
            case PENDING_WRITE -> record.hasSavedRecord()
                    ? COLOR_PENDING_WRITE_SAVED : COLOR_PENDING_WRITE_NEW;
            case CACHED -> interpolateGreenBlue(now - record.capturedAtMs());
        };
    }

    private static void renderBoundary(Canvas canvas,
                                       BuiltInViewport viewport,
                                       ChunkStatusSnapshot.BoundarySegment boundary) {
        int color = boundaryColor(boundary.color());
        if (boundary.vertical()) {
            int x = viewport.screenX(boundary.fixed());
            int z1 = viewport.screenZ(boundary.start());
            int z2 = viewport.screenZ(boundary.end());
            canvas.fill(x, z1, x + 1, z2, color);
        } else {
            int x1 = viewport.screenX(boundary.start());
            int x2 = viewport.screenX(boundary.end());
            int z = viewport.screenZ(boundary.fixed());
            canvas.fill(x1, z, x2, z + 1, color);
        }
    }

    private static void renderAggregatedBoundary(Canvas canvas,
                                                 BuiltInViewport viewport,
                                                 ChunkMapAggregation.Boundary boundary,
                                                 int bucketSize) {
        int color = boundaryColor(boundary.color());
        if (boundary.vertical()) {
            int x = viewport.screenX((double) boundary.fixed() * bucketSize);
            int z1 = viewport.screenZ((double) boundary.start() * bucketSize);
            int z2 = viewport.screenZ((double) boundary.end() * bucketSize);
            canvas.fill(x, z1, x + 1, Math.max(z1 + 1, z2), color);
        } else {
            int x1 = viewport.screenX((double) boundary.start() * bucketSize);
            int x2 = viewport.screenX((double) boundary.end() * bucketSize);
            int z = viewport.screenZ((double) boundary.fixed() * bucketSize);
            canvas.fill(x1, z, Math.max(x1 + 1, x2), z + 1, color);
        }
    }

    private static void renderProjectedBoundaries(Canvas canvas,
                                                  CoordinateProjection screenX,
                                                  CoordinateProjection screenY,
                                                  ChunkMapAggregation.Result aggregate,
                                                  double blockSize,
                                                  int width, int height) {
        for (ChunkMapAggregation.Boundary boundary : aggregate.boundaries()) {
            int color = boundaryColor(boundary.color());
            if (boundary.vertical()) {
                int x = screenX.project((double) boundary.fixed() * blockSize);
                int y1 = screenY.project((double) boundary.start() * blockSize);
                int y2 = screenY.project((double) boundary.end() * blockSize);
                y2 = Math.max(y1 + 1, y2);
                if (x < 0 || x >= width || y2 <= 0 || y1 >= height) continue;
                canvas.fill(x, Math.max(0, y1), x + 1, Math.min(height, y2), color);
            } else {
                int x1 = screenX.project((double) boundary.start() * blockSize);
                int x2 = screenX.project((double) boundary.end() * blockSize);
                int y = screenY.project((double) boundary.fixed() * blockSize);
                x2 = Math.max(x1 + 1, x2);
                if (y < 0 || y >= height || x2 <= 0 || x1 >= width) continue;
                canvas.fill(Math.max(0, x1), y, Math.min(width, x2), y + 1, color);
            }
        }
    }

    private static void renderGrid(Canvas canvas,
                                   BuiltInViewport viewport,
                                   ChunkRange range,
                                   int interval) {
        int top = viewport.screenZ(range.minZ());
        int bottom = viewport.screenZ(range.maxZ() + 1);
        int left = viewport.screenX(range.minX());
        int right = viewport.screenX(range.maxX() + 1);
        for (int x = firstMultipleAtOrAfter(range.minX(), interval);
             x <= range.maxX(); x += interval) {
            int screenX = viewport.screenX(x);
            canvas.fill(screenX, top, screenX + 1, bottom, COLOR_GRID);
        }
        for (int z = firstMultipleAtOrAfter(range.minZ(), interval);
             z <= range.maxZ(); z += interval) {
            int screenZ = viewport.screenZ(z);
            canvas.fill(left, screenZ, right, screenZ + 1, COLOR_GRID);
        }
    }

    private static void drawConflictBorder(Canvas canvas, int x, int z, int size) {
        if (size < 3) {
            canvas.fill(x, z, x + size, z + size, COLOR_CONFLICT_BORDER);
            return;
        }
        canvas.fill(x + 1, z + 1, x + size - 1, z + 2, COLOR_CONFLICT_BORDER);
        canvas.fill(x + 1, z + size - 2, x + size - 1, z + size - 1, COLOR_CONFLICT_BORDER);
        canvas.fill(x + 1, z + 1, x + 2, z + size - 1, COLOR_CONFLICT_BORDER);
        canvas.fill(x + size - 2, z + 1, x + size - 1, z + size - 1, COLOR_CONFLICT_BORDER);
    }

    private static int gridIntervalForCellSize(int size) {
        if (size >= 8) return 1;
        if (size >= 4) return 4;
        return 16;
    }

    private static int sparseGridIntervalForCellSize(int size) {
        return Math.max(4, gridIntervalForCellSize(size));
    }

    private static int firstMultipleAtOrAfter(int value, int interval) {
        int remainder = Math.floorMod(value, interval);
        return remainder == 0 ? value : value + interval - remainder;
    }

    private static int clampSparseThreshold(int threshold) {
        return Math.max(CELL_SIZE_MIN, Math.min(CELL_SIZE_MAX, threshold));
    }

    private static int clampOverlayMaxCells(int maxCells) {
        return Math.max(1_000, Math.min(50_000, maxCells));
    }

    private static int withAlpha(int argb, int alpha) {
        return argb == 0 ? 0 : (argb & 0x00FFFFFF) | (alpha << 24);
    }

    private static int boundaryColor(int argb) {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        int luminance = (red * 54 + green * 183 + blue * 19) >> 8;
        if (luminance < 128) {
            red = (red + 255) >> 1;
            green = (green + 255) >> 1;
            blue = (blue + 255) >> 1;
        } else {
            red >>= 1;
            green >>= 1;
            blue >>= 1;
        }
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
