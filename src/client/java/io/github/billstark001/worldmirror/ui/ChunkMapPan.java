package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;

/** Map center and the pointer anchor for a primary-button drag. */
final class ChunkMapPan {
    private record Anchor(double mouseX, double mouseY, double centerX, double centerZ) { }

    private double x;
    private double z;
    private Anchor anchor;

    double x() { return x; }
    double z() { return z; }

    static boolean isPrimary(MouseButtonEvent event) {
        return event.button() == InputConstants.MOUSE_BUTTON_LEFT;
    }

    void centerOn(double x, double z) {
        this.x = x;
        this.z = z;
        anchor = null;
    }

    void begin(MouseButtonEvent event) {
        if (isPrimary(event)) anchor = new Anchor(event.x(), event.y(), x, z);
    }

    boolean move(MouseButtonEvent event, int cellSize) {
        if (anchor == null || !isPrimary(event)) return false;
        x = anchor.centerX() + (anchor.mouseX() - event.x()) / cellSize;
        z = anchor.centerZ() + (anchor.mouseY() - event.y()) / cellSize;
        return true;
    }

    boolean release(MouseButtonEvent event) {
        if (anchor == null || !isPrimary(event)) return false;
        anchor = null;
        return true;
    }
}
