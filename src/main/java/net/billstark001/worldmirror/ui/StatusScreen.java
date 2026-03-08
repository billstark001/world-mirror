package net.billstark001.worldmirror.ui;

import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription;
import io.github.cottonmc.cotton.gui.widget.WButton;
import io.github.cottonmc.cotton.gui.widget.WLabel;
import io.github.cottonmc.cotton.gui.widget.WPlainPanel;
import io.github.cottonmc.cotton.gui.widget.data.HorizontalAlignment;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.billstark001.worldmirror.config.ModConfig;
import net.billstark001.worldmirror.conflict.ManualResolver;
import net.billstark001.worldmirror.download.DownloadManager;
import net.billstark001.worldmirror.download.MirrorMapping;
import net.billstark001.worldmirror.download.WorldMetadata;
import net.billstark001.worldmirror.core.ChunkListener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import java.nio.file.Path;
import java.util.List;

/**
 * In-game status screen for World Mirror.
 * <p>
 * Built with LibGUI ({@link LightweightGuiDescription} + {@link StatusClientScreen}).
 * Split into three tabs:
 * <ul>
 *   <li><b>Status</b> — source info, cache statistics, download/export state, action buttons.</li>
 *   <li><b>Settings</b> — per-world save-location and conflict-strategy overrides, plus a
 *       shortcut to the global settings screen.</li>
 *   <li><b>Conflicts</b> — pending manual-conflict queue with bulk Overwrite/Ignore actions.</li>
 * </ul>
 *
 * <p>The active tab is remembered across reopens via {@link #activeTab}.
 * {@link StatusClientScreen} wraps this description and polls the export/download
 * state on every tick, automatically reopening the screen when either state changes.
 */
@Environment(EnvType.CLIENT)
public class StatusScreen extends LightweightGuiDescription {

    // ── Layout constants ──────────────────────────────────────────────────────

    private static final int W        = 300;   // root panel width
    private static final int MARGIN   = 8;     // outer margin
    private static final int INNER_W  = W - MARGIN * 2;       // 284
    private static final int HALF_W   = (INNER_W - 4) / 2;    // 140
    private static final int BTN_H    = 20;
    private static final int LBL_H    = 10;
    private static final int ROW_GAP  = 13;
    private static final int SECT_GAP = 6;

    // Three equal-width tab buttons with a 4px gap between them
    private static final int TAB_GAP = 4;
    private static final int TAB_W   = (INNER_W - TAB_GAP * 2) / 3;  // 92

    // Fixed window height regardless of active tab (avoids jarring resize)
    private static final int FIXED_H = 232;

    // ── Tab state (persisted across reopens) ─────────────────────────────────

    /** 0 = Status, 1 = Settings, 2 = Conflicts */
    private static int activeTab = 0;

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

        String lastSyncStr = computeLastSyncStr(client, sourceId, sourceType);

        ModConfig.SaveLocation effectiveSaveLoc = resolveEffectiveSaveLoc(sourceId);
        ModConfig.ConflictStrategy effectiveStrategy = resolveEffectiveStrategy(sourceId);

        // ── Root panel ────────────────────────────────────────────────────────
        WPlainPanel root = new WPlainPanel();
        setRootPanel(root);

        int y = MARGIN;

        // ── Tab buttons ───────────────────────────────────────────────────────
        String[] tabKeys = {
            "screen.worldmirror.tab.status",
            "screen.worldmirror.tab.settings",
            "screen.worldmirror.tab.conflicts"
        };
        for (int i = 0; i < 3; i++) {
            final int tabIndex = i;
            // Highlight the active tab with bold prefix
            Text tabLabel = (activeTab == tabIndex)
                    ? Text.literal("§l").append(Text.translatable(tabKeys[i]))
                    : Text.translatable(tabKeys[i]);
            WButton tabBtn = new WButton(tabLabel);
            tabBtn.setOnClick(() -> { activeTab = tabIndex; open(); });
            root.add(tabBtn, MARGIN + tabIndex * (TAB_W + TAB_GAP), y, TAB_W, BTN_H);
        }
        y += BTN_H + 4;

