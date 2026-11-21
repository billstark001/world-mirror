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
import static org.junit.jupiter.api.Assertions.fail;

class DownloadCaptureQueueTest {
    @Test
    void resourcePauseKeepsAcceptedHintsAndUnloadStillCapturesThem() {
        var controller = new CapturePolicy();
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 2);
        var first = new DownloadCaptureQueue.Key(Level.OVERWORLD, 1, 0);
        var second = new DownloadCaptureQueue.Key(Level.OVERWORLD, 2, 0);
        var removed = new DownloadCaptureQueue.Key(Level.OVERWORLD, 3, 0);
        queue.queueObservation(first, "full-chunk-packet");
        queue.queueObservation(second, "full-chunk-packet");
        queue.queuePayloadChange(removed, "block-update", true);

        controller.update(0, new CapturePolicy.Sample(98, 100, 1, 1, 0, 0));
        queue.tick(null, CaptureBudget.forMode(controller.mode(), 1500));
        assertEquals(3, queue.metrics().pendingCaptures());

        CompoundTag terrain = new CompoundTag();
        terrain.putString("terrain", "saved before unload");
        assertTrue(queue.captureLoaded(first, null, () -> false, () -> terrain));
        assertTrue(queue.captureLoaded(removed, null, () -> true, CompoundTag::new));
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
        ChunkListener.applyLightUpdate(Level.OVERWORLD, new ChunkPos(0, 0), lightUpdate((byte) 1));
        long applied = ChunkListener.getDimension(Level.OVERWORLD).get(new ChunkPos(0, 0)).revision();
        queue.markLightDirty(light);
        queue.queueObservation(terrain, "full-chunk-packet");
        queue.tick(null, CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 1500));
        assertTrue(queue.isLightingPending(Level.OVERWORLD, new ChunkPos(0, 0)));
        queue.tick(null, CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 1500));
        assertFalse(queue.isLightingPending(Level.OVERWORLD, new ChunkPos(0, 0)));
        assertTrue(applied > before);
        assertEquals(applied, ChunkListener.getDimension(Level.OVERWORLD).get(new ChunkPos(0, 0)).revision());
        assertEquals(terrain, queue.pollPending().key());
    }

    @Test
    void pendingStatusRetainsOverlappingLightAndCaptureHintsUntilBothFinish() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 8);
        var key = new DownloadCaptureQueue.Key(Level.OVERWORLD, -1, 0);
        queue.queueObservation(key, "test");
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
    void repeatedFinalSamplingSkipsAnAlreadyExportedRemoteChunkUntilItsPayloadChanges() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 2);
        var key = new DownloadCaptureQueue.Key(Level.OVERWORLD, -1, 1);
        var pos = new ChunkPos(-1, 1);
        var loadedChunk = new Object();
        assertTrue(queue.captureLoaded(key, loadedChunk, () -> false, CompoundTag::new));
        var previous = ChunkListener.getDimension(Level.OVERWORLD).get(pos);
        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, previous.revision())), true);
        assertFalse(queue.captureLoaded(
                key, loadedChunk, () -> fail("Already sampled"), () -> fail("Already exported")));
        assertEquals(0, ChunkListener.getDirtyCount());
        assertFalse(queue.queueScannedChunk(key, loadedChunk, "resource-recovery"));
        assertTrue(queue.queueScannedChunk(key, loadedChunk, "pre-export"),
                "An explicit export still refreshes the loaded payload");
        assertEquals(key, queue.pollPending().key());

        queue.queuePayloadChange(key, "block-entity-update", false);
        assertTrue(queue.captureLoaded(key, loadedChunk, () -> false, CompoundTag::new));
        assertTrue(ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision() > previous.revision());
        assertTrue(queue.captureLoaded(key, new Object(), () -> false, CompoundTag::new),
                "A different loaded chunk at the same coordinates needs its own capture");
        queue.clear();
        assertTrue(queue.captureLoaded(key, loadedChunk, () -> false, CompoundTag::new),
                "A restarted session must resume capturing the loaded world");
    }

    @Test
    void payloadChangesSurviveCapacityAndDoNotAuthorizeEmptyTerrain() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 1);
        var ordinary = new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0);
        var blockEntity = new DownloadCaptureQueue.Key(Level.OVERWORLD, 1, 0);
        queue.queueObservation(ordinary, "chunk-load");
        queue.queuePayloadChange(blockEntity, "block-entity-update", false);
        assertEquals(Set.of(blockEntity), queue.finalPayloadChanges(Level.OVERWORLD));
        assertFalse(queue.captureLoaded(blockEntity, new Object(),
                () -> true, () -> fail("Block entity updates cannot authorize placeholder terrain")));
        assertEquals(ordinary, queue.pollPending().key());
        assertFalse(queue.hasWork());
    }

    @Test
    void droppedFreshPacketHintStillForcesANewSampleAtUnload() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 1);
        var blocker = new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0);
        var refreshed = new DownloadCaptureQueue.Key(Level.OVERWORLD, 1, 0);
        var loadedChunk = new Object();
        assertTrue(queue.captureLoaded(refreshed, loadedChunk, () -> false, CompoundTag::new));
        assertTrue(queue.queueObservation(blocker, "chunk-load"));
        queue.queueObservation(refreshed, "full-chunk-packet");
        assertEquals(1, queue.metrics().droppedHints());
        assertTrue(queue.captureLoaded(refreshed, loadedChunk, () -> false, CompoundTag::new));
        assertEquals(blocker, queue.pollPending().key());
    }

    @Test
    void failedCaptureRetainsLastBlockRemovalUntilTheFinalSample() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 1);
        var key = new DownloadCaptureQueue.Key(Level.OVERWORLD, 4, 2);
        var pos = new ChunkPos(4, 2);
        var chunk = new Object();
        queue.queuePayloadChange(key, "block-prediction-ack", true);
        queue.retainFailedChange(queue.pollPending());
        assertFalse(queue.hasWork(), "A failed payload must not keep ordinary tick capture busy");
        assertTrue(queue.hasPendingChunkWork(Level.OVERWORLD, pos));
        assertEquals(Set.of(key), queue.finalPayloadChanges(Level.OVERWORLD));
        assertTrue(queue.captureLoaded(key, chunk, () -> true, CompoundTag::new));
        assertTrue(ChunkListener.getDimension(Level.OVERWORLD).get(pos).authoritativeEmpty());
        assertFalse(queue.hasPendingChunkWork(Level.OVERWORLD, pos));
    }

    @Test
    void newObservationRetainsPendingEmptyAuthorityAfterAnIncrementalFailure() {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 1);
        var key = new DownloadCaptureQueue.Key(Level.OVERWORLD, 4, 2);
        queue.queuePayloadChange(key, "block-update", true);
        queue.retainFailedChange(queue.pollPending());
        queue.queueObservation(key, "light-update-no-base");
        assertTrue(queue.pollPending().allowEmpty());
    }

    @Test
    void finishingCapturePreservesAppliedLightEvenBeforeTheCoalescingDeadline() {
        ChunkPos pos = new ChunkPos(0, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        long previous = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, previous)), false);
        assertEquals(0, ChunkListener.getDirtyCount());
        byte[] light = new byte[2048];
        light[0] = 7;
        ChunkListener.applyLightUpdate(Level.OVERWORLD, pos, lightUpdate(light[0]));
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

    private static LightingUpdate lightUpdate(byte level) {
        BitSet mask = new BitSet();
        mask.set(0);
        byte[] light = new byte[2048];
        light[0] = level;
        return new LightingUpdate(
                0, 1, mask, new BitSet(), List.of(light), new BitSet(), new BitSet(), List.of());
    }
}
