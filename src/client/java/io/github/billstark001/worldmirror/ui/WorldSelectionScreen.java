package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.ui.input.MouseInput;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.OfflineTasks;
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
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** A native selection list for offline worlds and saved servers without joining them. */
final class WorldSelectionScreen extends StatusScreenApi {
    private static final int ROW_HEIGHT = 36;
    private static final int HEADER_HEIGHT = 56;
    private static final int FOOTER_HEIGHT = 38;
    private static final int MAX_ICON_BYTES = 256 * 1024;
    private static final Identifier UNKNOWN_ICON =
            Identifier.withDefaultNamespace("textures/misc/unknown_server.png");

    record Choice(String id, Component name, Component detail, WorldGraph.Location location, String address) {
        static Choice world(String id, Component name, WorldGraph.Location location) {
            String root = location.root() == WorldGraph.Root.SAVES ? "saves" : "downloaded_worlds";
            return new Choice(id, name, Component.literal(root + "/" + location.folder()), location, null);
        }

        static Choice server(String id, Component name, String address) {
            return server(id, name, Component.literal(address), address);
        }

        static Choice server(String id, Component name, Component detail, String address) {
            return new Choice(id, name, detail, null, address);
        }

        static Choice action(String id, Component name) {
            return new Choice(id, name, Component.empty(), null, null);
        }

        private boolean matches(String query) {
            return query.isEmpty() || name.getString().toLowerCase(Locale.ROOT).contains(query)
                    || detail.getString().toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private final Screen parent;
    private final List<Choice> choices;
    private final Component confirmLabel;
    private final Function<Choice, Component> onConfirm;
    private final Map<String, FaviconTexture> icons = new HashMap<>();
    private ChoiceList list;
    private Button confirm;
    private String selectedId;
    private String search = "";
    private Component error;
    private int iconRequest;
    private boolean iconsRequested;

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
        if (!iconsRequested)
            loadIcons();
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
        iconRequest++;
        iconsRequested = false;
        icons.values().forEach(FaviconTexture::close);
        icons.clear();
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

    private void loadIcons() {
        iconsRequested = true;
        int request = ++iconRequest;
        Path game = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        OfflineTasks.<Map<String, byte[]>>start("WM-ReadSelectionIcons",
                () -> iconBytes(game), ignored -> Map.of(), minecraft::execute, loaded -> {
                    if (iconRequest != request)
                        return;
                    loaded.forEach((id, bytes) -> {
                        var icon = FaviconTexture.forWorld(
                                minecraft.getTextureManager(), "worldmirror-picker/" + id);
                        try {
                            icon.upload(NativeImage.read(bytes));
                            icons.put(id, icon);
                        } catch (IOException | RuntimeException invalid) {
                            icon.close();
                        }
                    });
                }, () -> {});
    }

    private Map<String, byte[]> iconBytes(Path game) {
        var found = new HashMap<String, byte[]>();
        var servers = new HashMap<String, byte[]>();
        if (choices.stream().anyMatch(choice -> choice.address() != null)) {
            var savedServers = new ServerList(minecraft);
            savedServers.load();
            for (int i = 0; i < savedServers.size(); i++) {
                var server = savedServers.get(i);
                byte[] data = server.getIconBytes();
                if (data != null && data.length <= MAX_ICON_BYTES) {
                    try {
                        servers.putIfAbsent(WorldGraphConfig.normalizeEndpoint(server.ip), data);
                    } catch (IllegalArgumentException invalid) {
                        // An invalid saved endpoint cannot identify one of the choices.
                    }
                }
            }
        }
        for (Choice choice : choices) {
            byte[] bytes = choice.location() == null ? servers.get(choice.address())
                                                     : worldIcon(game, choice.location());
            if (bytes != null)
                found.put(choice.id(), bytes);
        }
        return found;
    }

    private static byte[] worldIcon(Path game, WorldGraph.Location location) {
        String root = location.root() == WorldGraph.Root.SAVES ? "saves" : "downloaded_worlds";
        Path icon = game.resolve(root).resolve(location.folder()).resolve("icon.png");
        try {
            FileProof.requirePlainPath(game, icon);
            if (!Files.isRegularFile(icon))
                return null;
            try (var input = Files.newInputStream(icon)) {
                byte[] bytes = input.readNBytes(MAX_ICON_BYTES + 1);
                return bytes.length <= MAX_ICON_BYTES ? bytes : null;
            }
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
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
                if (choice.matches(query)) {
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
            return Component.empty().append(choice.name()).append(" ").append(choice.detail());
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
            if (choice.location() != null || choice.address() != null) {
                var icon = icons.get(choice.id());
                graphics.blit(RenderPipelines.GUI_TEXTURED,
                        icon == null ? UNKNOWN_ICON : icon.textureLocation(), x, y, 0, 0, 32, 32, 64, 64);
                x += 36;
                textWidth -= 36;
            }
            String name = font.plainSubstrByWidth(choice.name().getString(), textWidth);
            String detail = font.plainSubstrByWidth(choice.detail().getString(), textWidth);
            int nameY = y + (detail.isEmpty() ? 11 : 4);
#if MC_VERSION >= 260102
            graphics.text(font, name, x, nameY, 0xFFFFFFFF);
            graphics.text(font, detail, x, y + 17, 0xFFA0A0A0);
#else
            graphics.drawString(font, name, x, nameY, 0xFFFFFFFF);
            graphics.drawString(font, detail, x, y + 17, 0xFFA0A0A0);
#endif
        }
    }
}
