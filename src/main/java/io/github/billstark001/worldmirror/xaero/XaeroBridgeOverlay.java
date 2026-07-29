package io.github.billstark001.worldmirror.xaero;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.ui.ChunkMapScreen;
import io.github.billstark001.worldmirror.ui.ChunkMapAggregation;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache;
import io.github.billstark001.worldmirror.ui.ChunkStatusSnapshot;
import io.github.billstark001.xaerobridge.api.MapOverlayContext;
import io.github.billstark001.xaerobridge.api.OverlayRegistration;
import io.github.billstark001.xaerobridge.api.XaeroWorldMapBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * World Mirror's optional integration with Xaero World Map Bridge.
 * The bridge, rather than this mod, owns every Xaero mixin and the exact/tail
 * injection policy.  Keeping this renderer on the public bridge API means World
 * Mirror can remain usable when neither Xaero nor the bridge is installed.
 */
public final class XaeroBridgeOverlay {
    private static final int CHUNK_BLOCK_SIZE = 16;
    private static final int OVERLAY_ALPHA = 0x5F;
    private static final int CONFLICT_COLOR = 0x66FF3030;
    private static OverlayRegistration registration;

    private XaeroBridgeOverlay() {}

    public static synchronized void install() {
        if (registration == null) {
            registration = XaeroWorldMapBridge.registerMapOverlay(
                    "worldmirror:chunk-status", 0, XaeroBridgeOverlay::render);
        }
    }

    private static void render(MapOverlayContext context) {
        ModConfig.ChunkMapConfig config = ModConfig.get().chunkMap;
        Minecraft client = Minecraft.getInstance();
        if (!config.showXaeroWorldMapOverlay || client.level == null) return;

        ResourceKey<Level> dimension = client.level.dimension();
        long refreshMs = Math.max(1, config.xaeroWorldMapOverlayRefreshSeconds) * 1_000L;
        ChunkStatusSnapshot snapshot = ChunkStatusCache.getOrScheduleRefresh(client, dimension, refreshMs);
        double scale = context.pixelsPerBlock();
        if (!Double.isFinite(scale) || scale <= 0.0D) return;

        double halfBlocksX = context.width() / (2.0D * scale);
        double halfBlocksZ = context.height() / (2.0D * scale);
        int minX = (int) Math.floor((context.cameraX() - halfBlocksX) / CHUNK_BLOCK_SIZE) - 1;
        int maxX = (int) Math.ceil((context.cameraX() + halfBlocksX) / CHUNK_BLOCK_SIZE) + 1;
        int minZ = (int) Math.floor((context.cameraZ() - halfBlocksZ) / CHUNK_BLOCK_SIZE) - 1;
        int maxZ = (int) Math.ceil((context.cameraZ() + halfBlocksZ) / CHUNK_BLOCK_SIZE) + 1;
        ChunkMapAggregation.Result aggregate = ChunkMapAggregation.aggregate(snapshot, minX, maxX, minZ, maxZ,
                System.currentTimeMillis(), Math.max(1_000, Math.min(50_000, config.xaeroWorldMapOverlayMaxCells)));
        int bucketSize = aggregate.bucketSize();
        for (ChunkMapAggregation.Run run : aggregate.runs()) {
            double blockSize = (double) bucketSize * CHUNK_BLOCK_SIZE;
            int x1 = context.worldToScreenX((double) run.startBucketX() * blockSize);
            int y1 = context.worldToScreenY((double) run.bucketZ() * blockSize);
            int x2 = context.worldToScreenX((double) (run.endBucketX() + 1) * blockSize);
            int y2 = context.worldToScreenY((double) (run.bucketZ() + 1) * blockSize);
            if (x2 <= x1) x2 = x1 + 1;
            if (y2 <= y1) y2 = y1 + 1;
            if (x2 <= 0 || y2 <= 0 || x1 >= context.width() || y1 >= context.height()) continue;
            context.canvas().fill(Math.max(0, x1), Math.max(0, y1),
                    Math.min(context.width(), x2), Math.min(context.height(), y2), translucent(run.color()));
            if (run.conflict()) {
                context.canvas().fill(Math.max(0, x1), Math.max(0, y1),
                        Math.min(context.width(), x2), Math.min(context.height(), y2), CONFLICT_COLOR);
            }
        }
        for (ChunkMapAggregation.Boundary boundary : aggregate.boundaries()) {
            double blockSize = (double) bucketSize * CHUNK_BLOCK_SIZE;
            int color = boundaryColor(boundary.color());
            if (boundary.vertical()) {
                int x = context.worldToScreenX((double) boundary.fixed() * blockSize);
                int y1 = context.worldToScreenY((double) boundary.start() * blockSize);
                int y2 = context.worldToScreenY((double) boundary.end() * blockSize);
                context.canvas().fill(x, y1, x + 1, Math.max(y1 + 1, y2), color);
            } else {
                int x1 = context.worldToScreenX((double) boundary.start() * blockSize);
                int x2 = context.worldToScreenX((double) boundary.end() * blockSize);
                int y = context.worldToScreenY((double) boundary.fixed() * blockSize);
                context.canvas().fill(x1, y, Math.max(x1 + 1, x2), y + 1, color);
            }
        }
    }

