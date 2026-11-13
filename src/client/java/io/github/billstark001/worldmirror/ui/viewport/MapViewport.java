package io.github.billstark001.worldmirror.ui.viewport;

/** Tracks the model point at the center of a scrollable map. */
public final class MapViewport {
    private record Drag(double pointerX, double pointerY, double centerX, double centerY) {}

    private double x;
    private double y;
    private Drag drag;

    public double x() {
        return x;
    }
    public double y() {
        return y;
    }

    public void centerOn(double x, double y) {
        this.x = x;
        this.y = y;
        drag = null;
    }

    public double toScreenX(double modelX, double screenCenter, double scale) {
        return screenCenter + (modelX - x) * scale;
    }

    public double toScreenY(double modelY, double screenCenter, double scale) {
        return screenCenter + (modelY - y) * scale;
    }

    public double toModelX(double screenX, double screenCenter, double scale) {
        return x + (screenX - screenCenter) / scale;
    }

    public double toModelY(double screenY, double screenCenter, double scale) {
        return y + (screenY - screenCenter) / scale;
    }

    public void zoomAt(double pointerX, double pointerY, double screenX, double screenY, double previousScale,
            double scale) {
        double anchorX = toModelX(pointerX, screenX, previousScale);
        double anchorY = toModelY(pointerY, screenY, previousScale);
        centerOn(anchorX - (pointerX - screenX) / scale, anchorY - (pointerY - screenY) / scale);
    }

    public void beginDrag(double pointerX, double pointerY) {
        drag = new Drag(pointerX, pointerY, x, y);
    }

    public boolean dragTo(double pointerX, double pointerY, double scale) {
        if (drag == null)
            return false;
        x = drag.centerX() + (drag.pointerX() - pointerX) / scale;
        y = drag.centerY() + (drag.pointerY() - pointerY) / scale;
        return true;
    }

    public boolean endDrag() {
        if (drag == null)
            return false;
        drag = null;
        return true;
    }
}
