package io.github.billstark001.worldmirror.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkListenerRevisionTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearCache() {
        ChunkListener.clear();
        ContainerTracker.clear();
        EntityTracker.clear();
    }

    @Test
    void invalidationOnlyRemovesTheExactDurableRevision() {
        ChunkPos pos = new ChunkPos(7, -2);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        assertEquals(1, ChunkListener.getDirtyCount());
        long first = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();

        ChunkListener.markChunkDirty(Level.OVERWORLD, pos);
        assertEquals(1, ChunkListener.getDirtyCount());
        long second = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();

        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, first)), true);
        assertNotNull(ChunkListener.getDimension(Level.OVERWORLD).get(pos));
        assertEquals(second, ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision());
        assertEquals(1, ChunkListener.getDirtyCount());

        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, second)), true);
        assertNull(ChunkListener.getDimension(Level.OVERWORLD).get(pos));
        assertEquals(0, ChunkListener.getDirtyCount());
    }

    @Test
    void dirtySnapshotExcludesDurableCacheEntries() {
        ChunkPos pos = new ChunkPos(-4, 9);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        long revision = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
        assertEquals(1, ChunkListener.snapshotDirtyReferences().get(Level.OVERWORLD).size());

        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, revision)), false);
        assertEquals(0, ChunkListener.getDirtyCount());
        assertEquals(0, ChunkListener.snapshotDirtyReferences().size());

        ChunkListener.markChunkDirty(Level.OVERWORLD, pos);
        assertEquals(1, ChunkListener.getDirtyCount());
        assertEquals(1, ChunkListener.snapshotDirtyReferences().get(Level.OVERWORLD).size());
    }

    @Test
    void dimensionQueriesCannotBypassRevisionBookkeeping() {
        ChunkPos pos = new ChunkPos(0, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());

        assertThrows(UnsupportedOperationException.class, () -> ChunkListener.getDimension(Level.OVERWORLD).clear());
        assertEquals(1, ChunkListener.getDirtyCount());
        assertNotNull(ChunkListener.getDimension(Level.OVERWORLD).get(pos));
    }

    @Test
    void dirtySnapshotKeepsItsLightingAfterLiveCacheIsCleared() {
        ChunkPos pos = new ChunkPos(2, 3);
        byte[] light = new byte[2048];
        java.util.Arrays.fill(light, (byte) 9);
        CompoundTag chunk = new CompoundTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        section.putByteArray("BlockLight", light);
        ListTag sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);

        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, chunk);
        ChunkListener.DirtySnapshot snapshot = ChunkListener.snapshotDirtyState();
        ChunkListener.CapturedChunk captured = snapshot.chunks().get(Level.OVERWORLD).get(pos);

        ChunkListener.clear();

        CompoundTag materialized = snapshot.materialize(Level.OVERWORLD, pos, captured).nbt();
        CompoundTag materializedSection = materialized.getListOrEmpty("sections")
                .getCompound(0).orElseThrow();
        assertArrayEquals(light, materializedSection.getByteArray("BlockLight").orElseThrow());
    }

    @Test
    void dirtySnapshotDoesNotObserveLaterLightPacketsOrFullCaptures() {
        ChunkPos pos = new ChunkPos(2, 3);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        BitSet updatedSection = new BitSet();
        updatedSection.set(0);
        byte[] initial = new byte[2048];
        java.util.Arrays.fill(initial, (byte) 3);
        ChunkListener.applyLightUpdate(Level.OVERWORLD, pos, new LightingUpdate(
                0, 1, updatedSection, new BitSet(), List.of(initial),
                new BitSet(), new BitSet(), List.of()));
        ChunkListener.DirtySnapshot snapshot = ChunkListener.snapshotDirtyState();
        ChunkListener.CapturedChunk captured = snapshot.chunks().get(Level.OVERWORLD).get(pos);

        byte[] later = new byte[2048];
        java.util.Arrays.fill(later, (byte) 8);
        ChunkListener.applyLightUpdate(Level.OVERWORLD, pos, new LightingUpdate(
                0, 1, updatedSection, new BitSet(), List.of(later),
                new BitSet(), new BitSet(), List.of()));
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());

        CompoundTag materialized = snapshot.materialize(Level.OVERWORLD, pos, captured).nbt();
        CompoundTag section = materialized.getListOrEmpty("sections").getCompound(0).orElseThrow();
        assertArrayEquals(initial, section.getByteArray("BlockLight").orElseThrow());
    }

    @Test
    void anOlderExportCannotInvalidateLightWaitingForItsCoalescingDeadline() {
        ChunkPos pos = new ChunkPos(2, 3);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        long exportedRevision = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
        BitSet mask = new BitSet();
        mask.set(0);
        byte[] light = new byte[2048];
        light[0] = 11;
        assertTrue(ChunkListener.applyLightUpdate(Level.OVERWORLD, pos, new LightingUpdate(0, 1,
                mask, new BitSet(), List.of(light), new BitSet(), new BitSet(), List.of())));

        // No markChunkDirty call yet: the packet's coalescing window is still open.
        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, exportedRevision)), true);

        assertEquals(1, ChunkListener.getDirtyCount());
        var snapshot = ChunkListener.snapshotDirtyState();
        var captured = snapshot.chunks().get(Level.OVERWORLD).get(pos);
        assertTrue(captured.revision() > exportedRevision);
        assertArrayEquals(light, snapshot.materialize(Level.OVERWORLD, pos, captured).nbt()
                .getListOrEmpty("sections").getCompound(0).orElseThrow()
                .getByteArray("BlockLight").orElseThrow());
    }

    @Test
    void confirmedEmptyTerrainDoesNotRestoreBlockEntitiesFromThePreviousCapture() {
        ChunkPos pos = new ChunkPos(0, 0);
        CompoundTag old = new CompoundTag();
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 1);
        chest.putInt("y", 64);
        chest.putInt("z", 2);
        ListTag blockEntities = new ListTag();
        blockEntities.add(chest);
        old.put("block_entities", blockEntities);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, old);

        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag(), true);
        ChunkListener.markChunkDirty(Level.OVERWORLD, pos);
        var snapshot = ChunkListener.snapshotDirtyState();
        var captured = snapshot.chunks().get(Level.OVERWORLD).get(pos);

        assertTrue(captured.authoritativeEmpty());
        assertTrue(snapshot.materialize(Level.OVERWORLD, pos, captured).nbt()
                .getListOrEmpty("block_entities").isEmpty());
    }
}
