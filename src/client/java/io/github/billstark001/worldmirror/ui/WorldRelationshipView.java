package io.github.billstark001.worldmirror.ui;

import static io.github.billstark001.worldmirror.ui.WorldRelationshipLabels.label;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.ui.WorldRelationshipLayout.Card;
import io.github.billstark001.worldmirror.ui.WorldRelationshipLayout.Edge;
import io.github.billstark001.worldmirror.ui.viewport.MapViewport;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Interactive view of the persisted upstream and downstream world relationships. */
public final class WorldRelationshipView extends AbstractWidget {
    static final int HELP_HEIGHT = 23;
    private static final int PADDING = 7;
    private static final double MIN_ZOOM = 0.18;
    private static final double INITIAL_MIN_ZOOM = 0.48;
    private static final double MAX_ZOOM = 2.2;
    private static final int TEXT = 0xFFF2F5FA;
    private static final int MUTED = 0xFFAAB6C8;
    private static final int SYNC = 0xFF7CBCFF;
    private static final int SNAPSHOT = 0xFFD0A9FF;
    private static final int WARNING = 0xFFF9A96A;
    private static final int SELECTED = 0xFFFFD979;

    private record Bounds(int left, int top, int right, int bottom) {
        boolean contains(double x, double y) {
            return left <= x && x < right && top <= y && y < bottom;
        }

        boolean intersects(Bounds other) {
            return right > other.left && left < other.right && bottom > other.top && top < other.bottom;
        }
    }

    private interface Canvas {
        void fill(int left, int top, int right, int bottom, int color);
        void text(Component text, int x, int y, int color);
    }

    private final MapViewport viewport = new MapViewport();
    private WorldGraphService.RoutingView view;
    private WorldRelationshipLayout.Result layout;
    private Consumer<String> openNode;
    private String selection;
    private double zoom = 1;
    private boolean fitViewport = true;
    private boolean fitAll;
    private double pressedX;
    private double pressedY;
    private boolean doubleClick;
    private boolean dragged;
    private int helpWidth;

    public WorldRelationshipView(int x, int y, int width, int height) {
        super(x, y, width, height, label("worlds"));
    }

    public void update(WorldGraphService.RoutingView view, Function<WorldGraph.Node, String> displayName,
            Consumer<String> openNode) {
        this.openNode = Objects.requireNonNull(openNode);
        if (this.view != null && this.view.graph() == view.graph() && this.view.config().equals(view.config())
                && this.view.untrackedLocalWorlds().equals(view.untrackedLocalWorlds())
                && this.view.orphanedMirrors().equals(view.orphanedMirrors()))
            return;
        this.view = view;
        var local = new ArrayList<>(view.untrackedLocalWorlds());
        local.addAll(view.orphanedMirrors());
        layout = WorldRelationshipLayout.create(view.graph(), local, displayName);
        if (!layout.byKey().containsKey(Objects.requireNonNullElse(selection, "")))
            selection = null;
        if (fitViewport)
            fitViewport();
    }

    @Override
    public void setRectangle(int width, int height, int x, int y) {
        super.setRectangle(width, height, x, y);
        if (fitViewport)
            fitViewport();
    }

    public void fit() {
        fitViewport = true;
        fitAll = true;
        fitViewport();
    }

    void setHelpWidth(int width) {
        helpWidth = width;
    }

    private void fitViewport() {
        if (layout == null || layout.cards().isEmpty())
            return;
        double contentHeight =
                fitAll || layout.trackedHeight() == 0 ? layout.height() : layout.trackedHeight();
        double fitWidth = (width - 2.0 * PADDING) / layout.width();
        double fitHeight = (mapBounds().bottom() - getY() - 2.0 * PADDING) / contentHeight;
        zoom = Math.clamp(Math.min(fitWidth, fitHeight), fitAll ? MIN_ZOOM : INITIAL_MIN_ZOOM, 1.0);
        viewport.centerOn(layout.width() / 2, contentHeight / 2);
    }

