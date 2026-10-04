package io.github.billstark001.worldmirror.xaero;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.ui.ChunkStatusCache;
import io.github.billstark001.worldmirror.ui.ChunkStatusSnapshot;
import io.github.billstark001.worldmirror.ui.ChunkMapView;
import io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot;
import io.github.billstark001.xaerobridge.api.MapOverlayContext;
import io.github.billstark001.xaerobridge.api.OverlayRegistration;
import io.github.billstark001.xaerobridge.api.XaeroWorldMapBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;

/**
 * World Mirror's optional integration with Xaero World Map Bridge.
 * The bridge, rather than this mod, owns every Xaero mixin and the exact/tail
 * injection policy.  Keeping this renderer on the public bridge API means World
 * Mirror can remain usable when neither Xaero nor the bridge is installed.
 */
public final class XaeroBridgeOverlay {
    private static OverlayRegistration registration;
    private static LiveChunkStatusSnapshot liveSnapshot = LiveChunkStatusSnapshot.EMPTY;
    private static ChunkMapView.ChunkRange liveCaptureRange;
    private static ResourceKey<Level> liveDimension;
    private static ChunkStatusCache.StatusTarget liveTarget;
    private static WeakReference<Object> liveLevel = new WeakReference<>(null);
    private static long nextLiveRefreshMs;

    private XaeroBridgeOverlay() {}

    public static synchronized void install() {
        if (registration == null) {
            registration = XaeroWorldMapBridge.registerMapOverlay(
                    "worldmirror:chunk-status", 0, XaeroBridgeOverlay::render);
            XaeroIndicatorText.install();
        }
    }

    private static void render(MapOverlayContext context) {
        ModConfig.ChunkMapConfig config = ModConfig.get().chunkMap;
        Minecraft client = Minecraft.getInstance();
        if (!config.showXaeroWorldMapOverlay || client.level == null) return;

        ResourceKey<Level> dimension = client.level.dimension();
        long refreshMs = Math.max(1, config.xaeroWorldMapOverlayRefreshSeconds) * 1_000L;
        ChunkStatusSnapshot snapshot = ChunkStatusCache.getOrScheduleRefresh(client, dimension, refreshMs);
        long now = System.currentTimeMillis();
        ChunkMapView.ChunkRange visible = ChunkMapView.overlayVisibleRange(
                context.width(), context.height(), context.cameraX(), context.cameraZ(),
                context.pixelsPerBlock());
        if (visible == null) return;
        refreshLiveSnapshot(client, dimension, visible, snapshot, now);
        ChunkMapView.renderOverlay(
                context.canvas()::fill,
                context::worldToScreenX,
                context::worldToScreenY,
                snapshot,
                liveSnapshot,
                context.width(),
                context.height(),
                context.cameraX(),
                context.cameraZ(),
                context.pixelsPerBlock(),
                now,
                config.xaeroWorldMapOverlayMaxCells);
    }

    private static void refreshLiveSnapshot(Minecraft client, ResourceKey<Level> dimension,
                                            ChunkMapView.ChunkRange visible,
                                            ChunkStatusSnapshot persistent, long now) {
        ChunkStatusCache.StatusTarget target = ChunkStatusCache.targetFor(client);
        if (liveCaptureRange != null && dimension.equals(liveDimension)
                && client.level == liveLevel.get()
                && java.util.Objects.equals(target, liveTarget)
                && now < nextLiveRefreshMs
                && visible.minX() >= liveCaptureRange.minX()
                && visible.maxX() <= liveCaptureRange.maxX()
                && visible.minZ() >= liveCaptureRange.minZ()
                && visible.maxZ() <= liveCaptureRange.maxZ()) return;
        int margin = 16;
        liveCaptureRange = new ChunkMapView.ChunkRange(
                visible.minX() - margin, visible.maxX() + margin,
                visible.minZ() - margin, visible.maxZ() + margin);
        liveSnapshot = LiveChunkStatusSnapshot.capture(dimension, liveCaptureRange, persistent);
        liveDimension = dimension;
        liveTarget = target;
        liveLevel = new WeakReference<>(client.level);
        nextLiveRefreshMs = now + 500L;
    }
}
