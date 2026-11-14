package io.github.billstark001.worldmirror.ui;

import static io.github.billstark001.worldmirror.ui.RoutingRow.info;
import static io.github.billstark001.worldmirror.ui.RoutingRow.row;
import static io.github.billstark001.worldmirror.ui.RoutingRow.selection;
import static io.github.billstark001.worldmirror.ui.StatusScreenApi.showScreen;
import static io.github.billstark001.worldmirror.ui.WorldRoutingScreen.AUTOMATIC;
import static io.github.billstark001.worldmirror.ui.WorldRoutingScreen.label;

import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.ui.WorldMirrorTabbedScreen.SelectionOption;
import io.github.billstark001.worldmirror.xaero.XaeroWorldIdentity;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Edits server addresses, source assignments, and advertised stable ID bindings. */
final class ServerRouteEditor {
    private final WorldRoutingScreen screen;

    ServerRouteEditor(WorldRoutingScreen screen) {
        this.screen = screen;
    }

    void showEndpoints() {
        var view = screen.view();
        if (view == null)
            return;
        List<RoutingRow> entries = new ArrayList<>();
        entries.add(row(label("addEndpoint"),
                ()
                        -> input(label("endpoint"), label("endpointHint"), "",
                                entered
                                -> chooseEndpointSource(WorldGraphConfig.normalizeEndpoint(entered)))));
        view.config().endpointRules().keySet().stream().sorted().forEach(
                endpoint -> entries.add(row(Component.literal(endpoint), () -> showEndpoint(endpoint))));
        screen.show(label("endpoints"), entries, null);
    }

    private void showEndpoint(String endpoint) {
        var view = screen.view();
        var rule = view.config().endpointRules().get(endpoint);
        if (rule == null) {
            showEndpoints();
            return;
        }
        List<RoutingRow> entries = new ArrayList<>();
        entries.add(info(label("addressGroup", rule.addressGroupId())));
        entries.add(row(label("addAlias"),
                ()
                        -> input(label("addAlias"), label("endpointHint"), "",
                                entered -> addAlias(endpoint, WorldGraphConfig.normalizeEndpoint(entered)))));
        entries.add(row(label("attachSource"), () -> chooseEndpointSource(endpoint)));
        entries.add(row(label("sourceDefault",
                                rule.defaultSourceId() == null ? label("automatic")
                                                               : screen.displayName(rule.defaultSourceId())),
                () -> chooseEndpointDefault(endpoint, rule)));
        entries.add(selection(label("policy", policyLabel(rule.idPolicy())), policyOptions(endpoint, rule)));
        for (String id : rule.sourceIds()) {
            entries.add(new RoutingRow(Component.literal(screen.displayName(id)), label("node", id),
                    () -> showEndpointSource(endpoint, id)));
        }
        entries.add(row(label("removeEndpoint"), () -> confirmRemoveEndpoint(endpoint)));
        screen.show(Component.literal(endpoint), entries, this::showEndpoints);
    }

    private void showEndpointSource(String endpoint, String sourceId) {
        screen.show(Component.literal(screen.displayName(sourceId)),
                List.of(info(label("node", sourceId)),
                        row(label("detachSource"),
                                ()
                                        -> screen.edit(config
                                                -> EndpointRoutingEdits.detach(config, endpoint, sourceId),
                                                () -> showEndpoint(endpoint)))),
                () -> showEndpoint(endpoint));
    }

    private void chooseEndpointSource(String endpoint) {
        screen.chooseRoute("attachSource", remoteSources().stream().map(screen::choice).toList(), null,
                source -> screen.edit(config -> {
                    if (!config.nodes().containsKey(source))
                        throw problem("sourceUnavailable");
                    return config.withEndpointSource(endpoint, source);
                }, () -> showEndpoint(endpoint)));
    }

    private void addAlias(String endpoint, String alias) {
        screen.edit(
                config -> EndpointRoutingEdits.addAlias(config, endpoint, alias), () -> showEndpoint(alias));
    }

    private void confirmRemoveEndpoint(String endpoint) {
        screen.show(label("removeEndpoint"),
                List.of(info(Component.literal(endpoint)),
                        row(Component.translatable("gui.done"),
                                ()
                                        -> screen.edit(config
                                                -> EndpointRoutingEdits.remove(config, endpoint),
                                                this::showEndpoints))),
                () -> showEndpoint(endpoint));
    }

