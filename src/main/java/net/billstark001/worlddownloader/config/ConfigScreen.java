package net.billstark001.worlddownloader.config;

import net.billstark001.worlddownloader.ui.StatusScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game configuration screen for World Downloader.
 * <p>
 * Opened from Mod Menu.  Uses a vanilla-style two-column layout:
 * the setting name is drawn as a text label on the left half and a cycling
 * {@link ButtonWidget} on the right half changes the value on click.
 * Grouped under a "Cache Control" section header for the §3 cache fields.
 */
public class ConfigScreen extends Screen {

    // ── Preset cycling arrays ─────────────────────────────────────────────────

    private static final int[] SYNC_INTERVALS    = {5, 10, 30, 60, 120};
    private static final int[] CHUNK_COUNT_PRESETS = {0, 100, 500, 1000, 5000};
    private static final int[] CACHE_DIST_PRESETS  = {0, 16, 32, 64, 128};
    private static final int[] CACHE_AGE_PRESETS   = {0, 60, 300, 600, 3600};

    // ── Layout constants ─────────────────────────────────────────────────────

    /** Width of the right (value button) column, starting at {@code cx + 5}. */
    private static final int BTN_W   = 150;
    private static final int ROW_H   = 20;
    private static final int ROW_GAP = 24;

    private final Screen parent;
    private final ModConfig config;

    /**
     * (rowY, translationKey) pairs used in {@link #render} to draw setting labels.
     * A {@code null} key marks a section-header row.
     */
    private final List<Object[]> labelRows = new ArrayList<>();

    public ConfigScreen(Screen parent) {
        super(Text.translatable("config.worlddownloader.title"));
        this.parent = parent;
        this.config = ModConfig.get();
    }

