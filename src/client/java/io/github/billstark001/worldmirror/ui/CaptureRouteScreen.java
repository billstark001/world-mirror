package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraphService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** A server with insufficient identity evidence needs a deliberate choice for this session. */
public final class CaptureRouteScreen extends Screen {
    private final Screen parent;
    private final List<String> choices;
    private final BiConsumer<String, Boolean> onChoice;
    private int page;

    private CaptureRouteScreen(
            Screen parent, String endpoint, Set<String> choices, BiConsumer<String, Boolean> onChoice) {
        super(Component.translatable("screen.worldmirror.captureRoute.title", endpoint));
        this.parent = parent;
        this.choices = choices.stream().sorted().toList();
        this.onChoice = onChoice;
    }

    public static void open(
            Screen parent, String endpoint, Set<String> choices, BiConsumer<String, Boolean> onChoice) {
        setScreen(new CaptureRouteScreen(parent, endpoint, choices, onChoice));
    }

    @Override
    protected void init() {
        int visible = Math.max(1, (height - 104) / 24);
        int pages = Math.max(1, (choices.size() + visible - 1) / visible);
        page = Math.min(page, pages - 1);
        int buttonWidth = Math.min(380, width - 30);
        int x = (width - buttonWidth) / 2;
        int end = Math.min(choices.size(), (page + 1) * visible);
        for (int i = page * visible; i < end; ++i) {
            String id = choices.get(i);
            var targets = WorldGraphService.instance().targetsForSource(id);
            String label = targets.isEmpty() ? id : targets.getFirst().folder().getFileName() + " · " + id;
            addRenderableWidget(Button.builder(Component.literal(label), button -> choose(id, false))
                            .bounds(x, 38 + (i - page * visible) * 24, buttonWidth, 20)
                            .build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), button -> showPage(page - 1))
                                    .bounds(x, height - 64, 22, 20)
                                    .build())
                .active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), button -> showPage(page + 1))
                                    .bounds(x + buttonWidth - 22, height - 64, 22, 20)
                                    .build())
                .active = page + 1 < pages;
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.captureRoute.newSource"),
                              button -> choose(null, true))
                        .bounds(x, height - 40, buttonWidth / 2 - 2, 20)
                        .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                        .bounds(x + buttonWidth / 2 + 2, height - 40, buttonWidth / 2 - 2, 20)
                        .build());
    }

    private void showPage(int next) {
        page = next;
        rebuildWidgets();
    }

    private void choose(String id, boolean create) {
        onClose();
        onChoice.accept(id, create);
    }

    @Override
    public void onClose() {
        setScreen(parent);
    }

    private static void setScreen(Screen screen) {
#if MC_VERSION >= 260200
        ClientScreens.set(screen);
#else
        Minecraft.getInstance().setScreen(screen);
#endif
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

#if MC_VERSION >= 260102
    @Override
    public void extractRenderState(
            net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, float delta) {
        super.extractRenderState(graphics, x, y, delta);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
    }
#else
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, float delta) {
        super.render(graphics, x, y, delta);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFFFF);
    }
#endif
}
