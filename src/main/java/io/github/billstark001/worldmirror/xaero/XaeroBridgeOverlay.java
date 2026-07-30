package io.github.billstark001.worldmirror.xaero;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache;
import io.github.billstark001.worldmirror.ui.ChunkStatusSnapshot;
import io.github.billstark001.worldmirror.ui.ChunkMapView;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache.StatusTarget;
import io.github.billstark001.xaerobridge.api.MapOverlayContext;
import io.github.billstark001.xaerobridge.api.OverlayRegistration;
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

        // The bridge canvas has no text API.  A compact yellow marker makes it
        // explicit that this overlay is reading the save currently being played,
        // rather than a separate output mirror.
        StatusTarget target = ChunkStatusCache.targetFor(client);
        if (target != null && target.currentWorldIsMirror()) {
            context.canvas().fill(4, 4, 10, 10, 0xFFFFFF00);
        }
    }
}
