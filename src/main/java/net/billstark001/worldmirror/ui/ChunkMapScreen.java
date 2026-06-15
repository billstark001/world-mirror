package net.billstark001.worldmirror.ui;

import net.billstark001.worldmirror.conflict.ConflictManager;
import net.billstark001.worldmirror.config.ModConfig;
import net.billstark001.worldmirror.download.ChunkDatabase;
import net.billstark001.worldmirror.download.DownloadManager;
import net.billstark001.worldmirror.download.WorldMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.*;

/**
 * Window 1 — full-screen chunk map.
 *
 * <p>Displays a draggable grid where each cell represents one chunk.  Cell colours
 * indicate download status:
 * <ul>
 *   <li><b>Transparent</b> — never downloaded.</li>
 *   <li><b>Green→Blue</b> — downloaded via {@code world_mirror}; colour shifts from
 *       green (≤10 min ago) to blue (≥1 month ago) on a logarithmic scale.</li>
 *   <li><b>Orange</b> — written by a non-{@code world_mirror} source.</li>
 * </ul>
 *
 * <p>If a chunk has an unresolved conflict entry a <b>red border</b> is drawn
 * inside its cell.  Clicking such a cell opens a small dialog offering three
 * choices: <i>Cancel</i>, <i>Overwrite</i> (apply the stored server chunk), or
 * <i>Discard</i> (keep the local chunk and delete the conflict entry).
 */
@Environment(EnvType.CLIENT)
public class ChunkMapScreen extends Screen {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int CELL_SIZE_MIN     = 2;
    private static final int CELL_SIZE_MAX     = 16;
    private static final int CELL_SIZE_DEFAULT = 6;
    private static final int COLOR_FRESH_GREEN     = 0xFF00C800;
    private static final int COLOR_OLD_BLUE        = 0xFF0000C8;
    private static final int COLOR_EXTERNAL        = 0xFFFF9000;
    private static final int COLOR_CONFLICT_BORDER = 0xFFFF3030;
    private static final int COLOR_GRID            = 0x22FFFFFF;

    private static final long AGE_MAX_MS = 30L * 24 * 3600 * 1000; // 1 month
    private static final long AGE_MIN_MS = 10L * 60 * 1000;        // 10 min

    // ── View state ────────────────────────────────────────────────────────────

    private double viewCX = 0, viewCZ = 0;
    private int cellSize = CELL_SIZE_DEFAULT;

    private boolean isDragging;
    private double dragStartX, dragStartY, viewCXOnDrag, viewCZOnDrag;

    private ChunkPos hoveredChunk;
    private int mouseX, mouseY;

    // ── Data ─────────────────────────────────────────────────────────────────

    private final Map<Long, ChunkDatabase.ChunkRecord> recordMap = new HashMap<>();
    private final Set<ChunkPos> conflictChunks = new HashSet<>();
    private final Set<Long> conflictKeys = new HashSet<>();
    private Path worldFolder;
    private ResourceKey<Level> currentDimension;
    private String sourceId;

    // ── Dialog ────────────────────────────────────────────────────────────────

    private ChunkPos dialogChunk;

    // Permanent dialog buttons (toggled visible/invisible)
    private Button dialogCancelBtn;
    private Button dialogOverwriteBtn;
    private Button dialogDiscardBtn;

    // ── Construction ─────────────────────────────────────────────────────────

    public ChunkMapScreen() {
        super(Component.translatable("screen.worldmirror.chunkmap.title"));
    }