    @Override
    protected void init() {
        labelRows.clear();
        int cx = this.width / 2;
        int btnX = cx + 5;
        int y = 40;

        // ── Save Location ─────────────────────────────────────────────────────
        addLabelRow(y, "config.worlddownloader.saveLoc");
        addDrawableChild(ButtonWidget.builder(
                saveLocValueText(),
                btn -> {
                    ModConfig.SaveLocation[] vals = ModConfig.SaveLocation.values();
                    config.defaultSaveLocation =
                            vals[(config.defaultSaveLocation.ordinal() + 1) % vals.length];
                    btn.setMessage(saveLocValueText());
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP;

        // ── Sync Interval ─────────────────────────────────────────────────────
        addLabelRow(y, "config.worlddownloader.syncInterval");
        addDrawableChild(ButtonWidget.builder(
                syncIntervalValueText(),
                btn -> {
                    int idx = nearestIndex(SYNC_INTERVALS, config.syncIntervalSeconds);
                    config.syncIntervalSeconds = SYNC_INTERVALS[(idx + 1) % SYNC_INTERVALS.length];
                    btn.setMessage(syncIntervalValueText());
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP;

        // ── Log Level ─────────────────────────────────────────────────────────
        addLabelRow(y, "config.worlddownloader.logLevel");
        addDrawableChild(ButtonWidget.builder(
                logLevelValueText(),
                btn -> {
                    ModConfig.LogLevel[] vals = ModConfig.LogLevel.values();
                    config.logLevel = vals[(config.logLevel.ordinal() + 1) % vals.length];
                    btn.setMessage(logLevelValueText());
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP;

        // ── Conflict Strategy ─────────────────────────────────────────────────
        addLabelRow(y, "config.worlddownloader.conflictStrategy");
        addDrawableChild(ButtonWidget.builder(
                conflictStrategyValueText(),
                btn -> {
                    ModConfig.ConflictStrategy[] vals = ModConfig.ConflictStrategy.values();
                    config.defaultConflictStrategy =
                            vals[(config.defaultConflictStrategy.ordinal() + 1) % vals.length];
                    btn.setMessage(conflictStrategyValueText());
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP + 6;  // extra gap before cache section

        // ── Cache Control (§3 fields) ──────────────────────────────────────────
        // Section header drawn in render() — we store it separately as a divider
        labelRows.add(new Object[]{y, null});  // null key = section header sentinel
        y += ROW_GAP;

        // Max Cached Chunks
        addLabelRow(y, "config.worlddownloader.maxCachedChunks");
        addDrawableChild(ButtonWidget.builder(
                intPresetValueText(config.maxCachedChunks, "config.worlddownloader.disabled"),
                btn -> {
                    int idx = nearestIndex(CHUNK_COUNT_PRESETS, config.maxCachedChunks);
                    config.maxCachedChunks = CHUNK_COUNT_PRESETS[(idx + 1) % CHUNK_COUNT_PRESETS.length];
                    btn.setMessage(intPresetValueText(config.maxCachedChunks,
                            "config.worlddownloader.disabled"));
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP;

        // Max Cache Distance
        addLabelRow(y, "config.worlddownloader.maxCacheDistanceChunks");
        addDrawableChild(ButtonWidget.builder(
                intPresetValueText(config.maxCacheDistanceChunks, "config.worlddownloader.disabled"),
                btn -> {
                    int idx = nearestIndex(CACHE_DIST_PRESETS, config.maxCacheDistanceChunks);
                    config.maxCacheDistanceChunks =
                            CACHE_DIST_PRESETS[(idx + 1) % CACHE_DIST_PRESETS.length];
                    btn.setMessage(intPresetValueText(config.maxCacheDistanceChunks,
                            "config.worlddownloader.disabled"));
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP;

        // Max Cache Age
        addLabelRow(y, "config.worlddownloader.maxCacheAgeSeconds");
        addDrawableChild(ButtonWidget.builder(
                cacheAgeValueText(),
                btn -> {
                    int idx = nearestIndex(CACHE_AGE_PRESETS, config.maxCacheAgeSeconds);
                    config.maxCacheAgeSeconds = CACHE_AGE_PRESETS[(idx + 1) % CACHE_AGE_PRESETS.length];
                    btn.setMessage(cacheAgeValueText());
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP;

        // Invalidate After Export
        addLabelRow(y, "config.worlddownloader.invalidateAfterExport");
        addDrawableChild(ButtonWidget.builder(
                boolValueText(config.invalidateAfterExport),
                btn -> {
                    config.invalidateAfterExport = !config.invalidateAfterExport;
                    btn.setMessage(boolValueText(config.invalidateAfterExport));
                    config.save();
                }
        ).dimensions(btnX, y, BTN_W, ROW_H).build());
        y += ROW_GAP + 6;

        // ── Open Status Screen ────────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.worlddownloader.status.openFromSettings"),
                btn -> StatusScreen.open()
        ).dimensions(cx - 155, y, 310, ROW_H).build());
        y += ROW_GAP;

        // ── Done ─────────────────────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"),
                btn -> this.client.setScreen(parent)
        ).dimensions(cx - 100, y, 200, ROW_H).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int labelX = cx - 155;

        // Title
        context.drawCenteredTextWithShadow(textRenderer, title, cx, 15, 0xFFFFFF);

        // Per-row labels
        for (Object[] row : labelRows) {
            int    rowY = (int)    row[0];
            String key  = (String) row[1];
            if (key == null) {
                // Section header — draw centred in grey
                context.drawCenteredTextWithShadow(
                        textRenderer,
                        Text.translatable("config.worlddownloader.section.cache"),
                        cx, rowY + 5, 0xAAAAAA);
            } else {
                // Setting name — draw left-aligned, vertically centred in the row
                context.drawTextWithShadow(
                        textRenderer,
                        Text.translatable(key),
                        labelX, rowY + 6, 0xFFFFFF);
            }
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Records a label row with its translation key for rendering. */
    private void addLabelRow(int y, String translationKey) {
        labelRows.add(new Object[]{y, translationKey});
    }

    // ── Value-text helpers ────────────────────────────────────────────────────

    private Text saveLocValueText() {
        return Text.translatable("config.worlddownloader.saveLoc."
                + config.defaultSaveLocation.name().toLowerCase());
    }

    private Text syncIntervalValueText() {
        return Text.literal(config.syncIntervalSeconds + "s");
    }

    private Text logLevelValueText() {
        return Text.translatable("config.worlddownloader.logLevel."
                + config.logLevel.name().toLowerCase());
    }

    private Text conflictStrategyValueText() {
        return Text.translatable("config.worlddownloader.conflictStrategy."
                + config.defaultConflictStrategy.name().toLowerCase());
    }

    private Text intPresetValueText(int value, String disabledKey) {
        if (value <= 0) return Text.translatable(disabledKey);
        return Text.literal(String.valueOf(value));
    }

    private Text cacheAgeValueText() {
        int v = config.maxCacheAgeSeconds;
        if (v <= 0) return Text.translatable("config.worlddownloader.disabled");
        if (v < 60)   return Text.literal(v + "s");
        if (v < 3600) return Text.literal((v / 60) + "m");
        return Text.literal((v / 3600) + "h");
    }

    private Text boolValueText(boolean value) {
        return Text.translatable(value
                ? "config.worlddownloader.on"
                : "config.worlddownloader.off");
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Returns the index in {@code presets} closest to {@code value}. */
    private static int nearestIndex(int[] presets, int value) {
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < presets.length; i++) {
            int d = Math.abs(presets[i] - value);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }
}

