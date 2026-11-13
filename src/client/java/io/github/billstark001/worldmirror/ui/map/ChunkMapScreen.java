package io.github.billstark001.worldmirror.ui.map;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
#if MC_VERSION >= 260200
import io.github.billstark001.worldmirror.ui.ClientScreens;
#endif
import io.github.billstark001.worldmirror.ui.ConflictActions;
import io.github.billstark001.worldmirror.ui.ExportStatusText;
import io.github.billstark001.worldmirror.ui.map.ChunkStatusCache.StatusTarget;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Window 1 — full-screen chunk map.
 *
 * <p>Displays a draggable grid where each cell represents one chunk.  Cell colours
 * indicate download status:
 * <ul>
 *   <li><b>Transparent</b> — never downloaded.</li>
 *   <li><b>Green→Blue</b> — downloaded via World Mirror or the replay toolkit; colour shifts from
 *       green (≤10 min ago) to blue (≥1 month ago) on a logarithmic scale.</li>
 *   <li><b>Orange</b> — written by another external source.</li>
 *   <li><b>Yellow / bright green</b> — waiting for capture / waiting for a durable write.</li>
 * </ul>
 *
 * <p>If a chunk has an unresolved conflict entry a <b>purple border</b> is drawn
 * inside its cell.  Clicking such a cell opens a small dialog offering three
 * choices: <i>Cancel</i>, <i>Overwrite</i> (apply the stored server chunk), or
 * <i>Discard</i> (keep the local chunk and delete the conflict entry).
 */
@Environment(EnvType.CLIENT)
public class ChunkMapScreen extends Screen {
    private static final long PERSISTENT_REFRESH_INTERVAL_MS = 5_000L;
    private static final long SLOW_MAP_FRAME_NS = 8_000_000L;

    private static final class MeasuredCanvas implements ChunkMapView.Canvas {
        private final ChunkMapView.Canvas target;
        private int fills;

        private MeasuredCanvas(ChunkMapView.Canvas target) { this.target = target; }

        @Override
        public void fill(int left, int top, int right, int bottom, int color) {
            fills++;
            target.fill(left, top, right, bottom, color);
        }
    }

    // ── View state ────────────────────────────────────────────────────────────

    private final ChunkMapPan pan = new ChunkMapPan();
    private int cellSize = ChunkMapView.CELL_SIZE_DEFAULT;

    private ChunkPos hoveredChunk;

    // ── Data ─────────────────────────────────────────────────────────────────

