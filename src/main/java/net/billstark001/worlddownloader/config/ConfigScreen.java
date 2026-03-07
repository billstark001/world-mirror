package net.billstark001.worlddownloader.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * In-game configuration screen for World Downloader.
 * <p>
 * Opened from Mod Menu.  Each setting is represented by a cycle button that
 * steps through the available values on click and saves immediately.
 */
public class ConfigScreen extends Screen {

    private static final int[] SYNC_INTERVALS = {5, 10, 30, 60, 120};

    private final Screen parent;
    private final ModConfig config;

    public ConfigScreen(Screen parent) {
        super(Text.translatable("config.worlddownloader.title"));
        this.parent = parent;
        this.config = ModConfig.get();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y  = this.height / 4;

        // ── Save Location ─────────────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(
                saveLocText(),
                btn -> {
                    ModConfig.SaveLocation[] vals = ModConfig.SaveLocation.values();
                    config.defaultSaveLocation =
                            vals[(config.defaultSaveLocation.ordinal() + 1) % vals.length];
                    btn.setMessage(saveLocText());
                    config.save();
                }
        ).dimensions(cx - 155, y, 310, 20).build());

        y += 24;

        // ── Sync Interval ─────────────────────────────────────────────────────
        ButtonWidget intervalBtn = ButtonWidget.builder(
                syncIntervalText(),
                btn -> {
                    int idx = nearestIntervalIndex(config.syncIntervalSeconds);
                    config.syncIntervalSeconds = SYNC_INTERVALS[(idx + 1) % SYNC_INTERVALS.length];
                    btn.setMessage(syncIntervalText());
                    config.save();
                }
        ).dimensions(cx - 155, y, 310, 20).build();
        addDrawableChild(intervalBtn);

        y += 24;

        // ── Log Level ─────────────────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(
                logLevelText(),
                btn -> {
                    ModConfig.LogLevel[] vals = ModConfig.LogLevel.values();
                    config.logLevel = vals[(config.logLevel.ordinal() + 1) % vals.length];
                    btn.setMessage(logLevelText());
                    config.save();
                }
        ).dimensions(cx - 155, y, 310, 20).build());

        y += 24;

        // ── Conflict Strategy ─────────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(
                conflictStrategyText(),
                btn -> {
                    ModConfig.ConflictStrategy[] vals = ModConfig.ConflictStrategy.values();
                    config.defaultConflictStrategy =
                            vals[(config.defaultConflictStrategy.ordinal() + 1) % vals.length];
                    btn.setMessage(conflictStrategyText());
                    config.save();
                }
        ).dimensions(cx - 155, y, 310, 20).build());

        y += 36;

        // ── Done ─────────────────────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"),
                btn -> this.client.setScreen(parent)
        ).dimensions(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 15, 0xFFFFFF);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Text saveLocText() {
        return Text.translatable("config.worlddownloader.saveLoc")
                .append(": ")
                .append(Text.translatable(
                        "config.worlddownloader.saveLoc."
                                + config.defaultSaveLocation.name().toLowerCase()));
    }

    private Text syncIntervalText() {
        return Text.translatable("config.worlddownloader.syncInterval")
                .append(": " + config.syncIntervalSeconds + "s");
    }

    private Text logLevelText() {
        return Text.translatable("config.worlddownloader.logLevel")
                .append(": ")
                .append(Text.translatable(
                        "config.worlddownloader.logLevel."
                                + config.logLevel.name().toLowerCase()));
    }

    private Text conflictStrategyText() {
        return Text.translatable("config.worlddownloader.conflictStrategy")
                .append(": ")
                .append(Text.translatable(
                        "config.worlddownloader.conflictStrategy."
                                + config.defaultConflictStrategy.name().toLowerCase()));
    }

    /** Returns the index in SYNC_INTERVALS closest to the given value. */
    private static int nearestIntervalIndex(int seconds) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < SYNC_INTERVALS.length; i++) {
            int d = Math.abs(SYNC_INTERVALS[i] - seconds);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }
}
