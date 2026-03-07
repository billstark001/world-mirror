package net.billstark001.worlddownloader.ui;

import io.github.cottonmc.cotton.gui.client.CottonClientScreen;
import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription;
import io.github.cottonmc.cotton.gui.widget.WButton;
import io.github.cottonmc.cotton.gui.widget.WLabel;
import io.github.cottonmc.cotton.gui.widget.WPlainPanel;
import io.github.cottonmc.cotton.gui.widget.data.HorizontalAlignment;
import net.billstark001.worlddownloader.config.ConfigScreen;
import net.billstark001.worlddownloader.config.ModConfig;
import net.billstark001.worlddownloader.conflict.ManualResolver;
import net.billstark001.worlddownloader.download.DownloadManager;
import net.billstark001.worlddownloader.download.MirrorMapping;
import net.billstark001.worlddownloader.download.WorldMetadata;
import net.billstark001.worlddownloader.core.ChunkListener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import java.nio.file.Path;
import java.util.List;

/**
 * In-game status screen for World Downloader (roadmap §6).
 * <p>
 * Built with LibGUI ({@link LightweightGuiDescription} + {@link CottonClientScreen}).
 * Shows:
 * <ul>
 *   <li>Source world/server info</li>
 *   <li>Cached-chunk statistics and last-sync time</li>
 *   <li>Download-active / export-in-progress status</li>
 *   <li>Toggle download, Export now, Clear data action buttons</li>
 *   <li>Manual-conflict queue with Overwrite-All / Ignore-All resolution</li>
 *   <li>Per-world save-location and conflict-strategy overrides</li>
 *   <li>Shortcut to the global settings screen</li>
 * </ul>
 *
 * <p>The screen is reopened (recreated) after every action so that all labels
 * reflect the current state without requiring individual widget updates.
 */
@Environment(EnvType.CLIENT)
public class StatusScreen extends LightweightGuiDescription {

    // ── Layout constants ──────────────────────────────────────────────────────

    private static final int W       = 300;  // root panel width
    private static final int MARGIN  = 8;    // left/right margin
    private static final int INNER_W = W - MARGIN * 2;
    private static final int HALF_W  = (INNER_W - 4) / 2;
    private static final int BTN_H   = 20;
    private static final int LBL_H   = 10;
    private static final int ROW_GAP = 13;   // label row height
    private static final int SECT_GAP = 6;  // extra space between sections

    // ── Construction ─────────────────────────────────────────────────────────

