package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.ui.WorldMirrorTabbedScreen.SelectionOption;
import net.minecraft.network.chat.Component;

import java.util.List;

/** A text row, action, or dropdown in a routing editor page. */
record RoutingRow(Component label, Component detail, Runnable action, List<SelectionOption> options,
        RoutingRow right, boolean text) {
    static final int BUTTON_HEIGHT = 22;
    static final int TEXT_HEIGHT = 13;

    RoutingRow(Component label, Component detail, Runnable action) {
        this(label, detail, action, List.of(), null, false);
    }

    int height() {
        return text ? TEXT_HEIGHT : BUTTON_HEIGHT;
    }

    static RoutingRow row(Component label, Runnable action) {
        return new RoutingRow(label, null, action);
    }

    static RoutingRow info(Component label) {
        return new RoutingRow(label, null, null, List.of(), null, true);
    }

    static RoutingRow selection(Component label, List<SelectionOption> options) {
        return new RoutingRow(
                WorldMirrorTabbedScreen.selectionLabel(label), label, null, options, null, false);
    }

    static RoutingRow pair(Component left, Runnable action, Component right, Runnable other) {
        return new RoutingRow(left, null, action, List.of(), row(right, other), false);
    }
}