    private static List<MapRun> buildRuns(ChunkStatusSnapshot snapshot, MapOverlayContext context, int bucketSize) {
        double halfBlocksX = context.width() / (2.0D * context.pixelsPerBlock());
        double halfBlocksZ = context.height() / (2.0D * context.pixelsPerBlock());
        int minX = (int) Math.floor((context.cameraX() - halfBlocksX) / CHUNK_BLOCK_SIZE) - bucketSize;
        int maxX = (int) Math.ceil((context.cameraX() + halfBlocksX) / CHUNK_BLOCK_SIZE) + bucketSize;
        int minZ = (int) Math.floor((context.cameraZ() - halfBlocksZ) / CHUNK_BLOCK_SIZE) - bucketSize;
        int maxZ = (int) Math.ceil((context.cameraZ() + halfBlocksZ) / CHUNK_BLOCK_SIZE) + bucketSize;
        long now = System.currentTimeMillis();
        HashMap<Long, Cell> cells = new HashMap<>();
        snapshot.forEachRecordInRange(minX, maxX, minZ, maxZ, record -> {
            int bucketX = Math.floorDiv(record.x(), bucketSize);
            int bucketZ = Math.floorDiv(record.z(), bucketSize);
            Cell cell = cells.computeIfAbsent(ChunkStatusSnapshot.chunkKey(bucketX, bucketZ), ignored -> new Cell(bucketX, bucketZ));
            if (record.updateTime() >= cell.updateTime) {
                cell.updateTime = record.updateTime();
                cell.color = translucent(ChunkMapScreen.computeColor(record, now));
            }
        });
        snapshot.forEachConflictCoordinatesInRange(minX, maxX, minZ, maxZ, (x, z) -> {
            int bucketX = Math.floorDiv(x, bucketSize);
            int bucketZ = Math.floorDiv(z, bucketSize);
            cells.computeIfAbsent(ChunkStatusSnapshot.chunkKey(bucketX, bucketZ), ignored -> new Cell(bucketX, bucketZ)).conflict = true;
        });

        ArrayList<Cell> sorted = new ArrayList<>(cells.values());
        sorted.sort(Comparator.comparingInt((Cell cell) -> cell.z).thenComparingInt(cell -> cell.x));
        ArrayList<MapRun> runs = new ArrayList<>(sorted.size());
        for (Cell cell : sorted) {
            int color = cell.color != 0 ? cell.color : CONFLICT_COLOR;
            if (!runs.isEmpty() && runs.getLast().canAppend(cell, color)) {
                runs.getLast().endBucketX = cell.x;
            } else {
                runs.add(new MapRun(cell.x, cell.x, cell.z, color, cell.conflict));
            }
        }
        return runs;
    }

    private static int bucketSizeForViewport(int width, int height, double scale, int maxCells) {
        double visibleChunks = (width / (scale * CHUNK_BLOCK_SIZE)) * (height / (scale * CHUNK_BLOCK_SIZE));
        return visibleChunks <= maxCells ? 1 : Math.max(1, (int) Math.ceil(Math.sqrt(visibleChunks / maxCells)));
    }

    private static int translucent(int argb) {
        return argb == 0 ? 0 : (argb & 0x00FFFFFF) | (OVERLAY_ALPHA << 24);
    }

    private static int boundaryColor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int luminance = (r * 54 + g * 183 + b * 19) >> 8;
        if (luminance < 128) { r = (r + 255) >> 1; g = (g + 255) >> 1; b = (b + 255) >> 1; }
        else { r >>= 1; g >>= 1; b >>= 1; }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static final class Cell {
        private final int x;
        private final int z;
        private long updateTime = Long.MIN_VALUE;
        private int color;
        private boolean conflict;

        private Cell(int x, int z) { this.x = x; this.z = z; }
    }

    private static final class MapRun {
        private final int startBucketX;
        private int endBucketX;
        private final int bucketZ;
        private final int color;
        private final boolean conflict;

        private MapRun(int startBucketX, int endBucketX, int bucketZ, int color, boolean conflict) {
            this.startBucketX = startBucketX;
            this.endBucketX = endBucketX;
            this.bucketZ = bucketZ;
            this.color = color;
            this.conflict = conflict;
        }

        private boolean canAppend(Cell cell, int color) {
            return cell.z == bucketZ && cell.x == endBucketX + 1 && color == this.color && cell.conflict == conflict;
        }
    }
}