    public StatusScreen() {
        MinecraftClient client = MinecraftClient.getInstance();

        // ── Gather data (all on game thread, safe) ────────────────────────────
        String sourceType  = WorldMetadata.detectSourceType(client);
        String sourceId    = WorldMetadata.detectSourceId(client);
        String folderName  = MirrorMapping.getInstance().getMirrorFolderName(sourceId);
        int    totalChunks = ChunkListener.getTotalCount();
        boolean isActive   = DownloadManager.isActive();
        boolean isExport   = DownloadManager.isExportInProgress();
        List<ChunkPos> pendingConflicts = ManualResolver.getPendingConflicts();

        // Last sync time from metadata (may read from disk; acceptable at screen-open time)
        String lastSyncStr = computeLastSyncStr(client, sourceId, sourceType);

        // Per-world settings (resolve effective values)
        ModConfig.SaveLocation effectiveSaveLoc = resolveEffectiveSaveLoc(sourceId);
        ModConfig.ConflictStrategy effectiveStrategy = resolveEffectiveStrategy(sourceId);

        // ── Build widget tree ─────────────────────────────────────────────────
        WPlainPanel root = new WPlainPanel();
        setRootPanel(root);

        int y = MARGIN;

        // Title
        WLabel title = new WLabel(Text.translatable("screen.worlddownloader.status.title"));
        title.setHorizontalAlignment(HorizontalAlignment.CENTER);
        root.add(title, MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        // ── Source info ───────────────────────────────────────────────────────
        root.add(rowLabel("screen.worlddownloader.status.sourceType", sourceType), MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;
        root.add(rowLabel("screen.worlddownloader.status.sourceId", sourceId), MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;
        root.add(rowLabel("screen.worlddownloader.status.mirrorPath", folderName), MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        // ── Statistics ────────────────────────────────────────────────────────
        root.add(rowLabel("screen.worlddownloader.status.chunks", String.valueOf(totalChunks)), MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;
        root.add(rowLabel("screen.worlddownloader.status.lastSync", lastSyncStr), MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;

        // Download status and export status on the same line
        WLabel dlStatus = new WLabel(
                Text.translatable(isActive ? "screen.worlddownloader.status.downloadActive"
                                           : "screen.worlddownloader.status.downloadInactive"));
        root.add(dlStatus, MARGIN, y, HALF_W, LBL_H);

        WLabel expStatus = new WLabel(
                Text.translatable(isExport ? "screen.worlddownloader.status.exportRunning"
                                           : "screen.worlddownloader.status.exportIdle"));
        root.add(expStatus, MARGIN + HALF_W + 4, y, HALF_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        // ── Action buttons ────────────────────────────────────────────────────
        WButton toggleBtn = new WButton(
                Text.translatable(isActive ? "screen.worlddownloader.status.stopDownload"
                                           : "screen.worlddownloader.status.startDownload"));
        toggleBtn.setOnClick(() -> {
            DownloadManager.toggle(MinecraftClient.getInstance());
            reopen();
        });
        root.add(toggleBtn, MARGIN, y, HALF_W, BTN_H);

        WButton exportBtn = new WButton(Text.translatable("screen.worlddownloader.status.exportNow"));
        exportBtn.setOnClick(() -> {
            DownloadManager.exportNow(MinecraftClient.getInstance());
            reopen();
        });
        root.add(exportBtn, MARGIN + HALF_W + 4, y, HALF_W, BTN_H);
        y += BTN_H + 4;

        WButton clearBtn = new WButton(Text.translatable("screen.worlddownloader.status.clearData"));
        clearBtn.setOnClick(() -> {
            DownloadManager.clearAll(MinecraftClient.getInstance());
            reopen();
        });
        root.add(clearBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + SECT_GAP;

        // ── Conflict queue ────────────────────────────────────────────────────
        if (pendingConflicts.isEmpty()) {
            root.add(
                    new WLabel(Text.translatable("screen.worlddownloader.status.noConflicts")),
                    MARGIN, y, INNER_W, LBL_H);
            y += ROW_GAP + SECT_GAP;
        } else {
            root.add(rowLabel("screen.worlddownloader.status.conflicts",
                    String.valueOf(pendingConflicts.size())), MARGIN, y, INNER_W, LBL_H);
            y += ROW_GAP;

            WButton overwriteAllBtn = new WButton(
                    Text.translatable("screen.worlddownloader.status.overwriteAll"));
            overwriteAllBtn.setOnClick(() -> {
                ManualResolver.clearPendingConflicts();
                DownloadManager.exportNow(MinecraftClient.getInstance());
                reopen();
            });
            root.add(overwriteAllBtn, MARGIN, y, HALF_W, BTN_H);

            WButton ignoreAllBtn = new WButton(
                    Text.translatable("screen.worlddownloader.status.ignoreAll"));
            ignoreAllBtn.setOnClick(() -> {
                ManualResolver.clearPendingConflicts();
                reopen();
            });
            root.add(ignoreAllBtn, MARGIN + HALF_W + 4, y, HALF_W, BTN_H);
            y += BTN_H + SECT_GAP;
        }

        // ── Per-world settings ────────────────────────────────────────────────
        final String sid = sourceId;  // must be effectively-final for lambdas

        WButton saveLocBtn = new WButton(
                Text.translatable("screen.worlddownloader.status.saveLoc")
                        .append(": ")
                        .append(Text.translatable(
                                "config.worlddownloader.saveLoc."
                                        + effectiveSaveLoc.name().toLowerCase())));
        saveLocBtn.setOnClick(() -> {
            ModConfig.SaveLocation[] vals = ModConfig.SaveLocation.values();
            ModConfig.SaveLocation next = vals[(effectiveSaveLoc.ordinal() + 1) % vals.length];
            MirrorMapping.getInstance().setPerWorldSaveLocation(sid, next.name());
            reopen();
        });
        root.add(saveLocBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton strategyBtn = new WButton(
                Text.translatable("screen.worlddownloader.status.conflictStrategy")
                        .append(": ")
                        .append(Text.translatable(
                                "config.worlddownloader.conflictStrategy."
                                        + effectiveStrategy.name().toLowerCase())));
        strategyBtn.setOnClick(() -> {
            ModConfig.ConflictStrategy[] vals = ModConfig.ConflictStrategy.values();
            ModConfig.ConflictStrategy next = vals[(effectiveStrategy.ordinal() + 1) % vals.length];
            MirrorMapping.getInstance().setPerWorldConflictStrategy(sid, next.name());
            reopen();
        });
        root.add(strategyBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + SECT_GAP;

        // ── Bottom buttons ────────────────────────────────────────────────────
        WButton settingsBtn = new WButton(
                Text.translatable("screen.worlddownloader.status.openSettings"));
        settingsBtn.setOnClick(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            // Pass current screen as parent so Settings screen returns here
            mc.setScreen(new ConfigScreen(new CottonClientScreen(new StatusScreen())));
        });
        root.add(settingsBtn, MARGIN, y, HALF_W, BTN_H);

        WButton closeBtn = new WButton(Text.translatable("gui.done"));
        closeBtn.setOnClick(() -> MinecraftClient.getInstance().setScreen(null));
        root.add(closeBtn, MARGIN + HALF_W + 4, y, HALF_W, BTN_H);
        y += BTN_H + MARGIN;

        // Fix total height now that we know it
        root.setSize(W, y);
        root.validate(this);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Opens a fresh status screen on the game thread. */
    public static void open() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.setScreen(new CottonClientScreen(new StatusScreen())));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Reopens the screen (recreates it so all labels reflect current state). */
    private static void reopen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.setScreen(new CottonClientScreen(new StatusScreen())));
    }

    /** Builds a "{label}: {value}" label widget. */
    private static WLabel rowLabel(String translationKey, String value) {
        return new WLabel(
                Text.translatable(translationKey).append(": " + value));
    }

    /** Reads WorldMetadata from disk to find the last sync time. */
    private static String computeLastSyncStr(MinecraftClient client,
                                             String sourceId, String sourceType) {
        try {
            Path worldFolder = DownloadManager.getOutputPath(client);
            WorldMetadata meta = WorldMetadata.loadOrCreate(worldFolder, sourceId, sourceType);
            if (meta.lastSyncTime == 0) {
                return Text.translatable("screen.worlddownloader.status.lastSyncNever").getString();
            }
            long secsAgo = (System.currentTimeMillis() - meta.lastSyncTime) / 1000;
            return formatAge(secsAgo);
        } catch (Exception e) {
            return "?";
        }
    }

    /** Formats an age in seconds as a short human-readable string. */
    private static String formatAge(long secs) {
        if (secs < 0) secs = 0;
        if (secs < 60)   return secs + "s ago";
        if (secs < 3600) return (secs / 60) + "m ago";
        return (secs / 3600) + "h ago";
    }

    /**
     * Returns the effective save location for the given source:
     * per-world override if set, otherwise the global config default.
     */
    private static ModConfig.SaveLocation resolveEffectiveSaveLoc(String sourceId) {
        String perWorld = MirrorMapping.getInstance().getPerWorldSaveLocation(sourceId);
        if (perWorld != null) {
            try { return ModConfig.SaveLocation.valueOf(perWorld); }
            catch (IllegalArgumentException ignored) {}
        }
        return ModConfig.get().defaultSaveLocation;
    }

    /**
     * Returns the effective conflict strategy for the given source:
     * per-world override if set, otherwise the global config default.
     */
    private static ModConfig.ConflictStrategy resolveEffectiveStrategy(String sourceId) {
        String perWorld = MirrorMapping.getInstance().getPerWorldConflictStrategy(sourceId);
        if (perWorld != null) {
            try { return ModConfig.ConflictStrategy.valueOf(perWorld); }
            catch (IllegalArgumentException ignored) {}
        }
        return ModConfig.get().defaultConflictStrategy;
    }
}
