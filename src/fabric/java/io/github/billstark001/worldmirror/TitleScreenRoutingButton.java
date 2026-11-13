package io.github.billstark001.worldmirror;

import io.github.billstark001.worldmirror.ui.WorldRoutingScreen;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import java.util.Comparator;

final class TitleScreenRoutingButton {
    private static final int SIZE = 20;
    private static final int GAP = 4;
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("worldmirror", "world_mirror");

    private TitleScreenRoutingButton() {}

    static void add(TitleScreen screen, int width) {
#if MC_VERSION >= 260102
        var widgets = Screens.getWidgets(screen);
#else
        var widgets = Screens.getButtons(screen);
#endif
        // The language button shares the icon row on newer versions and the options row on older versions.
        var language =
                widgets.stream()
                        .filter(widget
                                -> widget.getMessage().getContents() instanceof TranslatableContents text
                                        && "options.language".equals(text.getKey()))
                        .findFirst()
                        .orElse(null);
        if (language == null)
            return;
        int y = language.getY();
        var rowWidgets = widgets.stream().filter(widget
                -> widget instanceof Button && widget.visible && widget.getY() == y
                        && widget.getHeight() == SIZE);
#if MC_VERSION >= 260200
        var row = rowWidgets.sorted(Comparator.comparingInt(AbstractWidget::getX)).toList();
        int totalWidth = SIZE + row.stream().mapToInt(widget -> widget.getWidth() + GAP).sum();
        if (totalWidth > width)
            return;
        int x = (width - totalWidth) / 2;
        for (var widget : row) {
            widget.setX(x);
            x += widget.getWidth() + GAP;
        }
#else
        int x = rowWidgets.mapToInt(widget -> widget.getX() + widget.getWidth()).max().orElse(0) + GAP;
        if (x + SIZE > width)
            return;
#endif
        var message = Component.translatable("screen.worldmirror.routing.title");
        var button = SpriteIconButton.builder(message, ignored -> WorldRoutingScreen.open(screen), true)
                             .size(SIZE, SIZE)
                             .sprite(ICON, 16, 16)
                             .build();
        button.setTooltip(Tooltip.create(message));
        button.setPosition(x, y);
        widgets.add(button);
    }
}
