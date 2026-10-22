package io.github.billstark001.worldmirror.download;

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
        ChunkListener.applyLightUpdate(Level.OVERWORLD, pos, new LightingUpdate(0, 1,
                mask, new BitSet(), List.of(light), new BitSet(), new BitSet(), List.of()));
        DownloadCaptureQueue queue = new DownloadCaptureQueue(() -> false, 1);
        for (int hint = 0; hint < 10; hint++) {
            queue.markLightDirty(new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0));
        }
        assertTrue(queue.hasPendingLightUpdates());

        queue.finishLightUpdates();
        queue.clear();

        assertEquals(1, ChunkListener.getDirtyCount());
        assertFalse(queue.hasWork());
        var snapshot = ChunkListener.snapshotDirtyState();
        var captured = snapshot.chunks().get(Level.OVERWORLD).get(pos);
        assertTrue(captured.revision() > previous);
        CompoundTag nbt = snapshot.materialize(Level.OVERWORLD, pos, captured).nbt();
        assertArrayEquals(light, nbt.getListOrEmpty("sections").getCompound(0).orElseThrow()
                .getByteArray("BlockLight").orElseThrow());
    }
}
