package net.billstark001.worldmirror.ui;

import net.billstark001.worldmirror.download.DownloadManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * Feature 2.1 — Export Nearby Region.
 *
 * <p>Allows the player to snapshot all loaded chunks within a configurable
 * radius into a brand-new singleplayer save.  The spawn point of the new
 * world is set to the player's current block position.
 *
 * <p>The radius can be adjusted with ← / → buttons (range 1–50 chunks).
 * The world name defaults to the server / world name.
 */
@Environment(EnvType.CLIENT)
public class ExportNearbyScreen extends Screen {

    private static final int RADIUS_MIN = 1;
    private static final int RADIUS_MAX = 50;
    private static final int RADIUS_DEFAULT = 16;

    private int radius = RADIUS_DEFAULT;
    private TextFieldWidget nameField;

    private final Screen parent;

    public ExportNearbyScreen(Screen parent) {
        super(Text.translatable("screen.worldmirror.exportNearby.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // World-name field
        nameField = new TextFieldWidget(
                this.textRenderer,
                cx - 100, cy - 40, 200, 20,
                Text.translatable("screen.worldmirror.exportNearby.worldName"));
        nameField.setMaxLength(80);
        nameField.setText(defaultWorldName());
        nameField.setPlaceholder(
                Text.translatable("screen.worldmirror.exportNearby.worldName"));
        addDrawableChild(nameField);

        // Radius decrement
        addDrawableChild(ButtonWidget.builder(
                Text.literal("◀"),
                btn -> { radius = Math.max(RADIUS_MIN, radius - 1); }
        ).dimensions(cx - 60, cy, 20, 20).build());

        // Radius increment
        addDrawableChild(ButtonWidget.builder(
                Text.literal("▶"),
                btn -> { radius = Math.min(RADIUS_MAX, radius + 1); }
        ).dimensions(cx + 40, cy, 20, 20).build());

        // Confirm
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.worldmirror.exportNearby.export"),
                btn -> {
                    String name = nameField.getText().isBlank()
                            ? defaultWorldName() : nameField.getText();
                    MinecraftClient mc = MinecraftClient.getInstance();
                    mc.setScreen(null);
                    DownloadManager.exportNearbyToNewSave(mc, name, radius);
                }
        ).dimensions(cx - 100, cy + 30, 94, 20).build());

        // Cancel
        addDrawableChild(ButtonWidget.builder(
                ScreenTexts.CANCEL,
                btn -> MinecraftClient.getInstance().setScreen(parent)
        ).dimensions(cx + 6, cy + 30, 94, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int cy = this.height / 2;

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                cx, cy - 70, 0xFFFFFFFF);

        // World name label
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("screen.worldmirror.exportNearby.worldName"),
                cx - 100, cy - 55, 0xFFAAAAAA);

        // Radius display
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("screen.worldmirror.exportNearby.radius")
                        .append(": " + radius),
                cx, cy + 5, 0xFFFFFFFF);

        // Chunk count hint
        long chunkCount = (long) (2 * radius + 1) * (2 * radius + 1);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7(" + chunkCount + " chunks max)"),
                cx, cy + 18, 0xFFAAAAAA);
    }

    private static String defaultWorldName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getCurrentServerEntry() != null) {
            String host = mc.getCurrentServerEntry().address;
            return "Nearby-" + host.replaceAll("[:/\\\\]", "_");
        }
        if (mc.getServer() != null) {
            return "Nearby-" + mc.getServer().getLevelName();
        }
        return "NearbyExport";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Opens the ExportNearby screen on the game thread. */
    public static void open(Screen parent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.setScreen(new ExportNearbyScreen(parent)));
    }
}
