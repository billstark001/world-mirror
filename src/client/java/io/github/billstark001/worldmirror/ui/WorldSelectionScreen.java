package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.ui.input.MouseInput;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= 260102
import net.minecraft.client.gui.GuiGraphicsExtractor;
#else
import net.minecraft.client.gui.GuiGraphics;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A native selection list for offline worlds and saved servers without joining them. */
public final class WorldSelectionScreen extends StatusScreenApi {
    private static final int ROW_HEIGHT = 36;
    private static final int HEADER_HEIGHT = 56;
    private static final int FOOTER_HEIGHT = 38;
    private static final int ICON_SIZE = 32;
    private static final int ICON_BATCH_SIZE = 8;
    private static final int ICON_CACHE_SIZE = 128;
    private static final int DETAIL_COLOR = 0xFF808080;
    private static final Identifier UNKNOWN_ICON =
            Identifier.withDefaultNamespace("textures/misc/unknown_server.png");

    public record Choice(String id, Component name, Component detail, WorldGraph.Location location,
            String address, WorldGraph.Kind kind) {
        static Choice world(String id, Component name, WorldGraph.Location location, WorldGraph.Kind kind) {
            return new Choice(id, name, Component.literal(WorldLabels.path(location)), location, null, kind);
        }

        static Choice server(String id, Component name, String address) {
            return new Choice(id, name,
                    Component.translatable("screen.worldmirror.routing.serverLocation", address), null,
                    address, null);
        }

        static Choice server(String id, Component name, Component detail, String address) {
            return new Choice(id, name, detail, null, address, WorldGraph.Kind.REMOTE);
        }

        static Choice action(String id, Component name) {
            return new Choice(id, name, Component.empty(), null, null, null);
        }

        boolean isAction() {
            return kind == null && address == null;
        }
    }

    private record Details(
            Map<String, SelectionRowData.World> worlds, Map<String, SelectionRowData.Server> servers) {
        static final Details EMPTY = new Details(Map.of(), Map.of());
    }

    private record Presentation(Component title, Component detail, Component info) {
        boolean matches(String query) {
            return contains(title, query) || contains(detail, query) || contains(info, query);
        }

        private static boolean contains(Component text, String query) {
            return text.getString().toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private final Screen parent;
    private final List<Choice> choices;
    private final Component confirmLabel;
    private final Function<Choice, Component> onConfirm;
    private final Map<String, FaviconTexture> icons = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> absentIcons = new HashSet<>();
    private final Map<String, Choice> pendingIcons = new LinkedHashMap<>();
    private final Map<String, Presentation> presentations = new HashMap<>();
    private ChoiceList list;
    private Set<String> requestedIcons = Set.of();
    private Details details = Details.EMPTY;
    private Button confirm;
    private String selectedId;
    private String search = "";
    private Component error;
    private AtomicBoolean loading;
    private Path game;

    WorldSelectionScreen(Screen parent, Component title, List<Choice> choices, String selectedId,
            Component confirmLabel, Function<Choice, Component> onConfirm) {
        super(title);
        this.parent = parent;
        this.choices = List.copyOf(choices);
        this.selectedId = selectedId;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        var layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);
        var header = layout.addToHeader(LinearLayout.vertical().spacing(5));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font).setMaxWidth(width - 12));
        var field = header.addChild(new EditBox(
                font, Math.min(300, width - 24), 20, Component.translatable("selectWorld.search")));
        field.setHint(Component.translatable("gui.selectWorld.search"));
        field.setValue(search);
        field.setResponder(value -> {
            search = value;
            list.populate();
        });

