package io.github.billstark001.worldmirror.ui;

import net.minecraft.client.Minecraft;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

#if MC_VERSION >= 260200
/** Minecraft 26.2 rendering and screen-installation adapter. */
#elif MC_VERSION >= 260102
/** Minecraft 26.1 rendering and screen-installation adapter. */
#else
/** Minecraft 1.21.11 rendering and screen-installation adapter. */
#endif
abstract class StatusScreenApi extends Screen {
    protected interface Canvas {
        void text(Component text, int x, int y, int color);
        void centered(Component text, int x, int y, int color);
    }

    protected StatusScreenApi(Component title) {
        super(title);
    }

    protected abstract void renderContent(Canvas canvas);

    @Override
#if MC_VERSION >= 260102
    public final void extractRenderState(GuiGraphicsExtractor graphics,
                                         int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
#else
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
#endif
        renderContent(new Canvas() {
            @Override
            public void text(Component text, int x, int y, int color) {
#if MC_VERSION >= 260102
                graphics.text(font, text, x, y, color);
#else
                graphics.drawString(font, text, x, y, color);
#endif
            }

            @Override
            public void centered(Component text, int x, int y, int color) {
#if MC_VERSION >= 260102
                graphics.centeredText(font, text, x, y, color);
#else
                graphics.drawCenteredString(font, text, x, y, color);
#endif
            }
        });
    }

    protected static void showScreen(Screen screen) {
#if MC_VERSION >= 260200
        Minecraft.getInstance().gui.setScreen(screen);
#else
        Minecraft.getInstance().setScreen(screen);
#endif
    }
}
