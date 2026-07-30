package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** One-per-join acknowledgement when the local save is a World Mirror save. */
@Environment(EnvType.CLIENT)
public final class MirrorWorldWarningScreen extends Screen {
    private MirrorWorldWarningScreen() {
        super(Component.translatable("screen.worldmirror.mirrorWarning.title"));
    }

    public static void openIfCurrentWorldIsMirror() {
        if (MirrorWorldContext.current().isMirror()) {
            Minecraft.getInstance().setScreen(new MirrorWorldWarningScreen());
        }
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 50, height / 2 + 34, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);
        MirrorWorldContext.Snapshot snapshot = MirrorWorldContext.current();
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 36, 0xFFFFFF55);
        graphics.drawCenteredString(font, Component.translatable("screen.worldmirror.mirrorWarning.body"),
                width / 2, height / 2 - 10, 0xFFE0E0E0);
        graphics.drawCenteredString(font, Component.translatable("screen.worldmirror.mirrorWarning.state." + snapshot.state().name().toLowerCase()),
                width / 2, height / 2 + 6, 0xFFFFAA55);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
