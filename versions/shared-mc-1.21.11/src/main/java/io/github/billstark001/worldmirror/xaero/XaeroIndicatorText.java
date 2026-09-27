package io.github.billstark001.worldmirror.xaero;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.network.chat.Component;

/** Draws the badge through the native 1.21.11 font pipeline after Xaero's screen. */
public final class XaeroIndicatorText {
    private static boolean installed;

    private XaeroIndicatorText() { }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!XaeroIndicator.isXaeroMap(screen)) return;
            ScreenEvents.afterRender(screen).register((map, graphics, mouseX, mouseY, delta) ->
                    XaeroIndicator.render(client, new XaeroIndicator.Canvas() {
                        @Override
                        public void fill(int x1, int y1, int x2, int y2, int color) {
                            graphics.fill(x1, y1, x2, y2, color);
                        }

                        @Override
                        public void textCentered(Component value, int centerX,
                                                 int boxY, int boxHeight, int color) {
                            int x = centerX - client.font.width(value) / 2;
                            int y = boxY + (boxHeight - client.font.lineHeight) / 2;
                            graphics.drawString(client.font, value, x, y, color);
                        }
                    }, map.width));
        });
    }
}
