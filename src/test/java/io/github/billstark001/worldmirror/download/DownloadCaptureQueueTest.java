package io.github.billstark001.worldmirror.download;

import java.util.Set;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.LightingUpdate;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadCaptureQueueTest {
    @Test
    void resourcePauseKeepsAcceptedHintsAndUnloadStillCapturesThem() {
        var controller = new CapturePolicy();
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 2);
        var first = new DownloadCaptureQueue.Key(Level.OVERWORLD, 1, 0);
        var second = new DownloadCaptureQueue.Key(Level.OVERWORLD, 2, 0);
        var removed = new DownloadCaptureQueue.Key(Level.OVERWORLD, 3, 0);
        queue.queue(first, "full-chunk-packet", false);
        queue.queue(second, "full-chunk-packet", false);
        queue.queue(removed, "block-update", true);

        controller.update(0, new CapturePolicy.Sample(98, 100, 1, 1, 0, 0));
        queue.tick(null, CaptureBudget.forMode(controller.mode(), 1500));
        assertEquals(3, queue.metrics().pendingCaptures());

        CompoundTag terrain = new CompoundTag();
        terrain.putString("terrain", "saved before unload");
        assertTrue(queue.captureBeforeUnload(first, () -> false, () -> terrain));
        assertTrue(queue.captureBeforeUnload(removed, () -> true, CompoundTag::new));
        assertEquals("saved before unload",
                ChunkListener.getDimension(Level.OVERWORLD)
                        .get(new ChunkPos(1, 0))
                        .nbt()
                        .getStringOr("terrain", ""));
        assertTrue(ChunkListener.getDimension(Level.OVERWORLD).get(new ChunkPos(3, 0)).authoritativeEmpty());
        assertEquals(second, queue.pollPending().key());
        assertFalse(queue.hasWork());
    }

    @Test
    void pausedQueueStillFinishesLightWindowsWithoutDroppingOrdinaryHints() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 8);
        var light = new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0);
        var terrain = new DownloadCaptureQueue.Key(Level.OVERWORLD, 1, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, new ChunkPos(0, 0), new CompoundTag());
        long before = ChunkListener.getDimension(Level.OVERWORLD).get(new ChunkPos(0, 0)).revision();
        queue.markLightDirty(light);
        queue.queue(terrain, "full-chunk-packet", false);
        queue.tick(null, CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 1500));
        assertTrue(queue.isLightingPending(Level.OVERWORLD, new ChunkPos(0, 0)));
        queue.tick(null, CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 1500));
        assertFalse(queue.isLightingPending(Level.OVERWORLD, new ChunkPos(0, 0)));
        assertTrue(ChunkListener.getDimension(Level.OVERWORLD).get(new ChunkPos(0, 0)).revision() > before);
        assertEquals(terrain, queue.pollPending().key());
    }

    @Test
    void pendingStatusRetainsOverlappingLightAndCaptureHintsUntilBothFinish() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 8);
        var key = new DownloadCaptureQueue.Key(Level.OVERWORLD, -1, 0);
        queue.queue(key, "test", false);
        queue.markLightDirty(key);
        long version = queue.statusVersion(Level.OVERWORLD);
        queue.pollPending();
        assertEquals(Set.of(new ChunkPos(-1, 0)), queue.pendingChunkPositions(Level.OVERWORLD, -1, 0, 0, 0));
        assertEquals(version, queue.statusVersion(Level.OVERWORLD),
                "the visible state remains pending while light is queued");
        queue.finishLightUpdates();
        assertTrue(queue.pendingChunkPositions(Level.OVERWORLD, -1, 0, 0, 0).isEmpty());
        assertTrue(queue.statusVersion(Level.OVERWORLD) > version);
    }
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clear() {
        ChunkListener.clear();
    }

    @Test
    void finishingCapturePreservesAppliedLightEvenBeforeTheCoalescingDeadline() {
        ChunkPos pos = new ChunkPos(0, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        long previous = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, previous)), false);
        assertEquals(0, ChunkListener.getDirtyCount());
        BitSet mask = new BitSet();
        mask.set(0);
        byte[] light = new byte[2048];
        light[0] = 7;
        ChunkListener.applyLightUpdate(Level.OVERWORLD, pos,
                new LightingUpdate(
                        0, 1, mask, new BitSet(), List.of(light), new BitSet(), new BitSet(), List.of()));
        DownloadCaptureQueue queue = new DownloadCaptureQueue(() -> false, 1, () -> 8192);
        for (int hint = 0; hint < 10; hint++) {
            queue.markLightDirty(new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0));
        }
        assertTrue(queue.isLightingPending(Level.OVERWORLD, new ChunkPos(0, 0)));

        queue.finishLightUpdates();
        queue.clear();

        assertEquals(1, ChunkListener.getDirtyCount());
        assertFalse(queue.hasWork());
        var snapshot = ChunkListener.snapshotDirtyState();
        var captured = snapshot.chunks().get(Level.OVERWORLD).get(pos);
        assertTrue(captured.revision() > previous);
        CompoundTag nbt = snapshot.materialize(Level.OVERWORLD, pos, captured).nbt();
        assertArrayEquals(light,
                nbt.getListOrEmpty("sections")
                        .getCompound(0)
                        .orElseThrow()
                        .getByteArray("BlockLight")
                        .orElseThrow());
    }
}
