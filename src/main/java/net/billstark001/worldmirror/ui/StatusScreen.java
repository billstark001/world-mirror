package net.billstark001.worldmirror.ui;

import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription;
import io.github.cottonmc.cotton.gui.widget.WButton;
import io.github.cottonmc.cotton.gui.widget.WLabel;
import io.github.cottonmc.cotton.gui.widget.WPlainPanel;
import io.github.cottonmc.cotton.gui.widget.data.HorizontalAlignment;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.billstark001.worldmirror.config.ModConfig;
import net.billstark001.worldmirror.conflict.ConflictManager;
import net.billstark001.worldmirror.download.DownloadManager;
import net.billstark001.worldmirror.download.MirrorMapping;
import net.billstark001.worldmirror.download.WorldMetadata;
import net.billstark001.worldmirror.core.ChunkListener;
import net.billstark001.worldmirror.xaero.XaeroWorldMapOverlayStatus;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import java.nio.file.Path;

/**
 * In-game status screen for World Mirror.
 * <p>
 * Built with LibGUI ({@link LightweightGuiDescription} + {@link StatusClientScreen}).
 * Split into three tabs:
 * <ul>
 *   <li><b>Status</b> — source info, cache statistics, download/export state, action buttons.</li>
 *   <li><b>Settings</b> — per-world save-location and conflict-strategy overrides, plus a
 *       shortcut to the global settings screen.</li>
 *   <li><b>Conflicts</b> — bulk Overwrite / Discard conflict resolution plus the Chunk Map.</li>
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
        Minecraft client = Minecraft.getInstance();

        // ── Gather data (all on game thread, safe) ────────────────────────────
        String sourceType  = WorldMetadata.detectSourceType(client);
        String sourceId    = WorldMetadata.detectSourceId(client);
        String folderName  = MirrorMapping.getInstance().getMirrorFolderName(sourceId);
        int    totalChunks = ChunkListener.getTotalCount();
        boolean isActive   = DownloadManager.isActive();
        boolean isExport   = DownloadManager.isExportInProgress();

        Path worldFolder = DownloadManager.getOutputPath(client);
        int conflictCount = ConflictManager.countAllConflicts(worldFolder);

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
            Component tabLabel = (activeTab == tabIndex)
                    ? Component.literal("§l").append(Component.translatable(tabKeys[i]))
                    : Component.translatable(tabKeys[i]);
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
            case 2 -> y = buildConflictsTab(root, y, conflictCount, worldFolder);
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
        root.add(new WLabel(Component.translatable("screen.worldmirror.status.xaeroOverlay")
                        .append(": ")
                        .append(XaeroWorldMapOverlayStatus.statusComponent())),
                MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP;

        WLabel dlStatus = new WLabel(
                Component.translatable(isActive ? "screen.worldmirror.status.downloadActive"
                                           : "screen.worldmirror.status.downloadInactive"));
        root.add(dlStatus, MARGIN, y, HALF_W, LBL_H);
        WLabel expStatus = new WLabel(
                Component.translatable(isExport ? "screen.worldmirror.status.exportRunning"
                                           : "screen.worldmirror.status.exportIdle"));
        root.add(expStatus, MARGIN + HALF_W + 4, y, HALF_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        // Action buttons
        WButton toggleBtn = new WButton(
                Component.translatable(isActive ? "screen.worldmirror.status.stopDownload"
                                           : "screen.worldmirror.status.startDownload"));
        toggleBtn.setOnClick(() -> {
            DownloadManager.toggle(Minecraft.getInstance());
            open();
        });
        root.add(toggleBtn, MARGIN, y, HALF_W, BTN_H);

        WButton exportBtn = new WButton(
                Component.translatable("screen.worldmirror.status.exportNow"));
        exportBtn.setOnClick(() -> {
            DownloadManager.exportNow(Minecraft.getInstance());
            open();
        });
        root.add(exportBtn, MARGIN + HALF_W + 4, y, HALF_W, BTN_H);
        y += BTN_H + 4;

        WButton clearBtn = new WButton(
                Component.translatable("screen.worldmirror.status.clearData"));
        clearBtn.setOnClick(() -> {
            DownloadManager.clearAll(Minecraft.getInstance());
            open();
        });
        root.add(clearBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton exportNearbyBtn = new WButton(
                Component.translatable("screen.worldmirror.status.exportNearby"));
        exportNearbyBtn.setOnClick(() -> ExportNearbyScreen.open(new StatusClientScreen()));
        root.add(exportNearbyBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton closeBtn = new WButton(Component.translatable("gui.done"));
        closeBtn.setOnClick(() -> ClientScreens.set(null));
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

        WLabel header = new WLabel(Component.translatable("screen.worldmirror.tab.settingsHeader"));
        header.setHorizontalAlignment(HorizontalAlignment.CENTER);
        root.add(header, MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        WButton saveLocBtn = new WButton(
                Component.translatable("screen.worldmirror.status.saveLoc")
                        .append(": ")
                        .append(Component.translatable(
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
                Component.translatable("screen.worldmirror.status.conflictStrategy")
                        .append(": ")
                        .append(Component.translatable(
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
                Component.translatable("screen.worldmirror.status.openSettings"));
        settingsBtn.setOnClick(() -> {
            ClientScreens.set(
                    AutoConfigClient.getConfigScreen(ModConfig.class, new StatusClientScreen()).get()
            );
        });
        root.add(settingsBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton closeBtn = new WButton(Component.translatable("gui.done"));
        closeBtn.setOnClick(() -> ClientScreens.set(null));
        root.add(closeBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + MARGIN;

        return y;
    }

    /** Builds the Conflicts tab and returns the new y after all widgets. */
    private int buildConflictsTab(WPlainPanel root, int y,
                                  int conflictCount, Path worldFolder) {

        WLabel header = new WLabel(Component.translatable("screen.worldmirror.tab.conflictsHeader"));
        header.setHorizontalAlignment(HorizontalAlignment.CENTER);
        root.add(header, MARGIN, y, INNER_W, LBL_H);
        y += ROW_GAP + SECT_GAP;

        if (conflictCount == 0) {
            root.add(new WLabel(Component.translatable("screen.worldmirror.status.noConflicts")),
                    MARGIN, y, INNER_W, LBL_H);
            y += ROW_GAP + SECT_GAP;
        } else {
            root.add(rowLabel("screen.worldmirror.status.conflicts",
                    String.valueOf(conflictCount)), MARGIN, y, INNER_W, LBL_H);
            y += ROW_GAP;

            WButton overwriteAllBtn = new WButton(
                    Component.translatable("screen.worldmirror.status.overwriteAll"));
            overwriteAllBtn.setOnClick(() -> {
                ConflictManager.clearAllConflicts(worldFolder, true);
                open();
            });
            root.add(overwriteAllBtn, MARGIN, y, HALF_W, BTN_H);

            WButton discardAllBtn = new WButton(
                    Component.translatable("screen.worldmirror.status.discardAll"));
            discardAllBtn.setOnClick(() -> {
                ConflictManager.clearAllConflicts(worldFolder, false);
                open();
            });
            root.add(discardAllBtn, MARGIN + HALF_W + 4, y, HALF_W, BTN_H);
            y += BTN_H + SECT_GAP;
        }

        // Chunk map button
        WButton chunkMapBtn = new WButton(
                Component.translatable("screen.worldmirror.status.openChunkMap"));
        chunkMapBtn.setOnClick(() -> ChunkMapScreen.open());
        root.add(chunkMapBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + 4;

        WButton closeBtn = new WButton(Component.translatable("gui.done"));
        closeBtn.setOnClick(() -> ClientScreens.set(null));
        root.add(closeBtn, MARGIN, y, INNER_W, BTN_H);
        y += BTN_H + MARGIN;

        return y;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Opens a fresh status screen on the game thread. */
    public static void open() {
        ClientScreens.setLater(new StatusClientScreen());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a "{label}: {value}" label widget. */
    private static WLabel rowLabel(String translationKey, String value) {
        return new WLabel(
                Component.translatable(translationKey).append(": " + value));
    }

    /** Reads WorldMetadata from disk to find the last sync time. */
    private static String computeLastSyncStr(Minecraft client,
                                             String sourceId, String sourceType) {
        try {
            Path worldFolder = DownloadManager.getOutputPath(client);
            WorldMetadata meta = WorldMetadata.loadOrCreate(worldFolder, sourceId, sourceType);
            if (meta.lastSyncTime == 0) {
                return Component.translatable("screen.worldmirror.status.lastSyncNever").getString();
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
