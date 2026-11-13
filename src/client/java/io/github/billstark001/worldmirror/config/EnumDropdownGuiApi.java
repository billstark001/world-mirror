package io.github.billstark001.worldmirror.config;

import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.network.chat.Component;

import java.util.function.Function;

#if MC_VERSION >= 260200
/** Minecraft 26.2 draw-call adapter for the shared read-only dropdown top cell. */
#elif MC_VERSION >= 260102
/** Minecraft 26.1.2 draw-call adapter for the shared read-only dropdown top cell. */
#else
/** Minecraft 1.21.11 draw-call adapter for the shared read-only dropdown top cell. */
#endif
final class EnumDropdownGuiApi {
    private EnumDropdownGuiApi() {}

    static <T> DropdownBoxEntry.SelectionTopCellElement<T> createTopCell(
            T value, Function<T, Component> label) {
        return new ReadOnlyDropdownTopCell<>(value, label) {
            @Override
#if MC_VERSION >= 260102
            public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x,
                    int y, int width, int height, float delta) {
                RenderModel model = layout(x, y, width, height);
                var font = Minecraft.getInstance().font;
                graphics.text(font, model.text(), model.textX(), model.y(), model.color(), false);
                graphics.text(font, model.arrow(), model.arrowX(), model.y(),
#else
            public void render(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width,
                    int height, float delta) {
                RenderModel model = layout(x, y, width, height);
                var font = Minecraft.getInstance().font;
                graphics.drawString(font, model.text(), model.textX(), model.y(), model.color(), false);
                graphics.drawString(font, model.arrow(), model.arrowX(), model.y(),
#endif
                        model.color(), false);
            }
        };
    }
}
