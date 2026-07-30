package io.github.billstark001.worldmirror.xaero;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache;
import io.github.billstark001.worldmirror.ui.ChunkStatusSnapshot;
import io.github.billstark001.worldmirror.ui.ChunkMapView;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache.StatusTarget;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import io.github.billstark001.xaerobridge.api.MapOverlayContext;
import io.github.billstark001.xaerobridge.api.OverlayRegistration;
import io.github.billstark001.xaerobridge.api.UiOverlayContext;
import io.github.billstark001.xaerobridge.api.XaeroWorldMapBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * World Mirror's optional integration with Xaero World Map Bridge.
 * The bridge, rather than this mod, owns every Xaero mixin and the exact/tail
 * injection policy.  Keeping this renderer on the public bridge API means World
 * Mirror can remain usable when neither Xaero nor the bridge is installed.
 */
public final class XaeroBridgeOverlay {
    private static OverlayRegistration registration;
    private static OverlayRegistration indicatorRegistration;
    private static volatile boolean indicatorToastShown;

    private XaeroBridgeOverlay() {}

    public static synchronized void install() {
        if (registration == null) {
            registration = XaeroWorldMapBridge.registerMapOverlay(
                    "worldmirror:chunk-status", 0, XaeroBridgeOverlay::render);
            indicatorRegistration = XaeroWorldMapBridge.registerUiOverlay(
                    "worldmirror:mirror-indicator", 0, XaeroBridgeOverlay::renderIndicator);
        }
    }

    private static void render(MapOverlayContext context) {
        ModConfig.ChunkMapConfig config = ModConfig.get().chunkMap;
        Minecraft client = Minecraft.getInstance();
        if (!config.showXaeroWorldMapOverlay || client.level == null) return;

        ResourceKey<Level> dimension = client.level.dimension();
        long refreshMs = Math.max(1, config.xaeroWorldMapOverlayRefreshSeconds) * 1_000L;
        ChunkStatusSnapshot snapshot = ChunkStatusCache.getOrScheduleRefresh(client, dimension, refreshMs);
        ChunkMapView.renderOverlay(
                context.canvas()::fill,
                context::worldToScreenX,
                context::worldToScreenY,
                snapshot,
                context.width(),
                context.height(),
                context.cameraX(),
                context.cameraZ(),
                context.pixelsPerBlock(),
                System.currentTimeMillis(),
                config.xaeroWorldMapOverlayMaxCells);

    }

    /** Drawn after Xaero's widgets, avoiding the old settings-button overlap. */
    private static void renderIndicator(UiOverlayContext context) {
        Minecraft client = Minecraft.getInstance();
        StatusTarget target = ChunkStatusCache.targetFor(client);
        if (target != null && target.currentWorldIsMirror()) {
            int x = Math.min(36, Math.max(4, context.width() - 50));
            int y = 5;
            context.canvas().fill(x, y, x + 46, y + 18, 0xD0000000);
            context.canvas().fill(x, y, x + 46, y + 2, 0xFFFFC000);
            context.canvas().fill(x, y + 16, x + 46, y + 18, 0xFFFFC000);
            context.canvas().fill(x, y, x + 2, y + 18, 0xFFFFC000);
            context.canvas().fill(x + 44, y, x + 46, y + 18, 0xFFFFC000);
            drawWmGlyph(context, x + 7, y + 5);
            if (!indicatorToastShown) {
                indicatorToastShown = true;
                client.execute(() -> ClientDialogs.toast(client,
                        new MirrorPrompt.Text("toast.worldmirror.mirrorIndicator.title"),
                        new MirrorPrompt.Text("toast.worldmirror.mirrorIndicator.body")));
            }
        } else {
            indicatorToastShown = false;
        }
    }

    /** A legible fill-only WM glyph; Bridge 0.1.0 intentionally has no text API. */
    private static void drawWmGlyph(UiOverlayContext context, int x, int y) {
        int color = 0xFFFFE066;
        context.canvas().fill(x, y, x + 2, y + 8, color);
        context.canvas().fill(x + 9, y, x + 11, y + 8, color);
        context.canvas().fill(x + 2, y + 5, x + 4, y + 8, color);
        context.canvas().fill(x + 7, y + 5, x + 9, y + 8, color);
        context.canvas().fill(x + 4, y + 6, x + 7, y + 8, color);
        x += 15;
        context.canvas().fill(x, y, x + 2, y + 8, color);
        context.canvas().fill(x + 9, y, x + 11, y + 8, color);
        context.canvas().fill(x + 2, y, x + 4, y + 3, color);
        context.canvas().fill(x + 7, y, x + 9, y + 3, color);
        context.canvas().fill(x + 4, y + 2, x + 7, y + 4, color);
    }
}
