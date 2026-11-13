package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.EnumDropdownGui;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.ui.WorldGraphIconButton.Icon;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import io.github.billstark001.worldmirror.xaero.XaeroWorldIdentity;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** World relationships and saved routing, available in and out of a world. */
public final class WorldRoutingScreen extends WorldMirrorTabbedScreen {
    private static final String KEY = "screen.worldmirror.routing.";
    private static final int ROW = 22;
    private static final int TOOLBAR_GAP = 3;
    private enum Tab { WORLDS, ENDPOINTS, STABLE_IDS }
    private static final List<Component> TABS =
            List.of(label("worlds"), label("endpoints"), label("stableIds"));

    private record Row(Component label, Component detail, Runnable action, List<SelectionOption> options) {
        private Row(Component label, Component detail, Runnable action) {
            this(label, detail, action, List.of());
        }
    }
    private record WorkResult(WorldGraphService.RoutingView view, Exception failure) {}

    private final Screen parent;
    private final WorldGraphService service = WorldGraphService.instance();
    private WorldGraphService.RoutingView view;
    private List<Row> rows = List.of();
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

    public static Screen modMenu(Screen parent) {
        return new WorldRoutingScreen(parent);
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
        int perPage = Math.max(1, (footerTop() - 36 - firstRow) / ROW);
        int pageCount = Math.max(1, (rows.size() + perPage - 1) / perPage);
        listPage = Math.min(listPage, pageCount - 1);
        int first = listPage * perPage;
        int limit = Math.min(rows.size(), first + perPage);
        for (int i = first; i < limit; i++) {
            Row row = rows.get(i);
            int y = firstRow + (i - first) * ROW;
            if (row.options().isEmpty())
                button(row.label(), row.detail(), x, y, panelWidth, row.action());
            else
                addButton(row.label(), row.detail(), x, y, panelWidth,
                        opener -> openSelectionPopup(opener, row.options()));
        }
        if (pageCount > 1) {
            int navigation = footerTop() - 35;
            int step = Math.min(100, (panelWidth - 4) / 2);
            button(label("previous"), null, width / 2 - step - 2, navigation, step,
                    listPage > 0 ? () -> changePage(-1) : null);
            button(label("next"), null, width / 2 + 2, navigation, step,
                    listPage + 1 < pageCount ? () -> changePage(1) : null);
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
        graphView.update(view, this::displayName, this::showWorld);
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

    private void show(Component title, List<Row> entries, Runnable back) {
        section = title;
        rows = List.copyOf(entries);
        this.back = back;
        listPage = 0;
        rebuildWidgets();
    }

    private void showWorlds() {
        show(label("worlds"), List.of(), null);
    }

    private boolean showingGraph() {
        return view != null && tab == Tab.WORLDS && back == null;
    }

    private boolean hasSectionHeading() {
        return back != null && !section.equals(TABS.get(tab.ordinal()));
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
            case ENDPOINTS -> showEndpoints();
            case STABLE_IDS -> showBindings();
        }
    }

    private void showWorld(String id) {
        WorldGraph.Node node = view.graph().nodes().get(id);
        if (node == null) {
            showWorlds();
            return;
        }
        List<Row> entries = new ArrayList<>();
        entries.add(info(label("node", id)));
        entries.add(info(label("kind", label("kind." + node.kind().name().toLowerCase(Locale.ROOT)))));
        if (node.location() != null) {
            String root = node.location().root() == WorldGraph.Root.SAVES ? "saves" : "downloaded_worlds";
            entries.add(info(label("location", root + "/" + node.location().folder())));
        }
        if (node.parentId() != null) {
            entries.add(info(label("upstream", node.parentId())));
        }
        if (!view.graph().isUnambiguous(id))
            entries.add(info(label("ambiguous")));
        if (node.kind() == WorldGraph.Kind.MIRROR && view.graph().isUnambiguous(id) && node.parentId() != null
                && (node.parentId().startsWith("unresolved:")
                        || view.graph().missingParents().contains(node.parentId()))) {
            entries.add(selection(label("repair"), parentOptions(node)));
        }
        var settings = view.config().sourceSettings().get(id);
        String selected = settings == null           ? null
                : settings.defaultMirrorId() == null ? settings.inheritedMirrorId()
                                                     : settings.defaultMirrorId();
        if (selected != null || !view.graph().childrenOf(id).isEmpty()) {
            entries.add(selection(
                    label("targetDefault", selected == null ? label("automatic") : displayName(selected)),
                    defaultMirrorOptions(id, settings == null ? null : settings.defaultMirrorId())));
        }
        if (node.kind() == WorldGraph.Kind.MIRROR) {
            var mirror = view.config().mirrorSettings().get(id);
            ModConfig.ConflictStrategy conflict = mirror == null ? null : mirror.conflictStrategy();
            entries.add(selection(label("conflict", conflictLabel(conflict)), conflictOptions(id, conflict)));
        }
        show(Component.literal(displayName(node)), entries, this::showWorlds);
    }

    private List<SelectionOption> defaultMirrorOptions(String id, String current) {
        List<SelectionOption> entries = new ArrayList<>();
        entries.add(new SelectionOption(
                label("automatic"), current == null, () -> updateDefaultMirror(id, null)));
        for (WorldGraph.Node target : view.graph().childrenOf(id)) {
            if (target.role() == WorldGraph.Role.SYNC && view.graph().isUnambiguous(target.id())) {
                entries.add(new SelectionOption(Component.literal(displayName(target)),
                        target.id().equals(current), () -> updateDefaultMirror(id, target.id())));
            }
        }
        return List.copyOf(entries);
    }

    private void updateDefaultMirror(String id, String target) {
        edit(config -> {
            var old = config.sourceSettings().get(id);
            if (old == null)
                old = new WorldGraphConfig.SourceSettings(null, null, null, null, null);
            return config.withSourceSettings(id, old.withDefaultMirror(target));
        }, () -> showWorld(id));
    }

    private List<SelectionOption> conflictOptions(String id, ModConfig.ConflictStrategy current) {
        List<SelectionOption> entries = new ArrayList<>();
        entries.add(new SelectionOption(label("inherit"), current == null, () -> setConflict(id, null)));
        Arrays.stream(ModConfig.ConflictStrategy.values())
                .forEach(value
                        -> entries.add(new SelectionOption(
                                conflictLabel(value), current == value, () -> setConflict(id, value))));
        return List.copyOf(entries);
    }

    private void setConflict(String id, ModConfig.ConflictStrategy value) {
        edit(config
                -> config.withMirrorSettings(
                        id, value == null ? null : new WorldGraphConfig.MirrorSettings(value)),
                () -> showWorld(id));
    }

    private List<SelectionOption> parentOptions(WorldGraph.Node child) {
        List<SelectionOption> entries = new ArrayList<>(view.graph()
                        .nodes()
                        .values()
                        .stream()
                        .filter(node
                                -> node.kind() != WorldGraph.Kind.UNRESOLVED && !node.id().equals(child.id())
                                        && view.graph().isUnambiguous(node.id())
                                        && !view.graph().wouldCreateCycle(child.id(), node.id()))
                        .sorted(nodeOrder())
                        .map(node
                                -> new SelectionOption(Component.literal(displayName(node)), false,
                                        ()
                                                -> run(
                                                        ()
                                                                -> {
                                                            service.repairParent(child.id(), node.id());
                                                            return service.routingView();
                                                        },
                                                        () -> showWorld(child.id()), true)))
                        .toList());
        for (WorldGraph.Location location : view.untrackedLocalWorlds()) {
            entries.add(new SelectionOption(Component.literal(location.folder()), false, () -> run(() -> {
                service.repairParent(child.id(), location);
                return service.routingView();
            }, () -> showWorld(child.id()), true)));
        }
        return List.copyOf(entries);
    }

    private void showEndpoints() {
        if (view == null)
            return;
        List<Row> entries = new ArrayList<>();
        entries.add(row(label("addEndpoint"),
                ()
                        -> input(label("endpoint"), label("endpointHint"), "",
                                entered
                                -> chooseEndpointSource(WorldGraphConfig.normalizeEndpoint(entered)))));
        view.config().endpointRules().keySet().stream().sorted().forEach(
                endpoint -> entries.add(row(Component.literal(endpoint), () -> showEndpoint(endpoint))));
        show(label("endpoints"), entries, null);
    }

    private void showEndpoint(String endpoint) {
        var rule = view.config().endpointRules().get(endpoint);
        if (rule == null) {
            showEndpoints();
            return;
        }
        List<Row> entries = new ArrayList<>();
        entries.add(info(label("addressGroup", rule.addressGroupId())));
        entries.add(row(label("addAlias"),
                ()
                        -> input(label("addAlias"), label("endpointHint"), "",
                                entered -> addAlias(endpoint, WorldGraphConfig.normalizeEndpoint(entered)))));
        entries.add(row(label("attachSource"), () -> chooseEndpointSource(endpoint)));
        entries.add(row(label("sourceDefault",
                                rule.defaultSourceId() == null ? label("automatic")
                                                               : displayName(rule.defaultSourceId())),
                () -> chooseEndpointDefault(endpoint, rule)));
        entries.add(row(label("policy", policyLabel(rule.idPolicy())), () -> choosePolicy(endpoint, rule)));
        for (String id : rule.sourceIds()) {
            entries.add(new Row(Component.literal(displayName(id)), label("node", id),
                    () -> showEndpointSource(endpoint, id)));
        }
        entries.add(row(label("removeEndpoint"), () -> confirmRemoveEndpoint(endpoint)));
        show(Component.literal(endpoint), entries, this::showEndpoints);
    }

    private void showEndpointSource(String endpoint, String sourceId) {
        show(Component.literal(displayName(sourceId)),
                List.of(info(label("node", sourceId)),
                        row(label("detachSource"),
                                ()
                                        -> edit(config
                                                -> EndpointRoutingEdits.detach(config, endpoint, sourceId),
                                                () -> showEndpoint(endpoint)))),
                () -> showEndpoint(endpoint));
    }

    private void chooseEndpointSource(String endpoint) {
        List<Row> entries =
                remoteSources()
                        .stream()
                        .map(node
                                -> new Row(Component.literal(displayName(node)), label("node", node.id()),
                                        ()
                                                -> edit(
                                                        config
                                                        -> {
                                                            if (!config.nodes().containsKey(node.id()))
                                                                throw problem("sourceUnavailable");
                                                            return config.withEndpointSource(
                                                                    endpoint, node.id());
                                                        },
                                                        () -> showEndpoint(endpoint))))
                        .toList();
        show(label("attachSource"), entries, () -> showEndpoint(endpoint));
    }

    private void addAlias(String endpoint, String alias) {
        edit(config -> EndpointRoutingEdits.addAlias(config, endpoint, alias), () -> showEndpoint(alias));
    }

    private void confirmRemoveEndpoint(String endpoint) {
        show(label("removeEndpoint"),
                List.of(info(Component.literal(endpoint)),
                        row(Component.translatable("gui.done"),
                                ()
                                        -> edit(config
                                                -> EndpointRoutingEdits.remove(config, endpoint),
                                                this::showEndpoints))),
                () -> showEndpoint(endpoint));
    }

    private void chooseEndpointDefault(String endpoint, WorldGraphConfig.EndpointRule rule) {
        List<Row> entries = new ArrayList<>();
        entries.add(row(label("automatic"), () -> setEndpointDefault(endpoint, null)));
        for (String id : rule.sourceIds()) {
            entries.add(new Row(Component.literal(displayName(id)), label("node", id),
                    () -> setEndpointDefault(endpoint, id)));
        }
        show(label("chooseSource"), entries, () -> showEndpoint(endpoint));
    }

    private void setEndpointDefault(String endpoint, String sourceId) {
        edit(config -> {
            var old = EndpointRoutingEdits.requireEndpoint(config, endpoint);
            if (sourceId != null && !old.sourceIds().contains(sourceId))
                throw problem("sourceUnavailable");
            return config.withEndpointRule(endpoint,
                    new WorldGraphConfig.EndpointRule(
                            old.addressGroupId(), old.sourceIds(), sourceId, old.idPolicy()));
        }, () -> showEndpoint(endpoint));
    }

    private void choosePolicy(String endpoint, WorldGraphConfig.EndpointRule rule) {
        show(label("choosePolicy"),
                Arrays.stream(WorldGraphConfig.IdPolicy.values())
                        .map(value
                                -> new Row(policyLabel(value),
                                        value == rule.idPolicy() ? label("current") : null,
                                        ()
                                                -> edit(
                                                        config
                                                        -> {
                                                            var old = EndpointRoutingEdits.requireEndpoint(
                                                                    config, endpoint);
                                                            return config.withEndpointRule(endpoint,
                                                                    new WorldGraphConfig.EndpointRule(
                                                                            old.addressGroupId(),
                                                                            old.sourceIds(),
                                                                            old.defaultSourceId(), value));
                                                        },
                                                        () -> showEndpoint(endpoint))))
                        .toList(),
                () -> showEndpoint(endpoint));
    }

    private void showBindings() {
        if (view == null)
            return;
        List<Row> entries = new ArrayList<>();
        if (!view.config().endpointRules().isEmpty()) {
            entries.add(row(label("addBinding"), this::chooseBindingEndpoint));
        }
        for (WorldGraphConfig.StableBinding binding : view.config().stableBindings()) {
            entries.add(new Row(label("binding", binding.provider(), binding.advertisedId()),
                    label("node", binding.sourceId()), () -> showBinding(binding)));
        }
        show(label("stableIds"), entries, null);
    }

    private void showBinding(WorldGraphConfig.StableBinding binding) {
        show(label("stableIds"),
                List.of(info(label("binding", binding.provider(), binding.advertisedId())),
                        info(label("node", binding.sourceId())),
                        row(label("removeBinding"),
                                ()
                                        -> edit(config
                                                -> config.withoutStableBinding(binding.provider(),
                                                        binding.addressGroupId(), binding.advertisedId()),
                                                this::showBindings))),
                this::showBindings);
    }

    private void chooseBindingEndpoint() {
        show(label("endpoint"),
                view.config()
                        .endpointRules()
                        .entrySet()
                        .stream()
                        .filter(entry -> !entry.getValue().sourceIds().isEmpty())
                        .map(entry
                                -> row(Component.literal(entry.getKey()),
                                        () -> chooseBindingSource(entry.getKey(), entry.getValue())))
                        .toList(),
                this::showBindings);
    }

    private void chooseBindingSource(String endpoint, WorldGraphConfig.EndpointRule rule) {
        show(label("chooseSource"),
                rule.sourceIds()
                        .stream()
                        .map(source
                                -> row(Component.literal(displayName(source)),
                                        () -> beginBinding(endpoint, source)))
                        .toList(),
                this::chooseBindingEndpoint);
    }

    private void beginBinding(String endpoint, String source) {
        input(label("bindingProvider"), label("bindingProvider"), XaeroWorldIdentity.PROVIDER,
                provider
                -> input(label("bindingId"), label("bindingId"), "",
                        id -> addBinding(endpoint, source, provider, id)));
    }

    private void addBinding(String endpoint, String source, String provider, String id) {
        edit(config -> {
            var current = EndpointRoutingEdits.requireEndpoint(config, endpoint);
            if (!current.sourceIds().contains(source))
                throw problem("sourceUnavailable");
            var candidate =
                    new WorldGraphConfig.StableBinding(provider, current.addressGroupId(), id, source);
            if (config.stableBindings().stream().anyMatch(existing
                        -> existing.provider().equals(provider)
                                && existing.addressGroupId().equals(current.addressGroupId())
                                && existing.advertisedId().equals(id)
                                && !existing.sourceId().equals(source))) {
                throw problem("bindingExists");
            }
            return config.withStableBinding(candidate);
        }, this::showBindings);
    }

    private List<WorldGraph.Node> remoteSources() {
        return view.graph()
                .nodes()
                .values()
                .stream()
                .filter(node -> node.kind() == WorldGraph.Kind.REMOTE)
                .sorted(nodeOrder())
                .toList();
    }

    private Comparator<WorldGraph.Node> nodeOrder() {
        return Comparator.comparing((WorldGraph.Node node) -> displayName(node))
                .thenComparing(WorldGraph.Node::id);
    }

    private String displayName(String id) {
        WorldGraph.Node node = view.graph().nodes().get(id);
        return node == null ? id : displayName(node);
    }

    private String displayName(WorldGraph.Node node) {
        if (node.location() != null)
            return node.location().folder();
        if (node.kind() == WorldGraph.Kind.REMOTE) {
            List<String> endpoints =
                    view.config()
                            .endpointRules()
                            .entrySet()
                            .stream()
                            .filter(entry -> entry.getValue().sourceIds().contains(node.id()))
                            .map(java.util.Map.Entry::getKey)
                            .sorted()
                            .toList();
            if (!endpoints.isEmpty())
                return endpoints.getFirst() + " · " + node.id();
        }
        return node.id();
    }

    private void input(Component title, Component hint, String value, Consumer<String> accept) {
        showScreen(new StatusScreenApi(title) {
            private String entered = value;

            @Override
            protected void init() {
                int center = width / 2;
                int top = height / 2;
                var field = new EditBox(font, center - 100, top - 16, 200, 20, hint);
                field.setMaxLength(255);
                field.setValue(entered);
                field.setHint(hint);
                field.setResponder(value -> entered = value);
                addRenderableWidget(field);
                setInitialFocus(field);
                addRenderableWidget(
                        Button.builder(Component.translatable("gui.done"), button -> {
                                  showScreen(WorldRoutingScreen.this);
                                  try {
                                      accept.accept(field.getValue().strip());
                                  } catch (IllegalArgumentException invalid) {
                                      status = label("invalidInput");
                                      rebuildWidgets();
                                  }
                              }).bounds(center - 100, top + 10, 98, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                                .bounds(center + 2, top + 10, 98, 20)
                                .build());
            }

            @Override
            protected void renderContent(Canvas canvas) {
                canvas.centered(title, width / 2, height / 2 - 34, 0xFFFFFFFF);
            }

            @Override
            public void onClose() {
                showScreen(WorldRoutingScreen.this);
            }
        });
    }

    private void refresh(Runnable completed) {
        run(() -> {
            service.refresh();
            return service.routingView();
        }, completed, false);
    }

    private void edit(UnaryOperator<WorldGraphConfig.Snapshot> operation, Runnable completed) {
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
        OfflineTasks.start("WM-WorldRouting",
                ()
                        -> new WorkResult(work.run(), null),
                failure -> new WorkResult(null, failure), Minecraft.getInstance()::execute, result -> {
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
                }, () -> busy = false);
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

    private static Row row(Component title, Runnable action) {
        return new Row(title, null, action);
    }

    private static Row selection(Component title, List<SelectionOption> options) {
        return new Row(title.copy().append("  ▼"), title, null, options);
    }

    private static Row info(Component title) {
        return new Row(title, title, null);
    }

    private static Component label(String key, Object... values) {
        return Component.translatable(KEY + key, values);
    }

    private static EndpointRoutingEdits.InvalidEdit problem(String key) {
        return new EndpointRoutingEdits.InvalidEdit(key);
    }

    private static Component policyLabel(WorldGraphConfig.IdPolicy value) {
        return label("policy." + value.name().toLowerCase(Locale.ROOT));
    }

    private static Component conflictLabel(ModConfig.ConflictStrategy value) {
        return value == null ? label("inherit")
                             : Component.translatable("config.worldmirror.conflictStrategy."
                                       + value.name().toLowerCase(Locale.ROOT));
    }
}
