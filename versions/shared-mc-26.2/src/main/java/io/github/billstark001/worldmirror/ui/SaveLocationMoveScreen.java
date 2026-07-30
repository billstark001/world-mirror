package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.DownloadManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Confirms a safe, complete move of an existing mirror directory. */
@Environment(EnvType.CLIENT)
public final class SaveLocationMoveScreen extends Screen {
    private final Screen parent;
    private final ModConfig.SaveLocation targetLocation;
    private Component failure;

    public SaveLocationMoveScreen(Screen parent, ModConfig.SaveLocation targetLocation) {
        super(Component.translatable("screen.worldmirror.move.title"));
        this.parent = parent;
        this.targetLocation = targetLocation;
    }

    @Override
    protected void init() {
        int x = width / 2;
        int y = height / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.move.confirm"), button -> move())
                .bounds(x - 102, y + 22, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> Minecraft.getInstance().gui.setScreen(parent))
                .bounds(x + 4, y + 22, 98, 20).build());
    }

    private void move() {
        DownloadManager.MirrorMoveResult result = DownloadManager.moveMirrorWorld(Minecraft.getInstance(), targetLocation);
        if (result.success()) Minecraft.getInstance().gui.setScreen(new StatusClientScreen());
        else failure = Component.translatable("screen.worldmirror.move.failure." + result.failureCode());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = width / 2;
        int y = height / 2;
        graphics.centeredText(font, title, x, y - 50, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("screen.worldmirror.move.message",
                Component.translatable("config.worldmirror.saveLoc." + targetLocation.name().toLowerCase())), x, y - 28, 0xFFE0E0E0);
        graphics.centeredText(font, Component.translatable("screen.worldmirror.move.warning"), x, y - 14, 0xFFFFAA55);
        if (failure != null) graphics.centeredText(font, failure, x, y + 2, 0xFFFF5555);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
