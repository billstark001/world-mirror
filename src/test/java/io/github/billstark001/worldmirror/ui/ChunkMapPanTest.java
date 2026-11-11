package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMapPanTest {
    private static final int LEFT = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int CELL = ChunkMapView.CELL_SIZE_DEFAULT;

    @Test
    void leftDragPansAndStopsOnRelease() {
        var pan = new ChunkMapPan();
        pan.centerOn(30, -15);
        pan.begin(mouse(100, 100, LEFT));

        var moved = mouse(100 - 2 * CELL, 100 + 3 * CELL, LEFT);
        assertTrue(pan.move(moved, CELL));
        assertCenter(pan, 32, -18);
        assertTrue(pan.release(moved));

        assertFalse(pan.move(mouse(100, 100, LEFT), CELL));
        assertCenter(pan, 32, -18);
    }

    @ParameterizedTest
    @ValueSource(ints = {InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOUSE_BUTTON_MIDDLE})
    void otherButtonsDoNotPanOrCancelALeftDrag(int button) {
        var pan = new ChunkMapPan();
        var moved = mouse(100 - CELL, 100, button);
        pan.begin(mouse(100, 100, button));
        assertFalse(pan.move(moved, CELL));
        assertCenter(pan, 0, 0);

        pan.begin(mouse(100, 100, LEFT));
        pan.begin(mouse(50, 50, button));
        assertFalse(pan.move(moved, CELL));
        assertCenter(pan, 0, 0);
        assertFalse(pan.release(moved));

        assertTrue(pan.move(mouse(100 - CELL, 100, LEFT), CELL));
        assertCenter(pan, 1, 0);
    }

    private static MouseButtonEvent mouse(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    private static void assertCenter(ChunkMapPan pan, double x, double z) {
        assertEquals(x, pan.x(), "map center X");
        assertEquals(z, pan.z(), "map center Z");
    }
}
