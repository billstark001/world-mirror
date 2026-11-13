package io.github.billstark001.worldmirror.ui.map;

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
    private static final int VIEW_MARGIN = 1;
    private static final ChunkBounds EMPTY_RANGE = new ChunkBounds(0, -1, 0, -1);
    private static final int COLOR_GRID = 0x14FFFFFF;
    private static final int COLOR_BACKGROUND = 0xFF101010;
    private static final int COLOR_DIALOG_BACKGROUND = 0xFF202020;
    private static final int COLOR_DIALOG_BORDER = 0xFFAA4444;
    private static final int COLOR_PLAYER = 0xFFFFFFFF;
    private static final int TRANSPARENT_FILL_ALPHA = 0x7F;
    private static final int OVERLAY_ALPHA = 0x5F;
    private static final int MIN_MARKER_GLYPH_SIZE = 8;
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

    public record ChunkCoordinate(int x, int z) {}

    public record DialogBounds(int left, int top, int right, int bottom) {
        public int centerX() {
            return (left + right) / 2;
        }

        public boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    public static ChunkBounds renderBuiltIn(Canvas canvas, ChunkMapProjection projection,
                                           ChunkStatusSnapshot snapshot,
                                           LiveChunkStatusSnapshot live,
                                           BuiltInViewport viewport,
                                           long now,
                                           int sparseThreshold) {
        if (!viewport.transparentBackground()) {
            canvas.fill(0, 0, viewport.width(), viewport.height(), COLOR_BACKGROUND);
        }
        ChunkBounds range = visibleRange(viewport);
        var frame = mapFrame(projection, snapshot, live, range, viewport.width(), viewport.height(), viewport.cellSize(),
                now, ChunkBucketLayout.DEFAULT_MAP_BUDGET, viewport.cellSize() > clampSparseThreshold(sparseThreshold));
        int alpha = viewport.transparentBackground() ? TRANSPARENT_FILL_ALPHA : 0xFF;
        var target = new MapCanvas(canvas, viewport.width(), viewport.height(), viewport::screenX, viewport::screenZ, 1, alpha);
        renderCells(target, frame, range, now);
        int grid = gridIntervalForCellSize(viewport.cellSize());
        if (frame.dense()) renderGrid(canvas, viewport, range, grid);
        renderBoundaries(target, frame);
        if (!frame.dense()) renderGrid(canvas, viewport, range, Math.max(4, grid));
        renderBuiltInMarks(canvas, frame, viewport);
        return range;
    }

    public static ChunkBounds overlayVisibleRange(int width, int height,
                                                 double cameraX, double cameraZ,
                                                 double pixelsPerBlock) {
        if (width <= 0 || height <= 0 || !Double.isFinite(cameraX)
                || !Double.isFinite(cameraZ) || !Double.isFinite(pixelsPerBlock)
                || pixelsPerBlock <= 0.0D) return null;
        ChunkBounds range = visibleRange(width, height, cameraX / CHUNK_BLOCK_SIZE, cameraZ / CHUNK_BLOCK_SIZE,
                pixelsPerBlock * CHUNK_BLOCK_SIZE);
        return range.isEmpty() ? null : range;
    }

    private static ChunkBounds visibleRange(int width, int height, double centerX, double centerZ, double pixelsPerChunk) {
        double halfX = width / (2.0D * pixelsPerChunk);
        double halfZ = height / (2.0D * pixelsPerChunk);
        int minX = Math.max(-MAX_WORLD_CHUNK, (int) (Math.floor(centerX - halfX) - VIEW_MARGIN));
        int maxX = Math.min(MAX_WORLD_CHUNK, (int) (Math.ceil(centerX + halfX) + VIEW_MARGIN));
        int minZ = Math.max(-MAX_WORLD_CHUNK, (int) (Math.floor(centerZ - halfZ) - VIEW_MARGIN));
        int maxZ = Math.min(MAX_WORLD_CHUNK, (int) (Math.ceil(centerZ + halfZ) + VIEW_MARGIN));
        return minX > maxX || minZ > maxZ ? EMPTY_RANGE : new ChunkBounds(minX, maxX, minZ, maxZ);
    }

    private static long viewportSpan(int pixels, double pixelsPerChunk) {
        // Rounding the two endpoints can add one chunk beyond the ceil of the span.
        return (long) Math.min(2L * MAX_WORLD_CHUNK + 1, Math.ceil(pixels / pixelsPerChunk) + 2 * VIEW_MARGIN + 2);
    }

    private static ChunkMapProjection.Frame mapFrame(ChunkMapProjection projection, ChunkStatusSnapshot saved,
                                                      LiveChunkStatusSnapshot live, ChunkBounds range,
                                                      int width, int height, double pixelsPerChunk, long now,
                                                      int budget, boolean detailed) {
        return projection.get(saved, live, range, now, viewportSpan(width, pixelsPerChunk),
                viewportSpan(height, pixelsPerChunk), budget, detailed);
    }

    public static void renderOverlay(Canvas canvas, ChunkMapProjection projection,
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
        ChunkBounds range = overlayVisibleRange(width, height, cameraX, cameraZ, pixelsPerBlock);
        if (range == null) return;
        ChunkMapProjection.Frame aggregate = mapFrame(projection, snapshot, live, range, width, height,
                pixelsPerBlock * CHUNK_BLOCK_SIZE, now, clampOverlayMaxCells(maxCells), false);
        var target = new MapCanvas(canvas, width, height, screenX, screenY, CHUNK_BLOCK_SIZE, OVERLAY_ALPHA);
        renderCells(target, aggregate, range, now);
        renderBoundaries(target, aggregate);
    }

    private static void renderBuiltInMarks(Canvas canvas, ChunkMapProjection.Frame frame,
                                          BuiltInViewport viewport) {
        double size = frame.bucketSize();
        frame.marks().forEach((key, summary) -> {
            double x = ChunkBounds.x(key) * size, z = ChunkBounds.z(key) * size;
            int pixelLeft = viewport.screenX(x), pixelTop = viewport.screenZ(z);
            int pixelRight = Math.max(pixelLeft + 1, viewport.screenX(x + size));
            int pixelBottom = Math.max(pixelTop + 1, viewport.screenZ(z + size));
            if (pixelRight <= 0 || pixelBottom <= 0 || pixelLeft >= viewport.width() || pixelTop >= viewport.height()) return;
            if (pixelRight - pixelLeft < MIN_MARKER_GLYPH_SIZE || pixelBottom - pixelTop < MIN_MARKER_GLYPH_SIZE) {
                if (summary.conflict()) {
                    fillVisible(canvas, viewport, pixelLeft, pixelTop, pixelLeft + 1, pixelTop + 1,
                            ExportStatusMarkers.CONFLICT);
                }
                int warning = ExportStatusMarkers.compact(summary.exports());
                if (warning != 0) {
                    fillVisible(canvas, viewport, pixelRight - 1, pixelBottom - 1, pixelRight, pixelBottom, warning);
                }
                return;
            }
            ExportStatusMarkers.draw(summary.exports(), summary.conflict(), (left, top, right, bottom, color) -> {
                int x1 = viewport.screenX(x + left * size), z1 = viewport.screenZ(z + top * size);
                int x2 = Math.max(x1 + 1, viewport.screenX(x + right * size));
                int z2 = Math.max(z1 + 1, viewport.screenZ(z + bottom * size));
                fillVisible(canvas, viewport, x1, z1, x2, z2, color);
            });
        });
    }

    public static void drawPlayerMarker(Canvas canvas,
                                        BuiltInViewport viewport,
                                        double playerChunkX,
                                        double playerChunkZ) {
        int x = viewport.screenX(playerChunkX);
        int z = viewport.screenZ(playerChunkZ);
        fillVisible(canvas, viewport, x - 1, z - 1, x + 2, z + 2, COLOR_PLAYER);
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

    public static ChunkBounds visibleRange(BuiltInViewport viewport) {
        return visibleRange(viewport.width(), viewport.height(), viewport.centerChunkX(), viewport.centerChunkZ(),
                viewport.cellSize());
    }

    private record MapCanvas(Canvas canvas, int width, int height, CoordinateProjection screenX,
                             CoordinateProjection screenZ, int unitsPerChunk, int alpha) {
        int x(double chunk) { return screenX.project(chunk * unitsPerChunk); }
        int z(double chunk) { return screenZ.project(chunk * unitsPerChunk); }

        void fill(int left, int top, int right, int bottom, int color) {
            fillVisible(canvas, width, height, left, top, right, bottom, color);
        }
    }

    private static void renderCells(MapCanvas target, ChunkMapProjection.Frame frame, ChunkBounds range, long now) {
        int size = frame.bucketSize();
        for (ChunkTileGeometry.Run run : frame.runs()) {
            if (run.color() != 0) {
                fillBuckets(target, size, run.startBucketX(), run.bucketZ(), run.endBucketX(),
                        withAlpha(run.color(), target.alpha()));
            }
        }

        if (frame.live().isEmpty()) return;
        int minX = Math.floorDiv(range.minX(), size);
        int maxX = Math.floorDiv(range.maxX(), size);
        int minZ = Math.floorDiv(range.minZ(), size);
        int maxZ = Math.floorDiv(range.maxZ(), size);
        for (var entry : frame.live().entrySet()) {
            int x = ChunkBounds.x(entry.getKey());
            int z = ChunkBounds.z(entry.getKey());
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                fillBuckets(target, size, x, z, x, withAlpha(ChunkMapStyle.live(entry.getValue(), now), target.alpha()));
            }
        }
    }

    private static void fillBuckets(MapCanvas target, int size, int startX, int z, int endX, int color) {
        int left = target.x((double) startX * size);
        int top = target.z((double) z * size);
        int right = target.x((endX + 1.0D) * size);
        int bottom = target.z((z + 1.0D) * size);
        target.fill(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom), color);
    }

    private static void renderBoundaries(MapCanvas target, ChunkMapProjection.Frame frame) {
        int size = frame.bucketSize();
        for (var boundary : frame.boundaries()) {
            int color = boundaryColor(boundary.color());
            if (boundary.vertical()) {
                int x = target.x((double) boundary.fixed() * size);
                int z1 = target.z((double) boundary.start() * size);
                int z2 = target.z((double) boundary.end() * size);
                target.fill(x, z1, x + 1, Math.max(z1 + 1, z2), color);
            } else {
                int x1 = target.x((double) boundary.start() * size);
                int x2 = target.x((double) boundary.end() * size);
                int z = target.z((double) boundary.fixed() * size);
                target.fill(x1, z, Math.max(x1 + 1, x2), z + 1, color);
            }
        }
    }

    private static void fillVisible(Canvas canvas, int width, int height, int left, int top,
                                    int right, int bottom, int color) {
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(width, right);
        bottom = Math.min(height, bottom);
        if (left < right && top < bottom) canvas.fill(left, top, right, bottom, color);
    }

    private static void fillVisible(Canvas canvas, BuiltInViewport viewport, int left, int top,
                                    int right, int bottom, int color) {
        fillVisible(canvas, viewport.width(), viewport.height(), left, top, right, bottom, color);
    }

    private static void renderGrid(Canvas canvas,
                                   BuiltInViewport viewport,
                                   ChunkBounds range,
                                   int interval) {
        if (range.isEmpty()) return;
        int top = viewport.screenZ(range.minZ());
        int bottom = viewport.screenZ(range.maxZ() + 1);
        int left = viewport.screenX(range.minX());
        int right = viewport.screenX(range.maxX() + 1);
        for (int x = firstMultipleAtOrAfter(range.minX(), interval);
             x <= range.maxX(); x += interval) {
            int screenX = viewport.screenX(x);
            fillVisible(canvas, viewport, screenX, top, screenX + 1, bottom, COLOR_GRID);
        }
        for (int z = firstMultipleAtOrAfter(range.minZ(), interval);
             z <= range.maxZ(); z += interval) {
            int screenZ = viewport.screenZ(z);
            fillVisible(canvas, viewport, left, screenZ, right, screenZ + 1, COLOR_GRID);
        }
    }

    private static int gridIntervalForCellSize(int size) {
        if (size >= 8) return 1;
        if (size >= 4) return 4;
        return 16;
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
