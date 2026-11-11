package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.ExportStatusStore;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.function.Function;

/** Per-view live capture, shared by built-in maps, Xaero, and world overlays. */
public final class ChunkViewportCache {
    private static final int CAPTURE_MARGIN = 16;
    private static final long MIN_CAPTURE_INTERVAL_MS = 500L;
    static long refreshInterval(ChunkBounds range) {
        long span = Math.max((long) range.maxX() - range.minX() + 1, (long) range.maxZ() - range.minZ() + 1);
        return span <= 513 ? MIN_CAPTURE_INTERVAL_MS : span <= 4_097 ? 1_000L : 5_000L;
    }
    record Context(Object source, ResourceKey<Level> dimension, ChunkStatusSnapshot saved, long liveVersion, long pendingVersion, long exportVersion) { }
    private WeakReference<Object> world = new WeakReference<>(null);
    private Context context;
    private ChunkBounds bounds;
    private long capturedAt;
    private LiveChunkStatusSnapshot snapshot = LiveChunkStatusSnapshot.EMPTY;

    public LiveChunkStatusSnapshot get(Minecraft client, ResourceKey<Level> dimension, ChunkBounds visible,
                                       ChunkStatusSnapshot saved, long now) {
        var target = ChunkStatusCache.targetFor(client);
        String source = target == null ? "unknown" : target.sourceId();
        Context next = new Context(target, dimension, saved,
                ChunkListener.mapStatusVersion(dimension), DownloadManager.pendingCaptureStatusVersion(dimension),
                ExportStatusStore.global().version(source, dimension));
        return get(client.level, next, visible, now, range -> LiveChunkStatusSnapshot.capture(dimension, range, saved, source));
    }

    LiveChunkStatusSnapshot get(Object nextWorld, Context next, ChunkBounds visible, long now,
                                Function<ChunkBounds, LiveChunkStatusSnapshot> capture) {
        boolean sameSource = nextWorld == world.get() && context != null
                && java.util.Objects.equals(context.source(), next.source()) && context.dimension().equals(next.dimension())
                && context.saved() == next.saved();
        if (sameSource && bounds.contains(visible) && now >= capturedAt
                && (next.equals(context) || now - capturedAt < refreshInterval(visible))) return snapshot;
        bounds = visible.expand(CAPTURE_MARGIN);
        snapshot = capture.apply(bounds);
        world = new WeakReference<>(nextWorld);
        context = next;
        capturedAt = now;
        return snapshot;
    }

    public void clear() {
        world.clear();
        context = null;
        bounds = null;
        snapshot = LiveChunkStatusSnapshot.EMPTY;
    }
}
