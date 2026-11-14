package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.EnumDropdownGui;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.ui.WorldGraphIconButton.Icon;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** World relationships and saved routing, available in and out of a world. */
public final class WorldRoutingScreen extends WorldMirrorTabbedScreen {
    private static final String KEY = "screen.worldmirror.routing.";
    static final String AUTOMATIC = "automatic";
    private static final int TOOLBAR_GAP = 3;
    private enum Tab { WORLDS, ENDPOINTS, STABLE_IDS }
    private static final List<Component> TABS =
            List.of(label("worlds"), label("endpoints"), label("stableIds"));

    private record WorkResult(WorldGraphService.RoutingView view, Exception failure) {}

    private final Screen parent;
    private final WorldGraphService service = WorldGraphService.instance();
    private final WorldRouteDetails details = new WorldRouteDetails(this, service);
    private final ServerRouteEditor servers = new ServerRouteEditor(this);
    private WorldGraphService.RoutingView view;
    private List<RoutingRow> rows = List.of();
    private Tab tab = Tab.WORLDS;
    private Component section = label("worlds");
    private Runnable back;
    private int listPage;
    private boolean busy;
    private Component status;
    private WorldRelationshipView graphView;
    private Button graphOpen;

    private WorldRoutingScreen(Screen parent) {
        super(label("title"));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        showScreen(new WorldRoutingScreen(parent));
    }

    @Override
    protected void init() {
        initTabs(TABS, tab.ordinal(), !busy, selected -> showTab(Tab.values()[selected]));
        graphOpen = null;
        if (view == null && status == null && !busy) {
            refresh(this::showCurrentTab);
            return;
        }
        int footerWidth = Math.min(150, (width - 24) / 2);
        var primary = Button.builder(label("refresh"), button -> refresh(this::showCurrentTab))
                              .width(footerWidth)
                              .build();
        var close = Button.builder(Component.translatable(back == null ? "gui.done" : "gui.back"),
                                  button -> onClose())
                            .width(footerWidth)
                            .build();
        primary.active = close.active = !busy;
        initFooter(primary, close);
        if (showingGraph()) {
            initGraph();
            return;
        }
        int panelWidth = Math.min(424, width - 24);
        int x = (width - panelWidth) / 2;
        int firstRow = tabContentTop() + (hasSectionHeading() ? 27 : 8);
        int available = footerTop() - 15 - firstRow;
        var pageStarts = pageStarts(available);
        listPage = Math.min(listPage, pageStarts.size() - 2);
        int y = firstRow;
        for (int i = pageStarts.get(listPage); i < pageStarts.get(listPage + 1); i++) {
            RoutingRow row = rows.get(i);
            addRow(row, x, y, panelWidth);
            y += row.height();
        }
        if (pageStarts.size() > 2) {
            int navigation = footerTop() - 35;
            int step = Math.min(100, (panelWidth - 4) / 2);
            button(label("previous"), null, width / 2 - step - 2, navigation, step,
                    listPage > 0 ? () -> changePage(-1) : null);
            button(label("next"), null, width / 2 + 2, navigation, step,
                    listPage + 2 < pageStarts.size() ? () -> changePage(1) : null);
        }
    }

    private List<Integer> pageStarts(int available) {
        if (rows.stream().mapToInt(RoutingRow::height).sum() <= available || rows.isEmpty())
            return List.of(0, rows.size());
        var starts = new ArrayList<Integer>();
        starts.add(0);
        int used = 0;
        for (int index = 0; index < rows.size(); index++) {
            int height = rows.get(index).height();
            if (used > 0 && used + height > available - RoutingRow.BUTTON_HEIGHT) {
                starts.add(index);
                used = 0;
            }
            used += height;
        }
        starts.add(rows.size());
        return starts;
    }

    private void addRow(RoutingRow row, int x, int y, int width) {
        if (row.right() == null) {
            addCell(row, x, y, width);
            return;
        }
        int half = (width - 4) / 2;
        addCell(row, x, y, half);
        addCell(row.right(), x + half + 4, y, width - half - 4);
    }

