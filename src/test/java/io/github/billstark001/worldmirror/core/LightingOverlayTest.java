package io.github.billstark001.worldmirror.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

class LightingOverlayTest {
    @Test
    void emptyMaskOverwritesKnownDataButUnknownLayerDoesNot() {
        CompoundTag chunk = chunkWithSection(3, bytes((byte) 7), bytes((byte) 11));
        LightingOverlay overlay = new LightingOverlay();
        overlay.absorbAndStrip(chunk);

        BitSet emptyBlock = new BitSet();
        emptyBlock.set(3);
        overlay.applyUpdate(new LightingUpdate(
                0, 5, new BitSet(), emptyBlock, List.of(), new BitSet(), new BitSet(), List.of()));

        CompoundTag section = onlySection(overlay.snapshot().materialize(chunk));
        assertArrayEquals(new byte[2048], section.getByteArray("BlockLight").orElseThrow());
        assertArrayEquals(bytes((byte) 11), section.getByteArray("SkyLight").orElseThrow());
    }

    @Test
    void updateMaskConsumesArraysInSectionOrderAndCreatesLightOnlySections() {
        LightingOverlay overlay = new LightingOverlay();
        BitSet blockUpdates = new BitSet();
        blockUpdates.set(1);
        blockUpdates.set(3);

        overlay.applyUpdate(new LightingUpdate(0, 5, blockUpdates, new BitSet(),
                List.of(bytes((byte) 1), bytes((byte) 3)), new BitSet(), new BitSet(), List.of()));

        CompoundTag materialized = overlay.snapshot().materialize(new CompoundTag());
        assertTrue(materialized.contains("sections"));
        ListTag sections = materialized.getListOrEmpty("sections");
        assertTrue(sections.size() == 2);
        assertEquals(1, sections.getCompound(0).orElseThrow().getByteOr("Y", (byte) -1));
        assertArrayEquals(bytes((byte) 1), sectionAt(sections, 1).getByteArray("BlockLight").orElseThrow());
        assertArrayEquals(bytes((byte) 3), sectionAt(sections, 3).getByteArray("BlockLight").orElseThrow());
        assertFalse(sectionAt(sections, 1).contains("SkyLight"));
    }

    @Test
    void materializedLightCannotChangeAnotherExportFromTheSameSnapshot() {
        var overlay = new LightingOverlay();
        BitSet updated = new BitSet();
        updated.set(0);
        byte[] light = bytes((byte) 9);
        var update = new LightingUpdate(
                -1, 1, updated, new BitSet(), List.of(light), new BitSet(), new BitSet(), List.of());
        overlay.applyUpdate(update);
        var snapshot = overlay.snapshot();

        light[0] = 1;
        update.blockUpdates().getFirst()[0] = 2;
        onlySection(snapshot.materialize(new CompoundTag())).getByteArray("BlockLight").orElseThrow()[0] = 3;

        var next = onlySection(snapshot.materialize(new CompoundTag()));
        assertArrayEquals(bytes((byte) 9), next.getByteArray("BlockLight").orElseThrow());
    }

    private static CompoundTag chunkWithSection(int y, byte[] blockLight, byte[] skyLight) {
        CompoundTag chunk = new CompoundTag();
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) y);
        section.putByteArray("BlockLight", blockLight);
        section.putByteArray("SkyLight", skyLight);
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }

    private static CompoundTag onlySection(CompoundTag chunk) {
        return chunk.getListOrEmpty("sections").getCompound(0).orElseThrow();
    }

    private static CompoundTag sectionAt(ListTag sections, int y) {
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i).orElseThrow();
            if (section.getByteOr("Y", (byte) 0) == (byte) y) {
                return section;
            }
        }
        throw new AssertionError("No section at Y=" + y);
    }

    private static byte[] bytes(byte value) {
        byte[] bytes = new byte[2048];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
