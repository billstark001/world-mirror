package io.github.billstark001.worldmirror.download;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Resumable coordinates for a bulk capture, independent from loaded-world access. */
final class CaptureRegionScan {
    private final ResourceKey<Level> dimension;
    private final long minZ;
    private final long maxX;
    private final long maxZ;
    private long nextX;
    private long nextZ;

    CaptureRegionScan(ResourceKey<Level> dimension, int centerX, int centerZ, int radius) {
        if (radius < 0) throw new IllegalArgumentException("Negative capture radius");
        this.dimension = dimension;
        nextX = Math.max(Integer.MIN_VALUE, (long) centerX - radius);
        nextZ = Math.max(Integer.MIN_VALUE, (long) centerZ - radius);
        minZ = nextZ;
        maxX = Math.min(Integer.MAX_VALUE, (long) centerX + radius);
        maxZ = Math.min(Integer.MAX_VALUE, (long) centerZ + radius);
    }

    boolean isComplete() {
        return nextX > maxX;
    }

    /**
     * Visits at most {@code maxChecks} candidates, stopping before consuming a
     * coordinate when the destination queue or tick budget is exhausted.
     * The predicate returns whether a loaded candidate was newly queued.
     */
    int fill(int maxChecks, BooleanSupplier hasRoomAndTime,
             Predicate<DownloadCaptureQueue.Key> enqueue) {
        int queued = 0;
        for (int checked = 0; checked < maxChecks && !isComplete() && hasRoomAndTime.getAsBoolean(); checked++) {
            DownloadCaptureQueue.Key key = new DownloadCaptureQueue.Key(dimension, (int) nextX, (int) nextZ);
            if (enqueue.test(key)) queued++;
            if (nextZ < maxZ) nextZ++;
            else {
                nextZ = minZ;
                nextX++;
            }
        }
        return queued;
    }
}
