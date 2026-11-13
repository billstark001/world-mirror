package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.NearbyExporter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import java.util.List;

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
    private EditBox nameField;
    private NearbyExporter.Choice lineageChoice = NearbyExporter.Choice.INHERIT_ORIGINAL;
    private List<NearbyExporter.Choice> lineageChoices;

    private final Screen parent;

    public ExportNearbyScreen(Screen parent) {
        super(Component.translatable("screen.worldmirror.exportNearby.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        String currentName = nameField == null ? defaultWorldName() : nameField.getValue();
        lineageChoices = NearbyExporter.lineageChoices(Minecraft.getInstance());
        if (!lineageChoices.contains(lineageChoice))
            lineageChoice = lineageChoices.getFirst();
        int nameY = cy - 60;
        int radiusY = cy + 20;

        // World-name field
        nameField = new EditBox(this.font, cx - 100, nameY, 200, 20,
                Component.translatable("screen.worldmirror.exportNearby.worldName"));
        nameField.setMaxLength(80);
        nameField.setValue(currentName);
        nameField.setHint(Component.translatable("screen.worldmirror.exportNearby.worldName"));
        addRenderableWidget(nameField);

        Button lineage = addRenderableWidget(
                Button.builder(lineageLabel(), btn -> {
                          int next = (lineageChoices.indexOf(lineageChoice) + 1) % lineageChoices.size();
                          lineageChoice = lineageChoices.get(next);
                          btn.setMessage(lineageLabel());
                      }).bounds(cx - 100, cy - 25, 200, 20).build());
        lineage.active = lineageChoices.size() > 1;

        // Radius decrement
        addRenderableWidget(
                Button.builder(Component.literal("<"), btn -> {
                          radius = Math.max(RADIUS_MIN, radius - 1);
                      }).bounds(cx - 60, radiusY, 20, 20).build());

        // Radius increment
        addRenderableWidget(
                Button.builder(Component.literal(">"), btn -> {
                          radius = Math.min(RADIUS_MAX, radius + 1);
                      }).bounds(cx + 40, radiusY, 20, 20).build());

        // Confirm
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.exportNearby.export"), btn -> {
                          String name =
                                  nameField.getValue().isBlank() ? defaultWorldName() : nameField.getValue();
                          Minecraft mc = Minecraft.getInstance();
#if MC_VERSION >= 260200
                          ClientScreens.set(null);
#else
                    mc.setScreen(null);
#endif
                          NearbyExporter.export(mc, name, radius, lineageChoice);
                      }).bounds(cx - 100, radiusY + 30, 94, 20).build());

        // Cancel
        addRenderableWidget(
                Button.builder(Component.translatable("gui.cancel"),
#if MC_VERSION >= 260200
                              btn -> ClientScreens.set(parent)
#else
                              btn -> Minecraft.getInstance().setScreen(parent)
#endif
                                       )
                        .bounds(cx + 6, radiusY + 30, 94, 20)
                        .build());
    }

    @Override
#if MC_VERSION >= 260102
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
#else
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
#endif

        int cx = this.width / 2;
        int cy = this.height / 2;

        // Title
#if MC_VERSION >= 260102
        context.centeredText(this.font, this.title,
#else
        context.drawCenteredString(this.font, this.title,
#endif
                cx, cy - 100, 0xFFFFFFFF);

        // World name label
#if MC_VERSION >= 260102
        context.text(this.font,
#else
        context.drawString(this.font,
#endif
                Component.translatable("screen.worldmirror.exportNearby.worldName"), cx - 100, cy - 75,
                0xFFAAAAAA);

        int radiusY = cy + 20;
#if MC_VERSION >= 260102
        context.text(this.font,
#else
        context.drawString(this.font,
#endif
                Component.translatable("screen.worldmirror.exportNearby.lineage"), cx - 100, cy - 40,
                0xFFAAAAAA);

        // Radius display
#if MC_VERSION >= 260102
        context.centeredText(this.font,
#else
        context.drawCenteredString(this.font,
#endif
                Component.translatable("screen.worldmirror.exportNearby.radius").append(": " + radius), cx,
                radiusY + 5, 0xFFFFFFFF);

        // Chunk count hint
        long chunkCount = (long) (2 * radius + 1) * (2 * radius + 1);
#if MC_VERSION >= 260102
        context.centeredText(this.font,
#else
        context.drawCenteredString(this.font,
#endif
                Component.translatable("screen.worldmirror.exportNearby.chunkCount", chunkCount), cx,
                radiusY + 18, 0xFFAAAAAA);
    }

    private Component lineageLabel() {
        return Component.translatable("screen.worldmirror.exportNearby.lineage")
                .append(": ")
                .append(Component.translatable(
                        "screen.worldmirror.exportNearby.lineage." + lineageChoice.name().toLowerCase()));
    }

    private static String defaultWorldName() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            String host = server.ip;
            return "Nearby-" + host.replaceAll("[:/\\\\]", "_");
        }
        if (mc.hasSingleplayerServer()) {
            return "NearbySingleplayer";
        }
        return "NearbyExport";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Opens the ExportNearby screen on the game thread. */
    public static void open(Screen parent) {
#if MC_VERSION >= 260200
        ClientScreens.setLater(new ExportNearbyScreen(parent));
#else
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new ExportNearbyScreen(parent)));
#endif
    }
}
