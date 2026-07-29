package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorMapping;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/** Native Minecraft status screen with no external GUI dependency. */
@Environment(EnvType.CLIENT)
public class StatusScreen extends Screen {
    protected StatusScreen() { super(Component.translatable("screen.worldmirror.title")); }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        addRenderableWidget(Button.builder(Component.translatable(DownloadManager.isActive() ? "screen.worldmirror.status.stopDownload" : "screen.worldmirror.status.startDownload"), button -> { DownloadManager.toggle(Minecraft.getInstance()); refresh(); }).bounds(left, 92, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.status.exportNow"), button -> { DownloadManager.exportNow(Minecraft.getInstance()); refresh(); }).bounds(left + 154, 92, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.status.clearData"), button -> { DownloadManager.clearAll(Minecraft.getInstance()); refresh(); }).bounds(left, 116, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.status.exportNearby"), button -> ExportNearbyScreen.open(this)).bounds(left + 154, 116, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.status.openChunkMap"), button -> ChunkMapScreen.open()).bounds(left, 140, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.status.openSettings"), button -> ClientScreens.set(AutoConfigClient.getConfigScreen(ModConfig.class, this).get())).bounds(left + 154, 140, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.status.overwriteAll"), button -> { clearConflicts(true); refresh(); }).bounds(left, 164, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worldmirror.status.discardAll"), button -> { clearConflicts(false); refresh(); }).bounds(left + 154, 164, 146, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        Minecraft client = Minecraft.getInstance();
        String sourceId = WorldMetadata.detectSourceId(client);
        int x = width / 2 - 150;
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFF);
        graphics.text(font, Component.translatable("screen.worldmirror.status.sourceId").getString() + ": " + sourceId, x, 34, 0xE0E0E0);
        graphics.text(font, Component.translatable("screen.worldmirror.status.mirrorPath").getString() + ": " + MirrorMapping.getInstance().getMirrorFolderName(sourceId), x, 48, 0xE0E0E0);
        graphics.text(font, Component.translatable("screen.worldmirror.status.chunks").getString() + ": " + ChunkListener.getTotalCount(), x, 62, 0xE0E0E0);
        graphics.text(font, Component.translatable(DownloadManager.isExportInProgress() ? "screen.worldmirror.status.exportRunning" : "screen.worldmirror.status.exportIdle"), x, 76, 0xE0E0E0);
    }

    private static void clearConflicts(boolean overwrite) { Path output = DownloadManager.getOutputPath(Minecraft.getInstance()); ConflictManager.clearAllConflicts(output, overwrite); }
    protected void refresh() { ClientScreens.set(new StatusClientScreen()); }
    public static void open() { ClientScreens.setLater(new StatusClientScreen()); }
}