    public boolean canOpenSelected() {
        Card card = selected();
        return card != null
                && (card.node() != null
                        || card.untracked() != null && view.orphanedMirrors().contains(card.untracked()));
    }

    public void openSelected() {
        if (canOpenSelected())
            openNode.accept(selection);
    }

    private Card selected() {
        return layout == null || selection == null ? null : layout.byKey().get(selection);
    }

    @Override
#if MC_VERSION >= 260102
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
        var font = Minecraft.getInstance().font;
        drawWidget(graphics::fill,
                (text, x, y, color)
                        -> graphics.text(font, text, x, y, color),
#else
    protected void renderWidget(GuiGraphics graphics, int mx, int my, float delta) {
        var font = Minecraft.getInstance().font;
        drawWidget(graphics::fill,
                (text, x, y, color)
                        -> graphics.drawString(font, text, x, y, color),
#endif
                graphics::enableScissor, graphics::disableScissor);
        Card hover = cardAt(mx, my);
        if (hover != null)
            graphics.setTooltipForNextFrame(font,
                    WorldRelationshipLabels.tooltip(view, layout, hover)
                            .stream()
                            .map(Component::getVisualOrderText)
                            .toList(),
                    mx, my);
    }

    @FunctionalInterface
    private interface Fill {
        void fill(int left, int top, int right, int bottom, int color);
    }

    @FunctionalInterface
    private interface Text {
        void text(Component text, int x, int y, int color);
    }

    @FunctionalInterface
    private interface Clip {
        void enable(int left, int top, int right, int bottom);
    }

    private void drawWidget(Fill fill, Text text, Clip clip, Runnable restoreClip) {
        Canvas canvas = new Canvas() {
            @Override
            public void fill(int left, int top, int right, int bottom, int color) {
                fill.fill(left, top, right, bottom, color);
            }
            @Override
            public void text(Component component, int x, int y, int color) {
                text.text(component, x, y, color);
            }
        };
        Bounds map = mapBounds();
        canvas.fill(getX(), getY(), getRight(), getBottom(), 0xBE172131);
        if (map.bottom() <= map.top() || width <= 0)
            return;
        canvas.fill(map.left(), map.top(), map.right(), map.top() + 1, SYNC);
        clip.enable(map.left(), map.top() + 1, map.right(), map.bottom());
        try {
            if (layout == null || layout.cards().isEmpty()) {
                line(canvas, label("empty"), map.left() + PADDING, map.top() + PADDING, MUTED, width - 14);
            } else {
                for (Edge edge : layout.edges()) drawEdge(canvas, edge);
                for (Card card : layout.cards()) {
                    Bounds bounds = cardBounds(card);
                    if (map.intersects(bounds))
                        drawCard(canvas, card, bounds);
                }
            }
        } finally {
            restoreClip.run();
        }
        canvas.fill(getX(), map.bottom(), getRight(), getBottom(), 0xE8202A3A);
        canvas.fill(getX(), map.bottom(), getRight(), map.bottom() + 1, 0xFF35425B);
        drawDetails(canvas, selected(), map.bottom() + 3);
    }

    private void drawEdge(Canvas canvas, Edge edge) {
        Bounds parent = cardBounds(edge.parent());
        Bounds child = cardBounds(edge.child());
        int fromX = parent.right();
        int toX = child.left();
        int fromY = sy(edge.parent().y());
        int toY = sy(edge.child().y());
        int color = edge.cycle() || edge.parent().warning() == WorldRelationshipLayout.Warning.MISSING
                ? WARNING
                : edge.child().node().role() == WorldGraph.Role.SNAPSHOT ? SNAPSHOT
                                                                         : SYNC;
        if (fromX >= toX) {
            int above = Math.min(parent.top(), child.top()) - 7;
            int px = sx(edge.parent().x());
            int cx = sx(edge.child().x());
            if (px == cx) {
                horizontal(canvas, fromX, fromX + 8, fromY, color);
                vertical(canvas, fromX + 8, above, fromY, color);
                horizontal(canvas, px, fromX + 8, above, color);
            } else {
                vertical(canvas, px, above, parent.top(), color);
                horizontal(canvas, px, cx, above, color);
            }
            vertical(canvas, cx, above, child.top(), color);
            return;
        }
        int elbow = (fromX + toX) / 2;
        horizontal(canvas, fromX, elbow, fromY, color);
        vertical(canvas, elbow, fromY, toY, color);
        horizontal(canvas, elbow, toX, toY, color);
        if (zoom >= 0.45) {
            vertical(canvas, toX - 3, toY - 1, toY + 1, color);
            vertical(canvas, toX - 4, toY - 2, toY + 2, color);
        }
    }

    private static void horizontal(Canvas canvas, int from, int to, int y, int color) {
        canvas.fill(Math.min(from, to), y, Math.max(from, to) + 1, y + 1, color);
    }

    private static void vertical(Canvas canvas, int x, int from, int to, int color) {
        canvas.fill(x, Math.min(from, to), x + 1, Math.max(from, to) + 1, color);
    }

    private void drawCard(Canvas canvas, Card card, Bounds bounds) {
        int accent = accent(card);
        boolean selected = card.key().equals(selection);
        int left = bounds.left();
        int top = bounds.top();
        int right = bounds.right();
        int bottom = bounds.bottom();
        canvas.fill(left, top, right, bottom, selected ? SELECTED : accent);
        canvas.fill(left + 2, top + 1, right - 1, bottom - 1, selected ? 0xFF393548 : 0xFF25334B);
        if (zoom >= 0.36) {
            int textY = bottom - top >= 24 ? top + 5 : top + (bottom - top - 9) / 2;
            line(canvas, Component.literal(card.name()), left + 5, textY, TEXT, right - left - 9);
            if (bottom - top >= 24) {
                line(canvas, WorldRelationshipLabels.category(view, card), left + 5, textY + 11, accent,
                        right - left - 9);
            }
        }
    }

    private static int accent(Card card) {
        if (card.warning() != WorldRelationshipLayout.Warning.NONE)
            return WARNING;
        var node = card.node();
        if (node == null || node.kind() == WorldGraph.Kind.LOCAL)
            return 0xFF89D7BA;
        if (node.role() == WorldGraph.Role.SNAPSHOT)
            return SNAPSHOT;
        return node.kind() == WorldGraph.Kind.REMOTE ? 0xFF79D8E7 : SYNC;
    }

    private void drawDetails(Canvas canvas, Card card, int y) {
        int x = getX() + PADDING;
        int available = helpWidth;
        if (card == null) {
            line(canvas, label("graphHelp"), x, y + 5, MUTED, available);
            return;
        }
        var details = WorldRelationshipLabels.tooltip(view, layout, card);
        line(canvas, details.getFirst().copy().append(" · ").append(details.get(1)), x, y, TEXT, available);
        if (details.size() > 2)
            line(canvas, details.get(2), x, y + 9, MUTED, available);
    }

    private static void line(Canvas canvas, Component text, int x, int y, int color, int available) {
        if (available <= 0)
            return;
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= available)
            canvas.text(text, x, y, color);
        else
            canvas.text(Component.literal(font.plainSubstrByWidth(
                                                  text.getString(), Math.max(0, available - font.width("…")))
                                + "…"),
                    x, y, color);
    }