        // ── Tab content ───────────────────────────────────────────────────────
        switch (activeTab) {
            case 0 -> y = buildStatusTab(root, y, sourceType, sourceId, folderName,
                                         totalChunks, lastSyncStr, isActive, isExport);
            case 1 -> y = buildSettingsTab(root, y, sourceId, effectiveSaveLoc,
                                           effectiveStrategy);
            case 2 -> y = buildConflictsTab(root, y, pendingConflicts);
        }

        root.setSize(W, FIXED_H);
        root.validate(this);
    }

    // ── Tab builders ──────────────────────────────────────────────────────────

    /** Builds the Status tab and returns the new y after all widgets. */
    private int buildStatusTab(WPlainPanel root, int y,
                               String sourceType, String sourceId, String folderName,
                               int totalChunks, String lastSyncStr,
                               boolean isActive, boolean isExport) {

        // Source info
        root.add(rowLabel("screen.worldmirror.status.sourceType", sourceType),
                MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;
        root.add(rowLabel("screen.worldmirror.status.sourceId", sourceId),
                MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;
        root.add(rowLabel("screen.worldmirror.status.mirrorPath", folderName),
                MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        // Stats
        root.add(rowLabel("screen.worldmirror.status.chunks", String.valueOf(totalChunks)),
                MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;
        root.add(rowLabel("screen.worldmirror.status.lastSync", lastSyncStr),
                MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;

        WLabel dlStatus = new WLabel(
                Text.translatable(isActive ? "screen.worldmirror.status.downloadActive"
                                           : "screen.worldmirror.status.downloadInactive"));
        root.add(dlStatus, MARGIN, y, HALF_W, LBL_H);
        WLabel expStatus = new WLabel(
                Text.translatable(isExport ? "screen.worldmirror.status.exportRunning"
                                           : "screen.worldmirror.status.exportIdle"));
        root.add(expStatus, MARGIN + HALF_W + 4, y, HALF_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        // Action buttons
        WButton toggleBtn = new WButton(
                Text.translatable(isActive ? "screen.worldmirror.status.stopDownload"
                                           : "screen.worldmirror.status.startDownload"));
        toggleBtn.setOnClick(() -> {
            DownloadManager.toggle(MinecraftClient.getInstance());
            open();
        });
        root.add(toggleBtn, MARGIN, y, HALF_W, BTN_H);

        WButton exportBtn = new WButton(
                Text.translatable("screen.worldmirror.status.exportNow"));
        exportBtn.setOnClick(() -> {
            DownloadManager.exportNow(MinecraftClient.getInstance());
            open();
        });
        root.add(exportBtn, MARGIN + HALF_W + 4, y, HALF_W, BTN_H);
        y += BTN_H + 4;

        WButton clearBtn = new WButton(
                Text.translatable("screen.worldmirror.status.clearData"));
        clearBtn.setOnClick(() -> {
            DownloadManager.clearAll(MinecraftClient.getInstance());
            open();
        });
        root.add(clearBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton closeBtn = new WButton(Text.translatable("gui.done"));
        closeBtn.setOnClick(() -> MinecraftClient.getInstance().setScreen(null));
        root.add(closeBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + MARGIN;

        return y;
    }

    /** Builds the Settings tab and returns the new y after all widgets. */
    private int buildSettingsTab(WPlainPanel root, int y,
                                 String sourceId,
                                 ModConfig.SaveLocation effectiveSaveLoc,
                                 ModConfig.ConflictStrategy effectiveStrategy) {

        final String sid = sourceId;

        WLabel header = new WLabel(Text.translatable("screen.worldmirror.tab.settingsHeader"));
        header.setHorizontalAlignment(HorizontalAlignment.CENTER);
        root.add(header, MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        WButton saveLocBtn = new WButton(
                Text.translatable("screen.worldmirror.status.saveLoc")
                        .append(": ")
                        .append(Text.translatable(
                                "config.worldmirror.saveLoc."
                                        + effectiveSaveLoc.name().toLowerCase())));
        saveLocBtn.setOnClick(() -> {
            ModConfig.SaveLocation[] vals = ModConfig.SaveLocation.values();
            ModConfig.SaveLocation next = vals[(effectiveSaveLoc.ordinal() + 1) % vals.length];
            MirrorMapping.getInstance().setPerWorldSaveLocation(sid, next.name());
            open();
        });
        root.add(saveLocBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton strategyBtn = new WButton(
                Text.translatable("screen.worldmirror.status.conflictStrategy")
                        .append(": ")
                        .append(Text.translatable(
                                "config.worldmirror.conflictStrategy."
                                        + effectiveStrategy.name().toLowerCase())));
        strategyBtn.setOnClick(() -> {
            ModConfig.ConflictStrategy[] vals = ModConfig.ConflictStrategy.values();
            ModConfig.ConflictStrategy next = vals[(effectiveStrategy.ordinal() + 1) % vals.length];
            MirrorMapping.getInstance().setPerWorldConflictStrategy(sid, next.name());
            open();
        });
        root.add(strategyBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + SECT_GAP;

        WButton settingsBtn = new WButton(
                Text.translatable("screen.worldmirror.status.openSettings"));
        settingsBtn.setOnClick(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.setScreen(
                    AutoConfigClient.getConfigScreen(ModConfig.class, new StatusClientScreen()).get()
            );
        });
        root.add(settingsBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton closeBtn = new WButton(Text.translatable("gui.done"));
        closeBtn.setOnClick(() -> MinecraftClient.getInstance().setScreen(null));
        root.add(closeBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + MARGIN;

        return y;
    }

    /** Builds the Conflicts tab and returns the new y after all widgets. */
    private int buildConflictsTab(WPlainPanel root, int y, List<ChunkPos> pendingConflicts) {

        WLabel header = new WLabel(Text.translatable("screen.worldmirror.tab.conflictsHeader"));
        header.setHorizontalAlignment(HorizontalAlignment.CENTER);
        root.add(header, MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        if (pendingConflicts.isEmpty()) {
            root.add(new WLabel(Text.translatable("screen.worldmirror.status.noConflicts")),
                    MARGIN, y, INNER_W, LBL_H);
            y += ROW_GAP + SECT_GAP;
        } else {
            root.add(rowLabel("screen.worldmirror.status.conflicts",
                    String.valueOf(pendingConflicts.size())), MARGIN, y, INNER_W, LBL_H);
            y += ROW_GAP;

            WButton overwriteAllBtn = new WButton(
                    Text.translatable("screen.worldmirror.status.overwriteAll"));
            overwriteAllBtn.setOnClick(() -> {
                ManualResolver.clearPendingConflicts();
                DownloadManager.exportNow(MinecraftClient.getInstance());
                open();
            });
            root.add(overwriteAllBtn, MARGIN, y, HALF_W, BTN_H);

            WButton ignoreAllBtn = new WButton(
                    Text.translatable("screen.worldmirror.status.ignoreAll"));
            ignoreAllBtn.setOnClick(() -> {
                ManualResolver.clearPendingConflicts();
                open();
            });
            root.add(ignoreAllBtn, MARGIN + HALF_W + 4, y, HALF_W, BTN_H);
            y += BTN_H + SECT_GAP;
        }

        WButton closeBtn = new WButton(Text.translatable("gui.done"));
        closeBtn.setOnClick(() -> MinecraftClient.getInstance().setScreen(null));
        root.add(closeBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + MARGIN;

        return y;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Opens a fresh status screen on the game thread. */
    public static void open() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.setScreen(new StatusClientScreen()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
                return Text.translatable("screen.worldmirror.status.lastSyncNever").getString();
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

    private static ModConfig.SaveLocation resolveEffectiveSaveLoc(String sourceId) {
        String perWorld = MirrorMapping.getInstance().getPerWorldSaveLocation(sourceId);
        if (perWorld != null) {
            try { return ModConfig.SaveLocation.valueOf(perWorld); }
            catch (IllegalArgumentException ignored) {}
        }
        return ModConfig.get().defaultSaveLocation;
    }

    private static ModConfig.ConflictStrategy resolveEffectiveStrategy(String sourceId) {
        String perWorld = MirrorMapping.getInstance().getPerWorldConflictStrategy(sourceId);
        if (perWorld != null) {
            try { return ModConfig.ConflictStrategy.valueOf(perWorld); }
            catch (IllegalArgumentException ignored) {}
        }
        return ModConfig.get().defaultConflictStrategy;
    }
}