        var footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        int buttonWidth = Math.min(150, (width - 24) / 2);
        confirm = footer.addChild(
                Button.builder(confirmLabel, ignored -> confirm()).width(buttonWidth).build());
        footer.addChild(Button.builder(Component.translatable("gui.cancel"), ignored -> onClose())
                        .width(buttonWidth)
                        .build());
        layout.arrangeElements();
        list = addRenderableWidget(new ChoiceList(minecraft));
        list.populate();
        layout.visitWidgets(this::addRenderableWidget);
        setInitialFocus(field);
        if (loading == null)
            loadDetails();
    }

    @Override
    protected void renderContent(Canvas canvas) {
        if (list != null && list.children().isEmpty())
            canvas.centered(Component.translatable("screen.worldmirror.routing.empty"), width / 2, height / 2,
                    0xFFA0A0A0);
        if (error != null)
            canvas.centered(error, width / 2, height - 11, 0xFFFF6060);
    }

    @Override
    public void onClose() {
        showScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        if (loading != null)
            loading.set(true);
        loading = null;
        icons.values().forEach(FaviconTexture::close);
        icons.clear();
        absentIcons.clear();
        pendingIcons.clear();
        requestedIcons = Set.of();
        presentations.clear();
        details = Details.EMPTY;
    }

    private void confirm() {
        var selected = list.getSelected();
        if (selected == null)
            return;
        onClose();
        error = onConfirm.apply(selected.choice);
        if (error != null)
            showScreen(this);
    }

    private void loadDetails() {
        var cancelled = new AtomicBoolean();
        loading = cancelled;
        game = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        Path root = game;
        OfflineTasks.start("WM-ReadSelectionDetails",
                () -> readDetails(root, cancelled), ignored -> Details.EMPTY, minecraft::execute, loaded -> {
                    if (cancelled.get())
                        return;
                    details = loaded;
                    presentations.clear();
                    if (!search.isBlank())
                        list.populate();
                }, () -> {});
    }

    private Details readDetails(Path game, AtomicBoolean cancelled) {
        var worlds = new HashMap<String, SelectionRowData.World>();
        var addresses =
                choices.stream().map(Choice::address).filter(Objects::nonNull).collect(Collectors.toSet());
        var servers = SelectionRowData.servers(game, addresses);
        for (Choice choice : choices) {
            if (cancelled.get())
                return Details.EMPTY;
            if (choice.location() != null) {
                String path = WorldLabels.path(choice.location());
                var world = SelectionRowData.world(game, game.resolve(path), path);
                if (world != null)
                    worlds.put(choice.id(), world);
            }
        }
        return new Details(worlds, servers);
    }

    @Override
    public void tick() {
        super.tick();
        if (loading == null || !requestedIcons.isEmpty() || pendingIcons.isEmpty())
            return;

        // The rendered rows request icons lazily. Keep one small batch in flight and bound the GPU cache.
        var worlds = new ArrayList<Choice>(ICON_BATCH_SIZE);
        for (Choice choice : pendingIcons.values()) {
            if (choice.location() != null) {
                worlds.add(choice);
            } else {
                var server = details.servers().get(choice.address());
                installIcon(choice.id(), server == null ? null : server.icon());
            }
        }
        pendingIcons.clear();
        if (worlds.isEmpty())
            return;
        requestedIcons = worlds.stream().map(Choice::id).collect(Collectors.toUnmodifiableSet());
        var cancelled = loading;
        Path root = game;
        OfflineTasks.start("WM-ReadSelectionIcons",
                ()
                        -> readIcons(root, worlds, cancelled),
                ignored
                -> Map.<String, byte[]>of(),
                minecraft::execute,
                loaded
                -> {
                    if (cancelled.get())
                        return;
                    for (Choice choice : worlds) installIcon(choice.id(), loaded.get(choice.id()));
                    requestedIcons = Set.of();
                },
                ()
                        -> {},
                () -> {
                    if (!cancelled.get())
                        requestedIcons = Set.of();
                });
    }

    private static Map<String, byte[]> readIcons(Path game, List<Choice> batch, AtomicBoolean cancelled) {
        var found = new HashMap<String, byte[]>();
        for (Choice choice : batch) {
            if (cancelled.get())
                return Map.of();
            Path folder = game.resolve(WorldLabels.path(choice.location()));
            byte[] bytes = SelectionRowData.worldIcon(game, folder);
            if (bytes != null)
                found.put(choice.id(), bytes);
        }
        return found;
    }

    private Identifier icon(Choice choice) {
        String id = choice.id();
        var icon = icons.get(id);
        if (icon == null && !absentIcons.contains(id) && !requestedIcons.contains(id)
                && pendingIcons.size() < ICON_BATCH_SIZE
                && (choice.location() != null || hasSavedServerIcon(choice)))
            pendingIcons.putIfAbsent(id, choice);
        return icon == null ? UNKNOWN_ICON : icon.textureLocation();
    }

    private boolean hasSavedServerIcon(Choice choice) {
        var server = choice.address() == null ? null : details.servers().get(choice.address());
        return server != null && server.icon() != null;
    }

    private void installIcon(String id, byte[] bytes) {
        if (bytes == null) {
            absentIcons.add(id);
            return;
        }
        var icon = FaviconTexture.forWorld(minecraft.getTextureManager(), "worldmirror-picker/" + id);
        try {
            icon.upload(NativeImage.read(bytes));
            icons.put(id, icon);
            if (icons.size() > ICON_CACHE_SIZE) {
                var oldest = icons.entrySet().iterator();
                oldest.next().getValue().close();
                oldest.remove();
            }
        } catch (IOException | RuntimeException unavailable) {
            icon.close();
            absentIcons.add(id);
            WMLogger.warnRateLimited(
                    "picker.icon-upload", 30_000, "Cannot display world picker icon", unavailable);
        }
    }

    private Presentation presentation(Choice choice) {
        return presentations.computeIfAbsent(choice.id(), ignored -> createPresentation(choice));
    }

    private Presentation createPresentation(Choice choice) {
        if (choice.isAction())
            return new Presentation(choice.name(), choice.detail(), Component.empty());
        Component kind = choice.kind() == null ? Component.empty() : WorldLabels.kind(choice.kind());
        var world = details.worlds().get(choice.id());
        if (world != null) {
            return new Presentation(
                    world.name(), world.pathAndDate(), kind.copy().append(" · ").append(world.info()));
        }
        var server = choice.address() == null ? null : details.servers().get(choice.address());
        if (server != null && !server.name().getString().equals(choice.name().getString())) {
            // The configured logical name can differ between worlds behind one saved server address.
            if (choice.name().getString().equals(choice.address()))
                return new Presentation(server.name(), choice.detail(), kind);
            return new Presentation(
                    choice.name(), choice.detail(), kind.copy().append(" · ").append(server.name()));
        }
        return new Presentation(choice.name(), choice.detail(), kind);
    }

    private final class ChoiceList extends ObjectSelectionList<Row> {
        ChoiceList(Minecraft client) {
            super(client, WorldSelectionScreen.this.width,
                    Math.max(1, WorldSelectionScreen.this.height - HEADER_HEIGHT - FOOTER_HEIGHT),
                    HEADER_HEIGHT, ROW_HEIGHT);
        }

        @Override
        public int getRowWidth() {
            return Math.min(300, width - 26);
        }

        void populate() {
            setFocused(null);
            clearEntries();
            setScrollAmount(0);
            String query = search.strip().toLowerCase(Locale.ROOT);
            Row matching = null;
            for (Choice choice : choices) {
                if (query.isEmpty() || Presentation.contains(choice.name(), query)
                        || presentation(choice).matches(query)) {
                    var row = new Row(choice);
                    addEntry(row);
                    if (choice.id().equals(selectedId))
                        matching = row;
                }
            }
            // Filtering can remove the selected row. Retain its id if it becomes visible again.
            super.setSelected(matching);
            confirm.active = matching != null;
            if (matching != null)
                centerScrollOn(matching);
        }

        @Override
        public void setSelected(Row row) {
            super.setSelected(row);
            if (row != null)
                selectedId = row.choice.id();
            confirm.active = row != null;
        }
    }

    private final class Row extends ObjectSelectionList.Entry<Row> {
        private final Choice choice;

        Row(Choice choice) {
            this.choice = choice;
        }

        @Override
        public Component getNarration() {
            var content = presentation(choice);
            return Component.empty()
                    .append(content.title())
                    .append(" ")
                    .append(content.detail())
                    .append(" ")
                    .append(content.info());
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!MouseInput.isPrimary(event))
                return false;
            list.setSelected(this);
            if (doubleClick)
                confirm();
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!event.isConfirmation())
                return false;
            list.setSelected(this);
            confirm();
            return true;
        }

        @Override
