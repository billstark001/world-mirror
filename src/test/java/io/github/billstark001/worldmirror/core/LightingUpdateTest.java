package io.github.billstark001.worldmirror.core;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LightingUpdateTest {
    @Test
    void rejectsMalformedLayersBeforeTheyCanChangeAnOverlay() {
        assertThrows(IllegalArgumentException.class, () -> update(1, mask(0), mask(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> update(1, mask(), mask(), List.of(new byte[2048])));
        assertThrows(IllegalArgumentException.class, () -> update(1, mask(1), mask(), List.of(new byte[2048])));
        assertThrows(IllegalArgumentException.class, () -> update(1, mask(), mask(1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> update(1, mask(0), mask(0), List.of(new byte[2048])));
        assertThrows(IllegalArgumentException.class, () -> update(1, mask(0), mask(), List.of(new byte[10])));
        assertThrows(IllegalArgumentException.class, () -> update(-1, mask(), mask(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LightingUpdate(
                Integer.MAX_VALUE, 2, mask(), mask(), List.of(), mask(), mask(), List.of()));
    }

    @Test
    void inputsAndAccessorResultsCannotMutateTheUpdate() {
        BitSet mask = mask(0);
        byte[] data = new byte[2048];
        data[0] = 5;
        var update = update(1, mask, mask(), List.of(data));
        mask.clear();
        data[0] = 9;
        update.blockUpdateMask().clear();
        update.blockUpdates().getFirst()[0] = 7;

        assertEquals(mask(0), update.blockUpdateMask());
        byte[] expected = new byte[2048];
        expected[0] = 5;
        assertArrayEquals(expected, update.blockUpdates().getFirst());
    }

    private static LightingUpdate update(int count, BitSet updates, BitSet empty, List<byte[]> data) {
        return new LightingUpdate(0, count, updates, empty, data, mask(), mask(), List.of());
    }

    private static BitSet mask(int... sections) {
        BitSet mask = new BitSet();
        for (int section : sections) mask.set(section);
        return mask;
    }
}