    private Bounds mapBounds() {
        return new Bounds(getX(), getY(), getRight(), Math.max(getY(), getBottom() - HELP_HEIGHT));
    }

    @Override
    public boolean isMouseOver(double x, double y) {
        return mapBounds().contains(x, y) && super.isMouseOver(x, y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return isMouseOver(event.x(), event.y()) && super.mouseClicked(event, doubleClick);
    }

    private double centerX() {
        return getX() + width / 2.0;
    }
    private double centerY() {
        return getY() + (mapBounds().bottom() - getY()) / 2.0;
    }

    private int sx(double x) {
        return (int) Math.round(viewport.toScreenX(x, centerX(), zoom));
    }
    private int sy(double y) {
        return (int) Math.round(viewport.toScreenY(y, centerY(), zoom));
    }

    private Bounds cardBounds(Card card) {
        return new Bounds(sx(card.x() - WorldRelationshipLayout.CARD_WIDTH / 2.0),
                sy(card.y() - WorldRelationshipLayout.CARD_HEIGHT / 2.0),
                sx(card.x() + WorldRelationshipLayout.CARD_WIDTH / 2.0),
                sy(card.y() + WorldRelationshipLayout.CARD_HEIGHT / 2.0));
    }

    private Card cardAt(double x, double y) {
        if (layout == null || !mapBounds().contains(x, y))
            return null;
        double modelX = viewport.toModelX(x, centerX(), zoom);
        double modelY = viewport.toModelY(y, centerY(), zoom);
        return layout.cards().stream().filter(card -> card.contains(modelX, modelY)).findFirst().orElse(null);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!mapBounds().contains(event.x(), event.y()))
            return;
        pressedX = event.x();
        pressedY = event.y();
        this.doubleClick = doubleClick;
        dragged = false;
        viewport.beginDrag(pressedX, pressedY);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (dragged || Math.hypot(event.x() - pressedX, event.y() - pressedY) > 3.0) {
            dragged = viewport.dragTo(event.x(), event.y(), zoom);
            if (dragged)
                fitViewport = false;
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (!viewport.endDrag() || dragged)
            return;
        Card card = cardAt(event.x(), event.y());
        if (card == null) {
            selection = null;
            return;
        }
        boolean alreadySelected = card.key().equals(selection);
        selection = card.key();
        if (doubleClick && alreadySelected)
            openSelected();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!active || !visible || !mapBounds().contains(x, y) || vertical == 0)
            return false;
        setZoom(x, y, zoom * Math.pow(1.18, vertical));
        return true;
    }

