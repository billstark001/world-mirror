package net.billstark001.worldmirror.xaero;

import net.billstark001.worldmirror.config.ModConfig;
import net.billstark001.worldmirror.ui.ChunkMapScreen;
import net.billstark001.worldmirror.ui.ChunkStatusCache;
import net.billstark001.worldmirror.ui.ChunkStatusSnapshot;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public final class XaeroWorldMapOverlayRenderer {
    private static final int OVERLAY_ALPHA = 0x5F;
    private static final int CONFLICT_ALPHA = 0x66;
    private static final int BOUNDARY_ALPHA = 0x70;
    private static final int FALLBACK_CONFLICT_COLOR = (CONFLICT_ALPHA << 24) | 0x00FF3030;
    private static final long REBUILD_INTERVAL_MS = 200L;

    private static Field cameraXField;
    private static Field cameraZField;
    private static Field scaleField;
    private static Field lastViewedDimensionField;
    private static Field lastNonNullViewedDimensionField;

    private static final int CHUNK_BLOCK_SIZE = 16;

    private static List<MapRun> cachedRuns = List.of();
    private static long cachedSnapshotVersion = -1L;
    private static ResourceKey<Level> cachedDimension;
    private static int cachedPixelWidth;
    private static int cachedPixelHeight;
    private static double cachedCameraX = Double.NaN;
    private static double cachedCameraZ = Double.NaN;
    private static double cachedScale = Double.NaN;
    private static int cachedBucketSize = 1;
    private static long lastRebuildMs;

    private XaeroWorldMapOverlayRenderer() {}

    public static void extractRenderState(Screen screen, GuiGraphicsExtractor ctx, int width, int height) {
        ModConfig.ChunkMapConfig config = ModConfig.get().chunkMap;
        Minecraft client = Minecraft.getInstance();
        if (!config.showXaeroWorldMapOverlay || client.gui.hud.isHidden()) return;

        double cameraX = readDouble(screen, "cameraX", Double.NaN);
        double cameraZ = readDouble(screen, "cameraZ", Double.NaN);
        double scale = readDouble(screen, "scale", Double.NaN);
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ) || !Double.isFinite(scale) || scale <= 0.0) {
            return;
        }

        ResourceKey<Level> dimension = readDimension(screen);
        if (dimension == null) {
            dimension = client.level != null ? client.level.dimension() : Level.OVERWORLD;
        }

        long refreshMs = Math.max(1, config.xaeroWorldMapOverlayRefreshSeconds) * 1000L;
        ChunkStatusSnapshot snapshot = ChunkStatusCache.getOrScheduleRefresh(client, dimension, refreshMs);

        Window window = client.getWindow();
        int pixelWidth = window.getWidth();
        int pixelHeight = window.getHeight();
        double guiScale = Math.max(1.0D, window.getGuiScale());
        int maxCells = Math.max(1000, Math.min(50000, config.xaeroWorldMapOverlayMaxCells));
        int bucketSize = bucketSizeForViewport(pixelWidth, pixelHeight, scale, maxCells);
        long now = System.currentTimeMillis();
        if (shouldRebuild(snapshot, dimension, pixelWidth, pixelHeight, cameraX, cameraZ, scale, bucketSize, now)) {
            cachedRuns = buildRuns(snapshot, pixelWidth, pixelHeight, cameraX, cameraZ, scale, bucketSize);
            cachedSnapshotVersion = snapshot.version();
            cachedDimension = dimension;
            cachedPixelWidth = pixelWidth;
            cachedPixelHeight = pixelHeight;
            cachedCameraX = cameraX;
            cachedCameraZ = cameraZ;
            cachedScale = scale;
            cachedBucketSize = bucketSize;
            lastRebuildMs = now;
        }

        int halfW = pixelWidth / 2;
        int halfH = pixelHeight / 2;
        double bucketBlockSize = (double) bucketSize * CHUNK_BLOCK_SIZE;
        for (MapRun run : cachedRuns) {
            double blockX = (double) run.startBucketX() * bucketBlockSize;
            double blockZ = (double) run.bucketZ() * bucketBlockSize;
            double pixelX = halfW + (blockX - cameraX) * scale;
            double pixelZ = halfH + (blockZ - cameraZ) * scale;
            double pixelWidthForRun = (run.endBucketX() - run.startBucketX() + 1) * bucketBlockSize * scale;
            double pixelHeightForRun = bucketBlockSize * scale;

            int x1 = toGuiPixelRounded(pixelX, guiScale);
            int y1 = toGuiPixelRounded(pixelZ, guiScale);
            int x2 = toGuiPixelRounded(pixelX + pixelWidthForRun, guiScale);
            int y2 = toGuiPixelRounded(pixelZ + pixelHeightForRun, guiScale);
            if (x2 <= x1) x2 = x1 + 1;
            if (y2 <= y1) y2 = y1 + 1;
            if (x2 <= 0 || y2 <= 0 || x1 >= width || y1 >= height) {
                continue;
            }
            x1 = Math.max(0, x1);
            y1 = Math.max(0, y1);
            x2 = Math.min(width, x2);
            y2 = Math.min(height, y2);

            ctx.fill(x1, y1, x2, y2, run.color());
            if (run.conflict()) {
                ctx.fill(x1, y1, x2, y2, FALLBACK_CONFLICT_COLOR);
            }
        }
        drawStatusBoundaries(ctx, snapshot, width, height, pixelWidth, pixelHeight, cameraX, cameraZ, scale, guiScale);
    }

    private static boolean shouldRebuild(ChunkStatusSnapshot snapshot,
                                         ResourceKey<Level> dimension,
                                         int pixelWidth,
                                         int pixelHeight,
                                         double cameraX,
                                         double cameraZ,
                                         double scale,
                                         int bucketSize,
                                         long now) {
        if (snapshot.version() != cachedSnapshotVersion) return true;
        if (!Objects.equals(dimension, cachedDimension)) return true;
        if (pixelWidth != cachedPixelWidth || pixelHeight != cachedPixelHeight) return true;
        if (bucketSize != cachedBucketSize) return true;
        if (Math.abs(scale - cachedScale) > Math.max(0.0001D, cachedScale * 0.01D)) return true;
        double threshold = Math.max(8.0D, bucketSize * CHUNK_BLOCK_SIZE * 0.5D);
        if (Math.abs(cameraX - cachedCameraX) >= threshold || Math.abs(cameraZ - cachedCameraZ) >= threshold) return true;
        return now - lastRebuildMs >= REBUILD_INTERVAL_MS;
    }

    private static List<MapRun> buildRuns(ChunkStatusSnapshot snapshot,
                                          int pixelWidth,
                                          int pixelHeight,
                                          double cameraX,
                                          double cameraZ,
                                          double scale,
                                          int bucketSize) {
        double halfVisibleBlocksX = pixelWidth / (2.0D * scale);
        double halfVisibleBlocksZ = pixelHeight / (2.0D * scale);
        int minChunkX = (int) Math.floor((cameraX - halfVisibleBlocksX) / CHUNK_BLOCK_SIZE) - bucketSize;
        int maxChunkX = (int) Math.ceil((cameraX + halfVisibleBlocksX) / CHUNK_BLOCK_SIZE) + bucketSize;
        int minChunkZ = (int) Math.floor((cameraZ - halfVisibleBlocksZ) / CHUNK_BLOCK_SIZE) - bucketSize;
        int maxChunkZ = (int) Math.ceil((cameraZ + halfVisibleBlocksZ) / CHUNK_BLOCK_SIZE) + bucketSize;

        long now = System.currentTimeMillis();
        HashMap<Long, CellAccumulator> buckets = new HashMap<>();
        snapshot.forEachRecordInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ, record -> {
            int bucketX = Math.floorDiv(record.x(), bucketSize);
            int bucketZ = Math.floorDiv(record.z(), bucketSize);
            long key = ChunkStatusSnapshot.chunkKey(bucketX, bucketZ);
            CellAccumulator cell = buckets.computeIfAbsent(key, ignored -> new CellAccumulator(bucketX, bucketZ));
            if (record.updateTime() >= cell.updateTime) {
                cell.updateTime = record.updateTime();
                cell.color = translucent(ChunkMapScreen.computeColor(record, now));
            }
        });
        snapshot.forEachConflictInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ, pos -> {
            int bucketX = Math.floorDiv(pos.x(), bucketSize);
            int bucketZ = Math.floorDiv(pos.z(), bucketSize);
            long key = ChunkStatusSnapshot.chunkKey(bucketX, bucketZ);
            buckets.computeIfAbsent(key, ignored -> new CellAccumulator(bucketX, bucketZ)).conflict = true;
        });

        ArrayList<CellAccumulator> sortedCells = new ArrayList<>(buckets.values());
        sortedCells.sort(Comparator
                .comparingInt((CellAccumulator bucket) -> bucket.bucketZ)
                .thenComparingInt(bucket -> bucket.bucketX));

        ArrayList<MapRun> runs = new ArrayList<>(sortedCells.size());
        MapRunBuilder run = null;
        for (CellAccumulator bucket : sortedCells) {
            int color = bucket.color != 0 ? bucket.color : FALLBACK_CONFLICT_COLOR;
            if (run != null && run.canAppend(bucket.bucketX, bucket.bucketZ, color, bucket.conflict)) {
                run.append(bucket.bucketX);
            } else {
                if (run != null) runs.add(run.build());
                run = new MapRunBuilder(bucket.bucketX, bucket.bucketZ, color, bucket.conflict);
            }
        }
        if (run != null) runs.add(run.build());
        return runs;
    }

    private static int bucketSizeForViewport(int pixelWidth, int pixelHeight, double scale, int maxCells) {
        double visibleChunks = (pixelWidth / (scale * CHUNK_BLOCK_SIZE))
                * (pixelHeight / (scale * CHUNK_BLOCK_SIZE));
        if (visibleChunks <= maxCells) return 1;
        return Math.max(1, (int) Math.ceil(Math.sqrt(visibleChunks / maxCells)));
    }

    private static int translucent(int argb) {
        if (argb == 0) return 0;
        return (argb & 0x00FFFFFF) | (OVERLAY_ALPHA << 24);
    }

    private static int toGuiPixelRounded(double screenPixel, double guiScale) {
        return (int) Math.round(screenPixel / guiScale);
    }

    private static void drawStatusBoundaries(GuiGraphicsExtractor ctx,
                                             ChunkStatusSnapshot snapshot,
                                             int guiWidth,
                                             int guiHeight,
                                             int pixelWidth,
                                             int pixelHeight,
                                             double cameraX,
                                             double cameraZ,
                                             double scale,
                                             double guiScale) {
        double chunkGuiSize = CHUNK_BLOCK_SIZE * scale / guiScale;
        if (chunkGuiSize < 3.0D) return;
        int halfW = pixelWidth / 2;
        int halfH = pixelHeight / 2;
        double halfVisibleBlocksX = pixelWidth / (2.0D * scale);
        double halfVisibleBlocksZ = pixelHeight / (2.0D * scale);
        int minChunkX = (int) Math.floor((cameraX - halfVisibleBlocksX) / CHUNK_BLOCK_SIZE) - 1;
        int maxChunkX = (int) Math.ceil((cameraX + halfVisibleBlocksX) / CHUNK_BLOCK_SIZE) + 1;
        int minChunkZ = (int) Math.floor((cameraZ - halfVisibleBlocksZ) / CHUNK_BLOCK_SIZE) - 1;
        int maxChunkZ = (int) Math.ceil((cameraZ + halfVisibleBlocksZ) / CHUNK_BLOCK_SIZE) + 1;

        snapshot.forEachBoundaryInRange(minChunkX, maxChunkX, minChunkZ, maxChunkZ, segment -> {
            int color = translucentBoundary(segment.color());
            if (segment.vertical()) {
                int x = toGuiPixelRounded(halfW + ((double) segment.fixed() * CHUNK_BLOCK_SIZE - cameraX) * scale, guiScale);
                int y1 = toGuiPixelRounded(halfH + ((double) segment.start() * CHUNK_BLOCK_SIZE - cameraZ) * scale, guiScale);
                int y2 = toGuiPixelRounded(halfH + ((double) segment.end() * CHUNK_BLOCK_SIZE - cameraZ) * scale, guiScale);
                if (x >= 0 && x < guiWidth && y2 > 0 && y1 < guiHeight) {
                    ctx.fill(x, Math.max(0, y1), x + 1, Math.min(guiHeight, y2), color);
                }
            } else {
                int x1 = toGuiPixelRounded(halfW + ((double) segment.start() * CHUNK_BLOCK_SIZE - cameraX) * scale, guiScale);
                int x2 = toGuiPixelRounded(halfW + ((double) segment.end() * CHUNK_BLOCK_SIZE - cameraX) * scale, guiScale);
                int y = toGuiPixelRounded(halfH + ((double) segment.fixed() * CHUNK_BLOCK_SIZE - cameraZ) * scale, guiScale);
                if (y >= 0 && y < guiHeight && x2 > 0 && x1 < guiWidth) {
                    ctx.fill(Math.max(0, x1), y, Math.min(guiWidth, x2), y + 1, color);
                }
            }
        });
    }

    private static int translucentBoundary(int argb) {
        return (argb & 0x00FFFFFF) | (BOUNDARY_ALPHA << 24);
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Level> readDimension(Screen screen) {
        Object value = readField(screen, "lastViewedDimensionId");
        if (value == null) value = readField(screen, "lastNonNullViewedDimensionId");
        return value instanceof ResourceKey<?> ? (ResourceKey<Level>) value : null;
    }

    private static double readDouble(Screen screen, String name, double fallback) {
        Object value = readField(screen, name);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static Object readField(Screen screen, String name) {
        try {
            Field field = fieldFor(screen.getClass(), name);
            return field != null ? field.get(screen) : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field fieldFor(Class<?> type, String name) throws NoSuchFieldException {
        Field cached = switch (name) {
            case "cameraX" -> cameraXField;
            case "cameraZ" -> cameraZField;
            case "scale" -> scaleField;
            case "lastViewedDimensionId" -> lastViewedDimensionField;
            case "lastNonNullViewedDimensionId" -> lastNonNullViewedDimensionField;
            default -> null;
        };
        if (cached != null) return cached;

        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        switch (name) {
            case "cameraX" -> cameraXField = field;
            case "cameraZ" -> cameraZField = field;
            case "scale" -> scaleField = field;
            case "lastViewedDimensionId" -> lastViewedDimensionField = field;
            case "lastNonNullViewedDimensionId" -> lastNonNullViewedDimensionField = field;
            default -> {}
        }
        return field;
    }

    private static final class CellAccumulator {
        private final int bucketX;
        private final int bucketZ;
        private long updateTime = Long.MIN_VALUE;
        private int color;
        private boolean conflict;

        private CellAccumulator(int bucketX, int bucketZ) {
            this.bucketX = bucketX;
            this.bucketZ = bucketZ;
        }
    }

    private static final class MapRunBuilder {
        private final int startBucketX;
        private final int bucketZ;
        private final int color;
        private final boolean conflict;
        private int endBucketX;

        private MapRunBuilder(int bucketX, int bucketZ, int color, boolean conflict) {
            this.startBucketX = bucketX;
            this.endBucketX = bucketX;
            this.bucketZ = bucketZ;
            this.color = color;
            this.conflict = conflict;
        }

        private boolean canAppend(int bucketX, int bucketZ, int color, boolean conflict) {
            return bucketZ == this.bucketZ
                    && bucketX == this.endBucketX + 1
                    && color == this.color
                    && conflict == this.conflict;
        }

        private void append(int bucketX) {
            this.endBucketX = bucketX;
        }

        private MapRun build() {
            return new MapRun(startBucketX, endBucketX, bucketZ, color, conflict);
        }
    }

    private record MapRun(int startBucketX, int endBucketX, int bucketZ, int color, boolean conflict) {}
}
