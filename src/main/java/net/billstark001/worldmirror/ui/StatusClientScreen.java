package net.billstark001.worldmirror.ui;

import io.github.cottonmc.cotton.gui.client.CottonClientScreen;
import net.billstark001.worldmirror.download.DownloadManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

/**
 * Wraps {@link CottonClientScreen} around {@link StatusScreen} and
 * automatically refreshes the screen whenever the export-in-progress or
 * download-active state changes.
 *
 * <p>The refresh is driven by {@link #tick()}, which runs on every client tick
 * while the screen is open.  When a state change is detected, the old screen
 * is replaced with a brand-new {@link StatusClientScreen} instance so that
 * all labels reflect the current state without requiring individual widget
 * bindings.
 */
@Environment(EnvType.CLIENT)
public class StatusClientScreen extends CottonClientScreen {

    private boolean lastExportState;
    private boolean lastActiveState;

    public StatusClientScreen() {
        super(new StatusScreen());
        this.lastExportState = DownloadManager.isExportInProgress();
        this.lastActiveState = DownloadManager.isActive();
    }

    @Override
    public void tick() {
        super.tick();
        boolean currentExport = DownloadManager.isExportInProgress();
        boolean currentActive = DownloadManager.isActive();
        if (currentExport != lastExportState || currentActive != lastActiveState) {
            lastExportState = currentExport;
            lastActiveState = currentActive;
            MinecraftClient.getInstance().setScreen(new StatusClientScreen());
        }
    }
}