    private void setZoom(double x, double y, double proposed) {
        double next = Math.clamp(proposed, MIN_ZOOM, MAX_ZOOM);
        if (next != zoom) {
            viewport.zoomAt(x, y, centerX(), centerY(), zoom, next);
            zoom = next;
            fitViewport = false;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !isFocused() || layout == null || layout.cards().isEmpty())
            return false;
        switch (event.key()) {
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> openSelected();
            case InputConstants.KEY_HOME -> fit();
            case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD ->
                setZoom(centerX(), centerY(), zoom * 1.18);
            case InputConstants.KEY_MINUS -> setZoom(centerX(), centerY(), zoom / 1.18);
            case InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP,
                    InputConstants.KEY_DOWN ->
                selectNeighbor(event.key());
            default -> {
                return false;
            }
        }
        return true;
    }

    private void selectNeighbor(int direction) {
        Card card = selected();
        if (card == null) {
            focus(layout.cards().getFirst());
            return;
        }
        if (direction == InputConstants.KEY_LEFT) {
            if (card.node() != null && card.node().parentId() != null)
                focus(layout.byKey().get(card.node().parentId()));
        } else if (direction == InputConstants.KEY_RIGHT) {
            var direct = layout.children().get(card.key());
            if (direct != null && !direct.isEmpty())
                focus(direct.getFirst());
        } else {
            int index = layout.cards().indexOf(card) + (direction == InputConstants.KEY_UP ? -1 : 1);
            focus(layout.cards().get(Math.clamp(index, 0, layout.cards().size() - 1)));
        }
    }

    private void focus(Card card) {
        selection = card.key();
        var bounds = cardBounds(card);
        var map = mapBounds();
        if (!map.contains(bounds.left(), bounds.top()) || !map.contains(bounds.right(), bounds.bottom())) {
            viewport.centerOn(card.x(), card.y());
            fitViewport = false;
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        Card card = selected();
        output.add(NarratedElementType.TITLE,
                card == null ? getMessage()
                             : Component.literal(card.name())
                                       .append(". ")
                                       .append(WorldRelationshipLabels.category(view, card)));
        output.add(NarratedElementType.USAGE, label("graphHelp"));
    }
}