#if MC_VERSION >= 260102
        public void extractContent(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
#else
        public void renderContent(
                GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
#endif
            int x = getContentX();
            int y = getContentY();
            int textWidth = getContentWidth();
            var content = presentation(choice);
            if (!choice.isAction()) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, icon(choice), x, y, 0, 0, ICON_SIZE, ICON_SIZE,
                        ICON_SIZE, ICON_SIZE);
                x += ICON_SIZE + 3;
                textWidth -= ICON_SIZE + 3;
            }
            int nameY = choice.isAction() ? y + 11 : y + 1;
            drawLine(graphics, content.title(), x, nameY, textWidth, 0xFFFFFFFF);
            drawLine(graphics, content.detail(), x, y + 12, textWidth, DETAIL_COLOR);
            drawLine(graphics, content.info(), x, y + 22, textWidth, DETAIL_COLOR);
            if (hovered
                    && (font.width(content.title()) > textWidth || font.width(content.detail()) > textWidth
                            || font.width(content.info()) > textWidth)) {
                graphics.setComponentTooltipForNextFrame(
                        font, List.of(content.title(), content.detail(), content.info()), mouseX, mouseY);
            }
        }

#if MC_VERSION >= 260102
        private void drawLine(
                GuiGraphicsExtractor graphics, Component line, int x, int y, int width, int color) {
#else
        private void drawLine(GuiGraphics graphics, Component line, int x, int y, int width, int color) {
#endif
            var clipped = font.width(line) <= width
                    ? line.getVisualOrderText()
                    : Language.getInstance().getVisualOrder(font.substrByWidth(line, Math.max(0, width)));
#if MC_VERSION >= 260102
            graphics.text(font, clipped, x, y, color);
#else
            graphics.drawString(font, clipped, x, y, color);
#endif
        }
    }
}
