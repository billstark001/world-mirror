package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.MirrorMove;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Confirms a safe, complete move of an existing mirror directory. */
@Environment(EnvType.CLIENT)
public final class SaveLocationMoveScreen extends Screen {
    private final Screen parent;
    private final MirrorTarget target;
    private final ModConfig.SaveLocation targetLocation;
    private Component failure;
    private Button confirm;
    private Button cancel;
    private boolean moving;

    public SaveLocationMoveScreen(Screen parent, MirrorTarget target, ModConfig.SaveLocation targetLocation) {
        super(Component.translatable("screen.worldmirror.move.title"));
        this.parent = parent;
        this.target = target;
        this.targetLocation = targetLocation;
    }

    @Override
    protected void init() {
        int x = width / 2;
        int y = height / 2;
        confirm = addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.move.confirm"), button -> move())
                        .bounds(x - 102, y + 22, 98, 20)
                        .build());
        cancel = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                        .bounds(x + 4, y + 22, 98, 20)
                        .build());
        setMoving(moving);
    }

    @Override
    public void onClose() {
        if (!moving)
            StatusScreenApi.showScreen(parent);
    }

    private void setMoving(boolean moving) {
        this.moving = moving;
        confirm.active = cancel.active = !moving;
    }

    private void move() {
        if (moving)
            return;
        setMoving(true);
        failure = null;
        Minecraft client = Minecraft.getInstance();
        MirrorMove.move(client, target, targetLocation, result -> {
            setMoving(false);
            if (ClientDialogs.currentScreen(client) != this)
                return;
            if (result.success())
                onClose();
            else
                failure = Component.translatable("screen.worldmirror.move.failure." + result.failureCode());
        });
    }

    @Override
#if MC_VERSION >= 260102
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = width / 2;
        int y = height / 2;
        graphics.centeredText(font, title, x, y - 50, 0xFFFFFFFF);
        graphics.centeredText(font,
                Component.translatable(
                        "screen.worldmirror.move.message", WorldLabels.location(targetLocation)),
                x, y - 28, 0xFFE0E0E0);
        graphics.centeredText(
                font, Component.translatable("screen.worldmirror.move.warning"), x, y - 14, 0xFFFFAA55);
        if (failure != null)
            graphics.centeredText(font, failure, x, y + 2, 0xFFFF5555);
#else
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        int x = width / 2;
        int y = height / 2;
        graphics.drawCenteredString(font, title, x, y - 50, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable(
                        "screen.worldmirror.move.message", WorldLabels.location(targetLocation)),
                x, y - 28, 0xFFE0E0E0);
        graphics.drawCenteredString(
                font, Component.translatable("screen.worldmirror.move.warning"), x, y - 14, 0xFFFFAA55);
        if (failure != null)
            graphics.drawCenteredString(font, failure, x, y + 2, 0xFFFF5555);
#endif
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