    private void addCell(RoutingRow row, int x, int y, int width) {
        if (row.text()) {
            var text = new net.minecraft.client.gui.components.StringWidget(row.label(), font)
                               .setMaxWidth(width);
            text.setPosition(x, y + (RoutingRow.TEXT_HEIGHT - font.lineHeight) / 2);
            addRenderableOnly(text);
        } else if (row.options().isEmpty()) {
            button(row.label(), row.detail(), x, y, width, row.action());
        } else {
            addButton(row.label(), row.detail(), x, y, width,
                    opener -> openSelectionPopup(opener, row.options()));
        }
    }

    private void initGraph() {
        int top = tabContentTop();
        int bottom = Math.max(top + 1, footerTop() - 2);
        int graphHeight = bottom - top;
        if (graphView == null)
            graphView = new WorldRelationshipView(0, top, width, graphHeight);
        else
            graphView.setRectangle(width, graphHeight, 0, top);
        graphView.update(view, this::displayName, details::show);
        graphView.active = !busy;
        int step = WorldGraphIconButton.SIZE + TOOLBAR_GAP;
        int toolbarX = Math.max(0, width - 3 * step - TOOLBAR_GAP);
        int toolbarY = bottom - WorldRelationshipView.HELP_HEIGHT + 3;
        graphView.setHelpWidth(toolbarX - 14);
        addRenderableWidget(graphView);
        icon(Icon.FIT, "graphFit", toolbarX, toolbarY, graphView::fit);
        graphOpen = icon(Icon.DETAILS, "graphOpen", toolbarX + step, toolbarY, graphView::openSelected);
        graphOpen.active = !busy && graphView.canOpenSelected();
        icon(Icon.SETTINGS, "settings", toolbarX + 2 * step, toolbarY, () -> {
            EnumDropdownGui.register();
            showScreen(AutoConfigClient.getConfigScreen(ModConfig.class, this).get());
        });
    }

    private Button icon(Icon icon, String key, int x, int y, Runnable action) {
        var button = addRenderableWidget(new WorldGraphIconButton(x, y, label(key), icon, action));
        button.active = !busy;
        return button;
    }

    private Button button(Component text, Component detail, int x, int y, int width, Runnable action) {
        return addButton(text, detail, x, y, width, action == null ? null : ignored -> action.run());
    }

    private Button addButton(
            Component text, Component detail, int x, int y, int width, Button.OnPress action) {
        var builder = Button.builder(text, action == null ? ignored -> {} : action).bounds(x, y, width, 20);
        if (detail != null)
            builder.tooltip(Tooltip.create(detail));
        var widget = addRenderableWidget(builder.build());
        widget.active = !busy && action != null;
        return widget;
    }

    @Override
    public void tick() {
        super.tick();
        if (graphOpen != null)
            graphOpen.active = !busy && graphView.canOpenSelected();
    }

    @Override
    protected void renderContent(Canvas canvas) {
        if (hasSectionHeading())
            canvas.centered(section, width / 2, tabContentTop() + 9, 0xFFFFFFFF);
        Component message = busy ? label("working") : status;
        if (message == null && rows.isEmpty() && !showingGraph())
            message = label("empty");
        if (message != null) {
            canvas.centered(Component.literal(font.plainSubstrByWidth(message.getString(), width - 24)),
                    width / 2, footerTop() - (showingGraph() ? WorldRelationshipView.HELP_HEIGHT + 12 : 12),
                    0xFFE0E0E0);
        }
    }

    private void changePage(int amount) {
        listPage += amount;
        rebuildWidgets();
    }

    void show(Component title, List<RoutingRow> entries, Runnable back) {
        section = title;
        rows = List.copyOf(entries);
        this.back = back;
        listPage = 0;
        rebuildWidgets();
    }

    void showWorlds() {
        show(label("worlds"), List.of(), null);
    }

    private boolean showingGraph() {
        return view != null && tab == Tab.WORLDS && back == null;
    }

    private boolean hasSectionHeading() {
        return back != null && !section.equals(TABS.get(tab.ordinal()));
    }

    WorldGraphService.RoutingView view() {
        return view;
    }

    void invalidInput() {
        status = label("invalidInput");
        rebuildWidgets();
    }

    private void showCurrentTab() {
        showTab(tab);
    }

