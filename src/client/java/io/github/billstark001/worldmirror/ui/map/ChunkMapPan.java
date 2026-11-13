package io.github.billstark001.worldmirror.ui.map;

import io.github.billstark001.worldmirror.ui.input.MouseInput;
import io.github.billstark001.worldmirror.ui.viewport.MapViewport;
import net.minecraft.client.input.MouseButtonEvent;

/** Map center and the pointer anchor for a primary-button drag. */
final class ChunkMapPan {
    private final MapViewport viewport = new MapViewport();

    double x() {
        return viewport.x();
    }
    double z() {
        return viewport.y();
    }

    void centerOn(double x, double z) {
        viewport.centerOn(x, z);
    }

    void begin(MouseButtonEvent event) {
        if (MouseInput.isPrimary(event))
            viewport.beginDrag(event.x(), event.y());
    }

    boolean move(MouseButtonEvent event, int cellSize) {
        return MouseInput.isPrimary(event) && viewport.dragTo(event.x(), event.y(), cellSize);
    }

    boolean release(MouseButtonEvent event) {
        return MouseInput.isPrimary(event) && viewport.endDrag();
    }
}