    private void chooseEndpointDefault(String endpoint, WorldGraphConfig.EndpointRule rule) {
        var entries = new ArrayList<WorldSelectionScreen.Choice>();
        entries.add(WorldSelectionScreen.Choice.action(AUTOMATIC, label("automatic")));
        rule.sourceIds().forEach(id -> entries.add(remoteChoice(id, endpoint)));
        screen.chooseRoute("chooseSource", entries,
                Objects.requireNonNullElse(rule.defaultSourceId(), AUTOMATIC),
                source -> setEndpointDefault(endpoint, source));
    }

    private void setEndpointDefault(String endpoint, String sourceId) {
        screen.edit(config -> {
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
        screen.edit(config -> {
            var old = EndpointRoutingEdits.requireEndpoint(config, endpoint);
            return config.withEndpointRule(endpoint,
                    new WorldGraphConfig.EndpointRule(
                            old.addressGroupId(), old.sourceIds(), old.defaultSourceId(), policy));
        }, () -> showEndpoint(endpoint));
    }

    void showBindings() {
        var view = screen.view();
        if (view == null)
            return;
        List<RoutingRow> entries = new ArrayList<>();
        if (!view.config().endpointRules().isEmpty()) {
            entries.add(row(label("addBinding"), this::chooseBindingEndpoint));
        }
        for (WorldGraphConfig.StableBinding binding : view.config().stableBindings()) {
            entries.add(new RoutingRow(label("binding", binding.provider(), binding.advertisedId()),
                    label("node", binding.sourceId()), () -> showBinding(binding)));
        }
        screen.show(label("stableIds"), entries, null);
    }

    private void showBinding(WorldGraphConfig.StableBinding binding) {
        screen.show(label("stableIds"),
                List.of(info(label("binding", binding.provider(), binding.advertisedId())),
                        info(label("node", binding.sourceId())),
                        row(label("removeBinding"),
                                ()
                                        -> screen.edit(config
                                                -> config.withoutStableBinding(binding.provider(),
                                                        binding.addressGroupId(), binding.advertisedId()),
                                                this::showBindings))),
                this::showBindings);
    }

    private void chooseBindingEndpoint() {
        var view = screen.view();
        var entries = view.config()
                              .endpointRules()
                              .entrySet()
                              .stream()
                              .filter(entry -> !entry.getValue().sourceIds().isEmpty())
                              .map(entry
                                      -> WorldSelectionScreen.Choice.server(entry.getKey(),
                                              Component.literal(entry.getKey()), entry.getKey()))
                              .toList();
        screen.chooseRoute("endpoint", entries, null,
                endpoint
                -> chooseBindingSource(endpoint, screen.view().config().endpointRules().get(endpoint)));
    }

    private void chooseBindingSource(String endpoint, WorldGraphConfig.EndpointRule rule) {
        var entries = rule.sourceIds().stream().map(id -> remoteChoice(id, endpoint)).toList();
        screen.chooseRoute("chooseSource", entries, null, source -> beginBinding(endpoint, source));
    }

    private WorldSelectionScreen.Choice remoteChoice(String id, String endpoint) {
        return WorldSelectionScreen.Choice.server(
                id, Component.literal(screen.displayName(id)), label("serverLocation", endpoint), endpoint);
    }

    private void beginBinding(String endpoint, String source) {
        input(label("bindingProvider"), label("bindingProvider"), XaeroWorldIdentity.PROVIDER,
                provider
                -> input(label("bindingId"), label("bindingId"), "",
                        id -> addBinding(endpoint, source, provider, id)));
    }

    private void addBinding(String endpoint, String source, String provider, String id) {
        screen.edit(config -> {
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
        return screen.view()
                .graph()
                .nodes()
                .values()
                .stream()
                .filter(node -> node.kind() == WorldGraph.Kind.REMOTE)
                .sorted(screen.nodeOrder())
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
                                  showScreen(screen);
                                  try {
                                      accept.accept(field.getValue().strip());
                                  } catch (IllegalArgumentException invalid) {
                                      screen.invalidInput();
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
                showScreen(screen);
            }
        });
    }

    private static EndpointRoutingEdits.InvalidEdit problem(String key) {
        return new EndpointRoutingEdits.InvalidEdit(key);
    }

    private static Component policyLabel(WorldGraphConfig.IdPolicy value) {
        return label("policy." + value.name().toLowerCase(Locale.ROOT));
    }
}