    private void showTab(Tab selected) {
        if (busy)
            return;
        tab = selected;
        switch (tab) {
            case WORLDS -> showWorlds();
            case ENDPOINTS -> servers.showEndpoints();
            case STABLE_IDS -> servers.showBindings();
        }
    }

    WorldSelectionScreen.Choice choice(WorldGraph.Node node) {
        var name = Component.literal(displayName(node));
        if (node.location() != null)
            return WorldSelectionScreen.Choice.world(node.id(), name, node.location(), node.kind());
        var aliases = endpoints(node.id());
        String address = aliases.isEmpty() ? null : aliases.getFirst();
        var detail =
                address == null ? Component.empty() : label("serverLocation", String.join(", ", aliases));
        return WorldSelectionScreen.Choice.server(node.id(), name, detail, address);
    }

    void chooseRoute(String title, List<WorldSelectionScreen.Choice> choices, String selected,
            Consumer<String> confirm) {
        showScreen(new WorldSelectionScreen(
                this, label(title), choices, selected, Component.translatable("gui.done"), choice -> {
                    confirm.accept(routeChoiceId(choice));
                    return null;
                }));
    }

    private static String routeChoiceId(WorldSelectionScreen.Choice choice) {
        return AUTOMATIC.equals(choice.id()) ? null : choice.id();
    }

    Comparator<WorldGraph.Node> nodeOrder() {
        return Comparator.comparing((WorldGraph.Node node) -> displayName(node))
                .thenComparing(WorldGraph.Node::id);
    }

    String displayName(String id) {
        WorldGraph.Node node = view.graph().nodes().get(id);
        return node == null ? id : displayName(node);
    }

    String displayName(WorldGraph.Node node) {
        if (node.location() != null)
            return node.location().folder();
        if (node.kind() == WorldGraph.Kind.REMOTE) {
            List<String> endpoints = endpoints(node.id());
            if (!endpoints.isEmpty())
                return endpoints.getFirst() + " · " + node.id();
        }
        return node.id();
    }

    private List<String> endpoints(String id) {
        return view.config()
                .endpointRules()
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().sourceIds().contains(id))
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();
    }

    @FunctionalInterface
    interface WorldEdit {
        void apply() throws Exception;
    }

    void worldEdit(WorldEdit operation, Runnable completed) {
        run(() -> {
            operation.apply();
            return service.routingView();
        }, completed, true);
    }

    void refresh(Runnable completed) {
        run(() -> {
            service.refresh();
            return service.routingView();
        }, completed, false);
    }

    void edit(UnaryOperator<WorldGraphConfig.Snapshot> operation, Runnable completed) {
        run(() -> {
            service.editRouting(operation);
            return service.routingView();
        }, completed, true);
    }

    private void run(
            OfflineTasks.Work<WorldGraphService.RoutingView> work, Runnable completed, boolean saved) {
        if (busy)
            return;
        busy = true;
        status = null;
        if (width > 0)
            rebuildWidgets();
        OfflineTasks.start(saved ? "WM-EditWorldRouting" : "WM-RefreshWorldRouting",
                ()
                        -> {
                    try {
                        return new WorkResult(work.run(), null);
                    } catch (EndpointRoutingEdits.InvalidEdit invalid) {
                        return new WorkResult(null, invalid);
                    }
                },
                failure
                -> new WorkResult(null, failure),
                Minecraft.getInstance()::execute,
                result
                -> {
                    if (result.failure() == null) {
                        view = result.view();
                        status = saved ? label("saved") : null;
                        completed.run();
                    } else {
                        Component detail =
                                result.failure() instanceof EndpointRoutingEdits.InvalidEdit invalid
                                ? label(invalid.getMessage())
                                : Component.literal(Objects.toString(result.failure().getMessage(),
                                          result.failure().getClass().getSimpleName()));
                        status = label("failed", detail);
                    }
                    rebuildWidgets();
                },
                () -> busy = false);
    }

    @Override
    public void onClose() {
        if (busy)
            return;
        if (back != null)
            back.run();
        else
            showScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static Component label(String key, Object... values) {
        return Component.translatable(KEY + key, values);
    }
}
