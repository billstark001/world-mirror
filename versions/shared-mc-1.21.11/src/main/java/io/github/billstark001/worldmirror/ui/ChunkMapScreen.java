package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache.StatusTarget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    // ── View state ────────────────────────────────────────────────────────────

    private double viewCX = 0, viewCZ = 0;
    private int cellSize = ChunkMapView.CELL_SIZE_DEFAULT;

    private boolean isDragging;
    private double dragStartX, dragStartY, viewCXOnDrag, viewCZOnDrag;

    private ChunkPos hoveredChunk;

    // ── Data ─────────────────────────────────────────────────────────────────

    private ChunkStatusSnapshot statusSnapshot = ChunkStatusSnapshot.EMPTY;
    private Path worldFolder;
    private boolean viewingCurrentMirror;
    private ResourceKey<Level> currentDimension;

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
            viewCX = client.player.chunkPosition().getMinBlockX() >> 4;
            viewCZ = client.player.chunkPosition().getMinBlockZ() >> 4;
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
                        ChunkStatusCache.invalidate();
                        loadData(Minecraft.getInstance());
                    }
                    closeDialog();
                }
        ).bounds(bx + btnW + gap, btnY, btnW, 20).build());

        dialogDiscardBtn = addRenderableWidget(Button.builder(
                Component.translatable("screen.worldmirror.chunkmap.dialog.discard"),
                btn -> {
                    if (dialogChunk != null && worldFolder != null && currentDimension != null) {
                        ConflictManager.resolveConflict(worldFolder, dialogChunk, currentDimension, false);
                        ChunkStatusCache.invalidate();
                        loadData(Minecraft.getInstance());
                    }
                    closeDialog();
                }
        ).bounds(bx + 2 * (btnW + gap), btnY, btnW, 20).build());

        setDialogButtonsVisible(false);
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadData(Minecraft client) {
        StatusTarget target = ChunkStatusCache.targetFor(client);
        worldFolder = target != null ? target.worldFolder() : null;
        viewingCurrentMirror = target != null && target.currentWorldIsMirror();
        if (currentDimension == null) currentDimension = Level.OVERWORLD;

        statusSnapshot = ChunkStatusCache.getOrScheduleRefresh(client, currentDimension, 0L);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics ctx, int mx, int my, float delta) {
        statusSnapshot = ChunkStatusCache.getOrScheduleRefresh(Minecraft.getInstance(), currentDimension, 1_000L);
        ModConfig.ChunkMapConfig mapConfig = ModConfig.get().chunkMap;
        long now = System.currentTimeMillis();
        ChunkMapView.BuiltInViewport viewport = viewport(
                mapConfig.background == ModConfig.ChunkMapBackground.TRANSPARENT);
        ChunkMapView.renderBuiltIn(
                ctx::fill, statusSnapshot, viewport, now, mapConfig.sparseRenderCellThreshold);
        hoveredChunk = chunkAt(viewport, mx, my);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            ChunkMapView.drawPlayerMarker(
                    ctx::fill, viewport, client.player.getX() / 16.0D, client.player.getZ() / 16.0D);
        }
        if (viewingCurrentMirror) {
            ctx.drawCenteredString(this.font, Component.translatable("screen.worldmirror.chunkmap.currentMirror"),
                    width / 2, 16, 0xFFFFFF55);
        }

        if (dialogChunk != null) drawConflictDialog(ctx);
        super.render(ctx, mx, my, delta);
        if (hoveredChunk != null && dialogChunk == null) {
            drawTooltipForChunk(ctx, hoveredChunk, mx, my, now);
        }
    }

    private void drawTooltipForChunk(GuiGraphics ctx, ChunkPos pos, int mx, int my, long now) {
        int chunkX = pos.getMinBlockX() >> 4;
        int chunkZ = pos.getMinBlockZ() >> 4;
        ChunkDatabase.ChunkRecord rec = statusSnapshot.getRecord(chunkX, chunkZ);
        boolean hasConflict = statusSnapshot.hasConflict(chunkX, chunkZ);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.worldmirror.chunkmap.chunk", chunkX, chunkZ));
        lines.add(Component.translatable("screen.worldmirror.chunkmap.block", chunkX * 16, chunkZ * 16));
        if (rec != null) {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.updated", formatAge((now - rec.updateTime()) / 1000)));
            lines.add(Component.translatable("screen.worldmirror.chunkmap.source", rec.updateSource()));
        } else {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.notDownloaded"));
        }
        if (hasConflict) {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.conflict"));
            lines.add(Component.translatable("screen.worldmirror.chunkmap.clickResolve"));
        }
        ctx.setComponentTooltipForNextFrame(this.font, lines, mx, my);
    }

    // ── Dialog ────────────────────────────────────────────────────────────────

    private void drawConflictDialog(GuiGraphics ctx) {
        ChunkMapView.DialogBounds bounds = ChunkMapView.drawDialogFrame(ctx::fill, width, height);
        ctx.drawCenteredString(this.font,
                Component.translatable("screen.worldmirror.chunkmap.dialog.title"),
                bounds.centerX(), bounds.top() + 8, 0xFFFFFFFF);
        if (dialogChunk != null) {
            int chunkX = dialogChunk.getMinBlockX() >> 4;
            int chunkZ = dialogChunk.getMinBlockZ() >> 4;
            ctx.drawCenteredString(this.font,
                    Component.literal("(" + chunkX + ", " + chunkZ + ")"),
                    bounds.centerX(), bounds.top() + 22, 0xFFAAAAAA);
        }
        ctx.drawCenteredString(this.font,
                Component.translatable("screen.worldmirror.chunkmap.dialog.prompt"),
                bounds.centerX(), bounds.top() + 36, 0xFFCCCCCC);
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
            if (!ChunkMapView.dialogBounds(width, height).contains(mx, my)) closeDialog();
            return true;
        }

        if (button == 0) {
            if (hoveredChunk != null && statusSnapshot.hasConflict(
                    hoveredChunk.getMinBlockX() >> 4, hoveredChunk.getMinBlockZ() >> 4)) {
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
            cellSize = ChunkMapView.adjustCellSize(cellSize, vDelta);
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChunkMapView.BuiltInViewport viewport(boolean transparentBackground) {
        return new ChunkMapView.BuiltInViewport(
                width, height, viewCX, viewCZ, cellSize, transparentBackground);
    }

    private static ChunkPos chunkAt(ChunkMapView.BuiltInViewport viewport, double x, double z) {
        ChunkMapView.ChunkCoordinate chunk = ChunkMapView.screenToChunk(viewport, x, z);
        return new ChunkPos(chunk.x(), chunk.z());
    }

    private static Component formatAge(long secs) {
        if (secs < 0) secs = 0;
        if (secs < 60)    return Component.translatable("screen.worldmirror.status.age.seconds", secs);
        if (secs < 3600)  return Component.translatable("screen.worldmirror.status.age.minutes", secs / 60);
        if (secs < 86400) return Component.translatable("screen.worldmirror.status.age.hours", secs / 3600);
        return Component.translatable("screen.worldmirror.status.age.days", secs / 86400);
    }

    /** Opens a fresh ChunkMapScreen on the game thread. */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new ChunkMapScreen()));
    }
}