    private ChunkStatusSnapshot statusSnapshot = ChunkStatusSnapshot.EMPTY;
    private LiveChunkStatusSnapshot liveSnapshot = LiveChunkStatusSnapshot.EMPTY;
    private final ChunkViewportCache viewportCache = new ChunkViewportCache();
    private final ChunkMapProjection projection = new ChunkMapProjection();
    private StatusTarget statusTarget;
    private boolean resolvingConflict;
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
            pan.centerOn(client.player.chunkPosition().getMinBlockX() >> 4,
                    client.player.chunkPosition().getMinBlockZ() >> 4);
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
                    resolveSelectedConflict(true);
                    closeDialog();
                }
        ).bounds(bx + btnW + gap, btnY, btnW, 20).build());

        dialogDiscardBtn = addRenderableWidget(Button.builder(
                Component.translatable("screen.worldmirror.chunkmap.dialog.discard"),
                btn -> {
                    resolveSelectedConflict(false);
                    closeDialog();
                }
        ).bounds(bx + 2 * (btnW + gap), btnY, btnW, 20).build());

        setDialogButtonsVisible(false);
    }

    private void resolveSelectedConflict(boolean overwrite) {
        StatusTarget selected = statusTarget;
        Minecraft client = Minecraft.getInstance();
        if (resolvingConflict || dialogChunk == null || selected == null || currentDimension == null
                || client.level == null || !currentDimension.equals(client.level.dimension())
                || !selected.equals(ChunkStatusCache.targetFor(client))) return;
        resolvingConflict = true;
        ConflictActions.resolve(selected.mirror(), dialogChunk, currentDimension, overwrite, () -> {
            resolvingConflict = false;
            if (selected.equals(statusTarget)) loadData(Minecraft.getInstance());
        });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadData(Minecraft client) {
        updateTarget(client);
        if (currentDimension == null) currentDimension = Level.OVERWORLD;

        statusSnapshot = ChunkStatusCache.getOrScheduleRefresh(statusTarget, currentDimension, 0L);
        viewportCache.clear();
    }

    private void updateTarget(Minecraft client) {
        StatusTarget next = ChunkStatusCache.targetFor(client);
        ResourceKey<Level> dimension = client == null || client.level == null ? Level.OVERWORLD : client.level.dimension();
        if (!Objects.equals(statusTarget, next) || !dimension.equals(currentDimension)) {
            closeDialog();
            statusTarget = next;
            currentDimension = dimension;
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
#if MC_VERSION >= 260102
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
#else
    public void render(GuiGraphics ctx, int mx, int my, float delta) {
#endif
        ModConfig config = ModConfig.get();
        ModConfig.ChunkMapConfig mapConfig = config.chunkMap;
        boolean measure = config.performance.diagnosticPerformanceLogging;
        long start = measure ? System.nanoTime() : 0L;
        updateTarget(this.minecraft);
        statusSnapshot = ChunkStatusCache.getOrScheduleRefresh(
                statusTarget, currentDimension, PERSISTENT_REFRESH_INTERVAL_MS);
        long savedReady = measure ? System.nanoTime() : 0L;
        long now = System.currentTimeMillis();
        ChunkMapView.BuiltInViewport viewport = viewport(
                mapConfig.background == ModConfig.ChunkMapBackground.TRANSPARENT);
        liveSnapshot = viewportCache.get(this.minecraft, statusTarget, currentDimension,
                ChunkMapView.visibleRange(viewport), statusSnapshot, now);
        long liveReady = measure ? System.nanoTime() : 0L;
        var previousFrame = measure ? projection.currentFrame() : null;
        MeasuredCanvas measured = measure ? new MeasuredCanvas(ctx::fill) : null;
        ChunkMapView.renderBuiltIn(
                measure ? measured : ctx::fill, projection, statusSnapshot, liveSnapshot, viewport, now,
                mapConfig.sparseRenderCellThreshold);
        if (measure) {
            long drawn = System.nanoTime();
            var frame = projection.currentFrame();
            boolean rebuilt = frame != previousFrame;
            if (rebuilt || drawn - start >= SLOW_MAP_FRAME_NS) {
                WMLogger.infoRateLimited("chunk-map-render", 1_000L,
                        "[perf] chunkMap cellPx=" + viewport.cellSize() + " bucketChunks=" + frame.bucketSize()
                                + " layoutBuckets=" + frame.projectedBuckets() + " visibleOccupiedBuckets=" + frame.occupiedBuckets()
                                + " dense=" + frame.dense() + " rebuilt=" + rebuilt + " fills=" + measured.fills
                                + " savedUs=" + (savedReady - start) / 1_000L + " liveUs=" + (liveReady - savedReady) / 1_000L
                                + " geometryAndDrawUs=" + (drawn - liveReady) / 1_000L);
            }
        }
        hoveredChunk = chunkAt(viewport, mx, my);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            ChunkMapView.drawPlayerMarker(
                    ctx::fill, viewport, client.player.getX() / 16.0D, client.player.getZ() / 16.0D);
        }
        if (statusTarget != null && statusTarget.currentWorldIsMirror()) {
#if MC_VERSION >= 260102
            ctx.centeredText(this.font, Component.translatable("screen.worldmirror.chunkmap.currentMirror"),
#else
            ctx.drawCenteredString(this.font, Component.translatable("screen.worldmirror.chunkmap.currentMirror"),
#endif
                    width / 2, 16, 0xFFFFFF55);
        }

        if (dialogChunk != null) drawConflictDialog(ctx);
#if MC_VERSION >= 260102
        super.extractRenderState(ctx, mx, my, delta);
#else
        super.render(ctx, mx, my, delta);
#endif
        if (hoveredChunk != null && dialogChunk == null) {
            drawTooltipForChunk(ctx, hoveredChunk, mx, my, now);
        }
    }

#if MC_VERSION >= 260102
    private void drawTooltipForChunk(GuiGraphicsExtractor ctx, ChunkPos pos, int mx, int my, long now) {
#else
    private void drawTooltipForChunk(GuiGraphics ctx, ChunkPos pos, int mx, int my, long now) {
#endif
        int chunkX = pos.getMinBlockX() >> 4;
        int chunkZ = pos.getMinBlockZ() >> 4;
        ChunkDatabase.ChunkRecord rec = statusSnapshot.getRecord(chunkX, chunkZ);
        LiveChunkStatusSnapshot.Record live = liveSnapshot.getRecord(chunkX, chunkZ);
        boolean hasConflict = statusSnapshot.hasConflict(chunkX, chunkZ);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.worldmirror.chunkmap.chunk", chunkX, chunkZ));
        lines.add(Component.translatable("screen.worldmirror.chunkmap.block", chunkX * 16, chunkZ * 16));
        if (live != null) {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.live."
                    + live.state().name().toLowerCase(Locale.ROOT)));
            if (rec == null && live.capturedAtMs() > 0L) {
                lines.add(Component.translatable("screen.worldmirror.chunkmap.updated",
                        formatAge((now - live.capturedAtMs()) / 1000)));
            }
        }
        if (rec != null) {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.updated", formatAge((now - rec.updateTime()) / 1000)));
            lines.add(Component.translatable("screen.worldmirror.chunkmap.source",
                    "replay_import".equals(rec.updateSource())
                            ? Component.translatable("screen.worldmirror.chunkmap.source.replayImport")
                            : Component.literal(rec.updateSource())));
        } else if (live == null && liveSnapshot.exportAt(chunkX, chunkZ) == null) {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.notDownloaded"));
        }
        ExportStatusText.append(lines, liveSnapshot.exportAt(chunkX, chunkZ), rec != null);
        if (hasConflict) {
            lines.add(Component.translatable("screen.worldmirror.chunkmap.conflict"));
            lines.add(Component.translatable("screen.worldmirror.chunkmap.clickResolve"));
        }
        ctx.setComponentTooltipForNextFrame(this.font, lines, mx, my);
    }

    // ── Dialog ────────────────────────────────────────────────────────────────

#if MC_VERSION >= 260102
    private void drawConflictDialog(GuiGraphicsExtractor ctx) {
        ChunkMapView.DialogBounds bounds = ChunkMapView.drawDialogFrame(ctx::fill, width, height);
        ctx.centeredText(this.font,
#else
    private void drawConflictDialog(GuiGraphics ctx) {
        ChunkMapView.DialogBounds bounds = ChunkMapView.drawDialogFrame(ctx::fill, width, height);
        ctx.drawCenteredString(this.font,
#endif
                Component.translatable("screen.worldmirror.chunkmap.dialog.title"),
                bounds.centerX(), bounds.top() + 8, 0xFFFFFFFF);
        if (dialogChunk != null) {
            int chunkX = dialogChunk.getMinBlockX() >> 4;
            int chunkZ = dialogChunk.getMinBlockZ() >> 4;
#if MC_VERSION >= 260102
            ctx.centeredText(this.font,
#else
            ctx.drawCenteredString(this.font,
#endif
                    Component.literal("(" + chunkX + ", " + chunkZ + ")"),
                    bounds.centerX(), bounds.top() + 22, 0xFFAAAAAA);
        }
#if MC_VERSION >= 260102
        ctx.centeredText(this.font,
#else
        ctx.drawCenteredString(this.font,
#endif
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
        if (super.mouseClicked(event, doubleClick)) return true;

        if (dialogChunk != null) {
            if (!ChunkMapView.dialogBounds(width, height).contains(mx, my)) closeDialog();
            return true;
        }

        if (ChunkMapPan.isPrimary(event) && hoveredChunk != null && statusTarget != null
                && ConflictActions.canResolve(statusTarget.mirror()) && statusSnapshot.hasConflict(
                hoveredChunk.getMinBlockX() >> 4, hoveredChunk.getMinBlockZ() >> 4)) {
            openDialog(hoveredChunk);
            return true;
        }
        pan.begin(event);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return pan.release(event) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dialogChunk == null && pan.move(event, cellSize)) return true;
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
                width, height, pan.x(), pan.z(), cellSize, transparentBackground);
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
#if MC_VERSION >= 260200
        ClientScreens.setLater(new ChunkMapScreen());
#else
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new ChunkMapScreen()));
#endif
    }
}
