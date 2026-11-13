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
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** World relationships and saved routing, available in and out of a world. */
public final class WorldRoutingScreen extends WorldMirrorTabbedScreen {
    private static final String KEY = "screen.worldmirror.routing.";
    private static final String AUTOMATIC = "automatic";
    private static final int ROW = 22;
    private static final int TEXT_ROW = 13;
    private static final int TOOLBAR_GAP = 3;
    private enum Tab { WORLDS, ENDPOINTS, STABLE_IDS }
    private static final List<Component> TABS =
            List.of(label("worlds"), label("endpoints"), label("stableIds"));

    private record Row(Component label, Component detail, Runnable action, List<SelectionOption> options,
            Row right, boolean text) {
        private Row(Component label, Component detail, Runnable action) {
            this(label, detail, action, List.of(), null, false);
        }

        int height() {
            return text ? TEXT_ROW : ROW;
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
            Row row = rows.get(i);
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
        if (rows.stream().mapToInt(Row::height).sum() <= available || rows.isEmpty())
            return List.of(0, rows.size());
        var starts = new ArrayList<Integer>();
        starts.add(0);
        int used = 0;
        for (int index = 0; index < rows.size(); index++) {
            int height = rows.get(index).height();
            if (used > 0 && used + height > available - ROW) {
                starts.add(index);
                used = 0;
            }
            used += height;
        }
        starts.add(rows.size());
        return starts;
    }

    private void addRow(Row row, int x, int y, int width) {
        if (row.right() != null) {
            int half = (width - 4) / 2;
            addRow(new Row(row.label(), row.detail(), row.action(), row.options(), null, row.text()), x, y,
                    half);
            addRow(row.right(), x + half + 4, y, width - half - 4);
        } else if (row.text()) {
            var text = new net.minecraft.client.gui.components.StringWidget(row.label(), font)
                               .setMaxWidth(width);
            text.setPosition(x, y + (TEXT_ROW - font.lineHeight) / 2);
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
            for (var location : view.orphanedMirrors()) {
                if (WorldRelationshipLayout.untrackedKey(location).equals(id)) {
                    showScreen(new MirrorSeedScreen(
                            this, null, service.path(location), () -> refresh(this::showWorlds)));
                    return;
                }
            }
            showWorlds();
            return;
        }
        List<Row> entries = new ArrayList<>();
        entries.add(info(label("node", id)));
        entries.add(info(label("kind", WorldLabels.kind(node.kind()))));
        if (node.location() != null)
            entries.add(info(label("location", WorldLabels.path(node.location()))));
        boolean unambiguous = view.graph().isUnambiguous(id);
        boolean managed = node.kind() == WorldGraph.Kind.MIRROR || node.kind() == WorldGraph.Kind.SNAPSHOT;
        boolean editable = managed && view.graph().hasUniqueLocation(id);
        if (managed) {
            entries.add(info(label("upstream",
                    node.parentId() == null ? label("upstreamNone") : displayName(node.parentId()))));
        }
        if (!unambiguous)
            entries.add(info(label("ambiguous")));
        if (editable) {
            entries.add(pair(label("chooseUpstream"),
                    ()
                            -> chooseUpstream(node),
                    label("removeUpstream"),
                    node.parentId() == null
                            ? null
                            : () -> worldEdit(() -> service.removeUpstream(id), () -> showWorld(id))));
        }
        var settings = view.config().sourceSettings().get(id);
        String selected = settings == null           ? null
                : settings.defaultMirrorId() == null ? settings.inheritedMirrorId()
                                                     : settings.defaultMirrorId();
        if (selected != null || !view.graph().childrenOf(id).isEmpty()) {
            String defaultId = settings == null ? null : settings.defaultMirrorId();
            entries.add(
                    row(label("targetDefault", selected == null ? label("automatic") : displayName(selected)),
                            unambiguous ? () -> chooseDefaultMirror(id, defaultId) : null));
        }
        if (node.kind() == WorldGraph.Kind.MIRROR) {
            var mirror = view.config().mirrorSettings().get(id);
            ModConfig.ConflictStrategy conflict = mirror == null ? null : mirror.conflictStrategy();
            if (unambiguous)
                entries.add(
                        selection(label("conflict", conflictLabel(conflict)), conflictOptions(id, conflict)));
        }
        if (editable) {
            var folder = service.path(node.location());
            entries.add(worldLocation(node));
            entries.add(pair(Component.translatable("screen.worldmirror.seed.title"),
                    ()
                            -> showScreen(new MirrorSeedScreen(this, id, folder)),
                    Component.translatable("selectWorld.delete"), () -> confirmDelete(node)));
        } else if (node.kind() == WorldGraph.Kind.LOCAL && view.graph().hasUniqueLocation(id)) {
            entries.add(row(Component.translatable("selectWorld.delete"), () -> confirmDelete(node)));
        }
        show(Component.literal(displayName(node)), entries, this::showWorlds);
    }

    private void chooseDefaultMirror(String id, String current) {
        List<WorldSelectionScreen.Choice> entries = new ArrayList<>();
        entries.add(WorldSelectionScreen.Choice.action(AUTOMATIC, label("automatic")));
        for (WorldGraph.Node target : view.graph().childrenOf(id)) {
            if (target.role() == WorldGraph.Role.SYNC && view.graph().isUnambiguous(target.id())) {
                entries.add(choice(target));
            }
        }
        showScreen(new WorldSelectionScreen(this, label("chooseTarget"), entries,
                current == null ? AUTOMATIC : current, Component.translatable("gui.done"), choice -> {
                    updateDefaultMirror(id, routeChoiceId(choice));
                    return null;
                }));
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

    private void chooseUpstream(WorldGraph.Node child) {
        var entries = new ArrayList<>(view.graph()
                        .nodes()
                        .values()
                        .stream()
                        .filter(node
                                -> node.kind() != WorldGraph.Kind.UNRESOLVED && !node.id().equals(child.id())
                                        && view.graph().isUnambiguous(node.id())
                                        && !view.graph().wouldCreateCycle(child.id(), node.id()))
                        .sorted(nodeOrder())
                        .map(this::choice)
                        .toList());
        var untracked = new HashMap<String, WorldGraph.Location>();
        for (WorldGraph.Location location : view.untrackedLocalWorlds()) {
            String key = WorldRelationshipLayout.untrackedKey(location);
            untracked.put(key, location);
            entries.add(WorldSelectionScreen.Choice.world(
                    key, Component.literal(location.folder()), location, WorldGraph.Kind.LOCAL));
        }
        showScreen(new WorldSelectionScreen(
                this, label("chooseUpstream"), entries, child.parentId(), label("chooseUpstream"), choice -> {
                    var local = untracked.get(choice.id());
                    worldEdit(() -> {
                        if (local == null)
                            service.setUpstream(child.id(), choice.id());
                        else
                            service.setUpstream(child.id(), local);
                    }, () -> showWorld(child.id()));
                    return null;
                }));
    }

    private WorldSelectionScreen.Choice choice(WorldGraph.Node node) {
        var name = Component.literal(displayName(node));
        if (node.location() != null)
            return WorldSelectionScreen.Choice.world(node.id(), name, node.location(), node.kind());
        var aliases = endpoints(node.id());
        String address = aliases.isEmpty() ? null : aliases.getFirst();
        var detail =
                address == null ? Component.empty() : label("serverLocation", String.join(", ", aliases));
        return WorldSelectionScreen.Choice.server(node.id(), name, detail, address);
    }

    private Row worldLocation(WorldGraph.Node node) {
        ModConfig.SaveLocation current = ModConfig.SaveLocation.valueOf(node.location().root().name());
        var choices =
                Arrays.stream(ModConfig.SaveLocation.values())
                        .map(location
                                -> new SelectionOption(WorldLabels.location(location), location == current,
                                        () -> {
                                            if (location != current)
                                                move(node, location);
                                        }))
                        .toList();
        return selection(selectionValue(WorldLabels.LOCATION, WorldLabels.location(current)), choices);
    }

    private void move(WorldGraph.Node node, ModConfig.SaveLocation location) {
        worldEdit(() -> service.moveWorld(node.id(), location), () -> showWorld(node.id()));
    }

    private void confirmDelete(WorldGraph.Node node) {
        showScreen(new ConfirmScreen(
                confirmed
                -> {
                    showScreen(this);
                    if (confirmed)
                        worldEdit(() -> service.deleteWorld(node.id(), node.location()), this::showWorlds);
                },
                Component.translatable("selectWorld.deleteQuestion"),
                Component.translatable("selectWorld.deleteWarning", displayName(node)),
                Component.translatable("selectWorld.deleteButton"), Component.translatable("gui.cancel")));
    }

    @FunctionalInterface
    private interface WorldEdit {
        void apply() throws Exception;
    }

    private void worldEdit(WorldEdit operation, Runnable completed) {
        run(() -> {
            operation.apply();
            return service.routingView();
        }, completed, true);
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
        entries.add(selection(label("policy", policyLabel(rule.idPolicy())), policyOptions(endpoint, rule)));
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
        chooseRoute("attachSource", remoteSources().stream().map(this::choice).toList(), null,
                source -> edit(config -> {
                    if (!config.nodes().containsKey(source))
                        throw problem("sourceUnavailable");
                    return config.withEndpointSource(endpoint, source);
                }, () -> showEndpoint(endpoint)));
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
        var entries = new ArrayList<WorldSelectionScreen.Choice>();
        entries.add(WorldSelectionScreen.Choice.action(AUTOMATIC, label("automatic")));
        rule.sourceIds().forEach(id -> entries.add(remoteChoice(id, endpoint)));
        chooseRoute("chooseSource", entries, Objects.requireNonNullElse(rule.defaultSourceId(), AUTOMATIC),
                source -> setEndpointDefault(endpoint, source));
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

    private List<SelectionOption> policyOptions(String endpoint, WorldGraphConfig.EndpointRule rule) {
        return Arrays.stream(WorldGraphConfig.IdPolicy.values())
                .map(value
                        -> new SelectionOption(policyLabel(value), value == rule.idPolicy(),
                                () -> setPolicy(endpoint, value)))
                .toList();
    }

    private void setPolicy(String endpoint, WorldGraphConfig.IdPolicy policy) {
        edit(config -> {
            var old = EndpointRoutingEdits.requireEndpoint(config, endpoint);
            return config.withEndpointRule(endpoint,
                    new WorldGraphConfig.EndpointRule(
                            old.addressGroupId(), old.sourceIds(), old.defaultSourceId(), policy));
        }, () -> showEndpoint(endpoint));
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
        var entries = view.config()
                              .endpointRules()
                              .entrySet()
                              .stream()
                              .filter(entry -> !entry.getValue().sourceIds().isEmpty())
                              .map(entry
                                      -> WorldSelectionScreen.Choice.server(entry.getKey(),
                                              Component.literal(entry.getKey()), entry.getKey()))
                              .toList();
        chooseRoute("endpoint", entries, null,
                endpoint -> chooseBindingSource(endpoint, view.config().endpointRules().get(endpoint)));
    }

    private void chooseBindingSource(String endpoint, WorldGraphConfig.EndpointRule rule) {
        var entries = rule.sourceIds().stream().map(id -> remoteChoice(id, endpoint)).toList();
        chooseRoute("chooseSource", entries, null, source -> beginBinding(endpoint, source));
    }

    private WorldSelectionScreen.Choice remoteChoice(String id, String endpoint) {
        return WorldSelectionScreen.Choice.server(
                id, Component.literal(displayName(id)), label("serverLocation", endpoint), endpoint);
    }

    private void chooseRoute(String title, List<WorldSelectionScreen.Choice> choices, String selected,
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

    private static Row row(Component title, Runnable action) {
        return new Row(title, null, action);
    }

    private static Row selection(Component title, List<SelectionOption> options) {
        return new Row(selectionLabel(title), title, null, options, null, false);
    }

    private static Row info(Component title) {
        return new Row(title, null, null, List.of(), null, true);
    }

    private static Row pair(Component left, Runnable action, Component right, Runnable other) {
        return withAction(row(left, action), right, other);
    }

    private static Row withAction(Row left, Component right, Runnable action) {
        return new Row(
                left.label(), left.detail(), left.action(), left.options(), row(right, action), left.text());
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