    @Override
    protected void init() {
        Minecraft client = Minecraft.getInstance();

        // Centre on player
        if (client.player != null) {
            viewCX = client.player.chunkPosition().x();
            viewCZ = client.player.chunkPosition().z();
        }
        if (client.level != null) {
            currentDimension = client.level.dimension();
        }
        if (currentDimension == null) currentDimension = Level.OVERWORLD;

        loadData(client);

        // Permanent bottom buttons
        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"), btn -> onClose()
        ).bounds(this.width - 60, this.height - 24, 54, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("screen.worldmirror.chunkmap.refresh"),
                btn -> loadData(Minecraft.getInstance())
        ).bounds(this.width - 120, this.height - 24, 54, 20).build());

        // Dialog buttons (hidden initially)
        int dw = 220, dh = 90;
        int dx = (this.width - dw) / 2;
        int dy = (this.height - dh) / 2;
        int btnY = dy + dh - 28;
        int btnW = 64, gap = 8;
        int totalW = 3 * btnW + 2 * gap;
        int bx = (this.width - totalW) / 2;

        dialogCancelBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"), btn -> closeDialog()
        ).bounds(bx, btnY, btnW, 20).build());

        dialogOverwriteBtn = addRenderableWidget(Button.builder(
                Component.translatable("screen.worldmirror.chunkmap.dialog.overwrite"),
                btn -> {
                    if (dialogChunk != null && worldFolder != null && currentDimension != null) {
                        ConflictManager.resolveConflict(worldFolder, dialogChunk, currentDimension, true);
                        conflictChunks.remove(dialogChunk);
                        conflictKeys.remove(chunkKey(dialogChunk.x(), dialogChunk.z()));
                    }
                    closeDialog();
                }
        ).bounds(bx + btnW + gap, btnY, btnW, 20).build());

        dialogDiscardBtn = addRenderableWidget(Button.builder(
                Component.translatable("screen.worldmirror.chunkmap.dialog.discard"),
                btn -> {
                    if (dialogChunk != null && worldFolder != null && currentDimension != null) {
                        ConflictManager.resolveConflict(worldFolder, dialogChunk, currentDimension, false);
                        conflictChunks.remove(dialogChunk);
                        conflictKeys.remove(chunkKey(dialogChunk.x(), dialogChunk.z()));
                    }
                    closeDialog();
                }
        ).bounds(bx + 2 * (btnW + gap), btnY, btnW, 20).build());

        setDialogButtonsVisible(false);
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadData(Minecraft client) {
        sourceId = WorldMetadata.detectSourceId(client);
        worldFolder = DownloadManager.getOutputPath(client);
        if (currentDimension == null) currentDimension = Level.OVERWORLD;

        String dimStr = currentDimension.identifier().toString();

        recordMap.clear();
        for (ChunkDatabase.ChunkRecord r :
                ChunkDatabase.queryAllReadOnly(worldFolder, sourceId, dimStr)) {
            recordMap.put(chunkKey(r.x(), r.z()), r);
        }

        conflictChunks.clear();
        conflictChunks.addAll(ConflictManager.listConflicts(worldFolder, currentDimension));
        conflictKeys.clear();
        for (ChunkPos pos : conflictChunks) {
            conflictKeys.add(chunkKey(pos.x(), pos.z()));
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;

        ModConfig.ChunkMapConfig mapConfig = ModConfig.get().chunkMap;
        if (mapConfig.background == ModConfig.ChunkMapBackground.BLACK) {
            ctx.fill(0, 0, this.width, this.height, 0xFF101010);
        }

        int halfW = this.width  / 2;
        int halfH = this.height / 2;
        int visCX = this.width  / cellSize / 2 + 2;
        int visCZ = this.height / cellSize / 2 + 2;

        int cxMin = (int) Math.floor(viewCX) - visCX;
        int cxMax = (int) Math.ceil(viewCX)  + visCX;
        int czMin = (int) Math.floor(viewCZ) - visCZ;
        int czMax = (int) Math.ceil(viewCZ)  + visCZ;

        long now = System.currentTimeMillis();
        hoveredChunk = null;

        int sparseThreshold = clampedSparseRenderCellThreshold();
        if (cellSize <= sparseThreshold) {
            renderSparse(ctx, halfW, halfH, cxMin, cxMax, czMin, czMax, now);
        } else {
            renderDense(ctx, halfW, halfH, cxMin, cxMax, czMin, czMax, now);
        }
        hoveredChunk = screenToChunk(mx, my, halfW, halfH);

        // Player marker
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            double px = client.player.getX() / 16.0;
            double pz = client.player.getZ() / 16.0;
            int sx = halfW + (int) Math.round((px - viewCX) * cellSize);
            int sz = halfH + (int) Math.round((pz - viewCZ) * cellSize);
            ctx.fill(sx - 1, sz - 1, sx + 2, sz + 2, 0xFFFFFFFF);
        }

        // Dialog overlay
        if (dialogChunk != null) drawConflictDialog(ctx);

        super.extractRenderState(ctx, mx, my, delta);

        // Tooltip
        if (hoveredChunk != null && dialogChunk == null) {
            drawTooltipForChunk(ctx, hoveredChunk, mx, my, now);
        }
    }

    private int computeColor(ChunkDatabase.ChunkRecord rec, long now) {
        if (rec == null) return 0;
        if ("world_mirror".equals(rec.updateSource())) {
            return interpolateGreenBlue(now - rec.updateTime());
        }
        return COLOR_EXTERNAL;
    }

    private static int interpolateGreenBlue(long ageMs) {
        if (ageMs <= AGE_MIN_MS) return COLOR_FRESH_GREEN;
        if (ageMs >= AGE_MAX_MS) return COLOR_OLD_BLUE;
        double t = Math.log((double) ageMs / AGE_MIN_MS)
                 / Math.log((double) AGE_MAX_MS / AGE_MIN_MS);
        t = Math.max(0.0, Math.min(1.0, t));
        int g = (int) (200 * (1 - t));
        int b = (int) (200 * t);
        return 0xFF000000 | (g << 8) | b;
    }

    private static void drawConflictBorder(GuiGraphicsExtractor ctx, int x, int z, int size) {
        int c = COLOR_CONFLICT_BORDER;
        if (size < 3) { ctx.fill(x, z, x + size, z + size, c); return; }
        ctx.fill(x + 1, z + 1, x + size - 1, z + 2,           c);
        ctx.fill(x + 1, z + size - 2, x + size - 1, z + size - 1, c);
        ctx.fill(x + 1, z + 1, x + 2,       z + size - 1,     c);
        ctx.fill(x + size - 2, z + 1, x + size - 1, z + size - 1, c);
    }

    private void drawTooltipForChunk(GuiGraphicsExtractor ctx, ChunkPos pos, int mx, int my, long now) {
        ChunkDatabase.ChunkRecord rec = recordMap.get(chunkKey(pos.x(), pos.z()));
        boolean hasConflict = conflictChunks.contains(pos);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§eChunk (" + pos.x() + ", " + pos.z() + ")"));
        lines.add(Component.literal("§7Block: (" + (pos.x() * 16) + ", " + (pos.z() * 16) + ")"));
        if (rec != null) {
            lines.add(Component.literal("§7Updated: §f" + formatAge((now - rec.updateTime()) / 1000)));
            lines.add(Component.literal("§7Source: §f" + rec.updateSource()));
        } else {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.notDownloaded"));
        }
        if (hasConflict) {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.conflict"));
            lines.add(Component.translatable("screen.worldmirror.chunkmap.clickResolve"));
        }
        ctx.setComponentTooltipForNextFrame(this.font, lines, mx, my);
    }

    private void renderDense(GuiGraphicsExtractor ctx, int halfW, int halfH,
                             int cxMin, int cxMax, int czMin, int czMax, long now) {
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                int sx = halfW + (int) Math.round((cx - viewCX) * cellSize);
                int sz = halfH + (int) Math.round((cz - viewCZ) * cellSize);
                long key = chunkKey(cx, cz);

                int fill = computeColor(recordMap.get(key), now);
                if (fill != 0) ctx.fill(sx, sz, sx + cellSize, sz + cellSize, fill);

                ctx.fill(sx, sz, sx + cellSize, sz + 1, COLOR_GRID);
                ctx.fill(sx, sz, sx + 1, sz + cellSize, COLOR_GRID);

                if (conflictKeys.contains(key)) drawConflictBorder(ctx, sx, sz, cellSize);
            }
        }
    }

    private void renderSparse(GuiGraphicsExtractor ctx, int halfW, int halfH,
                              int cxMin, int cxMax, int czMin, int czMax, long now) {
        for (ChunkDatabase.ChunkRecord rec : recordMap.values()) {
            int cx = rec.x();
            int cz = rec.z();
            if (cx < cxMin || cx > cxMax || cz < czMin || cz > czMax) continue;
            int sx = halfW + (int) Math.round((cx - viewCX) * cellSize);
            int sz = halfH + (int) Math.round((cz - viewCZ) * cellSize);
            int fill = computeColor(rec, now);
            if (fill != 0) ctx.fill(sx, sz, sx + cellSize, sz + cellSize, fill);
        }

        for (ChunkPos pos : conflictChunks) {
            int cx = pos.x();
            int cz = pos.z();
            if (cx < cxMin || cx > cxMax || cz < czMin || cz > czMax) continue;
            int sx = halfW + (int) Math.round((cx - viewCX) * cellSize);
            int sz = halfH + (int) Math.round((cz - viewCZ) * cellSize);
            drawConflictBorder(ctx, sx, sz, cellSize);
        }
    }

    // ── Dialog ────────────────────────────────────────────────────────────────

    private void drawConflictDialog(GuiGraphicsExtractor ctx) {
        int dw = 220, dh = 90;
        int dx = (this.width - dw) / 2, dy = (this.height - dh) / 2;
        ctx.fill(dx, dy, dx + dw, dy + dh, 0xFF202020);
        int b = 0xFFAA4444;
        ctx.fill(dx, dy, dx + dw, dy + 1, b);
        ctx.fill(dx, dy, dx + 1, dy + dh, b);
        ctx.fill(dx + dw - 1, dy, dx + dw, dy + dh, b);
        ctx.fill(dx, dy + dh - 1, dx + dw, dy + dh, b);
        ctx.centeredText(this.font,
                Component.translatable("screen.worldmirror.chunkmap.dialog.title"),
                this.width / 2, dy + 8, 0xFFFFFFFF);
        if (dialogChunk != null) {
            ctx.centeredText(this.font,
                    Component.literal("(" + dialogChunk.x() + ", " + dialogChunk.z() + ")"),
                    this.width / 2, dy + 22, 0xFFAAAAAA);
        }
        ctx.centeredText(this.font,
                Component.translatable("screen.worldmirror.chunkmap.dialog.prompt"),
                this.width / 2, dy + 36, 0xFFCCCCCC);
    }

    private void openDialog(ChunkPos chunk) {
        dialogChunk = chunk;
        setDialogButtonsVisible(true);
    }

    private void closeDialog() {
        dialogChunk = null;
        setDialogButtonsVisible(false);
    }

    private void setDialogButtonsVisible(boolean visible) {
        if (dialogCancelBtn != null)    dialogCancelBtn.visible    = visible;
        if (dialogOverwriteBtn != null) dialogOverwriteBtn.visible = visible;
        if (dialogDiscardBtn != null)   dialogDiscardBtn.visible   = visible;
    }

    // ── Input handling ────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (super.mouseClicked(event, doubleClick)) return true;

        if (dialogChunk != null) {
            int dw = 220, dh = 90;
            int dx = (this.width - dw) / 2, dy = (this.height - dh) / 2;
            if (mx < dx || mx > dx + dw || my < dy || my > dy + dh) closeDialog();
            return true;
        }

        if (button == 0) {
            if (hoveredChunk != null && conflictChunks.contains(hoveredChunk)) {
                openDialog(hoveredChunk);
                return true;
            }
            isDragging = true;
            dragStartX = mx; dragStartY = my;
            viewCXOnDrag = viewCX; viewCZOnDrag = viewCZ;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) isDragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (isDragging && dialogChunk == null) {
            double mx = event.x();
            double my = event.y();
            viewCX = viewCXOnDrag + (dragStartX - mx) / cellSize;
            viewCZ = viewCZOnDrag + (dragStartY - my) / cellSize;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hDelta, double vDelta) {
        if (dialogChunk == null) {
            cellSize = Math.max(CELL_SIZE_MIN, Math.min(CELL_SIZE_MAX,
                    cellSize + (vDelta > 0 ? 1 : -1)));
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private ChunkPos screenToChunk(int sx, int sz, int halfW, int halfH) {
        int cx = (int) Math.floor(viewCX + ((double) sx - halfW) / cellSize + 0.5D);
        int cz = (int) Math.floor(viewCZ + ((double) sz - halfH) / cellSize + 0.5D);
        return new ChunkPos(cx, cz);
    }

    private static int clampedSparseRenderCellThreshold() {
        return Math.max(4, Math.min(16, ModConfig.get().chunkMap.sparseRenderCellThreshold));
    }

    private static String formatAge(long secs) {
        if (secs < 0) secs = 0;
        if (secs < 60)    return secs + "s ago";
        if (secs < 3600)  return (secs / 60) + "m ago";
        if (secs < 86400) return (secs / 3600) + "h ago";
        return (secs / 86400) + "d ago";
    }

    /** Opens a fresh ChunkMapScreen on the game thread. */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new ChunkMapScreen()));
    }
}
