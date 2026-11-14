package io.github.billstark001.worldmirror.ui;

import static io.github.billstark001.worldmirror.ui.RoutingRow.info;
import static io.github.billstark001.worldmirror.ui.RoutingRow.pair;
import static io.github.billstark001.worldmirror.ui.RoutingRow.row;
import static io.github.billstark001.worldmirror.ui.RoutingRow.selection;
import static io.github.billstark001.worldmirror.ui.StatusScreenApi.showScreen;
import static io.github.billstark001.worldmirror.ui.WorldMirrorTabbedScreen.selectionValue;
import static io.github.billstark001.worldmirror.ui.WorldRoutingScreen.AUTOMATIC;
import static io.github.billstark001.worldmirror.ui.WorldRoutingScreen.label;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.ui.WorldMirrorTabbedScreen.SelectionOption;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Edits the selected world's relationships and storage. */
final class WorldRouteDetails {
    private final WorldRoutingScreen screen;
    private final WorldGraphService service;

    WorldRouteDetails(WorldRoutingScreen screen, WorldGraphService service) {
        this.screen = screen;
        this.service = service;
    }

    void show(String id) {
        var view = screen.view();
        WorldGraph.Node node = view.graph().nodes().get(id);
        if (node == null) {
            for (var location : view.orphanedMirrors()) {
                if (WorldRelationshipLayout.untrackedKey(location).equals(id)) {
                    showScreen(new MirrorSeedScreen(screen, null, service.worlds().path(location),
                            () -> screen.refresh(screen::showWorlds)));
                    return;
                }
            }
            screen.showWorlds();
            return;
        }
        List<RoutingRow> entries = new ArrayList<>();
        entries.add(info(label("node", id)));
        entries.add(info(label("kind", WorldLabels.kind(node.kind()))));
        if (node.location() != null)
            entries.add(info(label("location", WorldLabels.path(node.location()))));
        boolean unambiguous = view.graph().isUnambiguous(id);
        boolean managed = node.kind() == WorldGraph.Kind.MIRROR || node.kind() == WorldGraph.Kind.SNAPSHOT;
        boolean editable = managed && view.graph().hasUniqueLocation(id);
        if (managed) {
            entries.add(info(label("upstream",
                    node.parentId() == null ? label("upstreamNone") : screen.displayName(node.parentId()))));
        }
        if (!unambiguous)
            entries.add(info(label("ambiguous")));
        if (editable) {
            entries.add(pair(label("chooseUpstream"),
                    ()
                            -> chooseUpstream(node),
                    label("removeUpstream"),
                    node.parentId() == null ? null : ()
                            -> screen.worldEdit(() -> service.worlds().removeUpstream(id), () -> show(id))));
        }
        var settings = view.config().sourceSettings().get(id);
        String selected = settings == null           ? null
                : settings.defaultMirrorId() == null ? settings.inheritedMirrorId()
                                                     : settings.defaultMirrorId();
        if (selected != null || !view.graph().childrenOf(id).isEmpty()) {
            String defaultId = settings == null ? null : settings.defaultMirrorId();
            entries.add(row(label("targetDefault",
                                    selected == null ? label("automatic") : screen.displayName(selected)),
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
            var folder = service.worlds().path(node.location());
            entries.add(worldLocation(node));
            entries.add(pair(Component.translatable("screen.worldmirror.seed.title"),
                    ()
                            -> showScreen(new MirrorSeedScreen(screen, id, folder)),
                    Component.translatable("selectWorld.delete"), () -> confirmDelete(node)));
        } else if (node.kind() == WorldGraph.Kind.LOCAL && view.graph().hasUniqueLocation(id)) {
            entries.add(row(Component.translatable("selectWorld.delete"), () -> confirmDelete(node)));
        }
        screen.show(Component.literal(screen.displayName(node)), entries, screen::showWorlds);
    }

    private void chooseDefaultMirror(String id, String current) {
        List<WorldSelectionScreen.Choice> entries = new ArrayList<>();
        entries.add(WorldSelectionScreen.Choice.action(AUTOMATIC, label("automatic")));
        var view = screen.view();
        for (WorldGraph.Node target : view.graph().childrenOf(id)) {
            if (target.role() == WorldGraph.Role.SYNC && view.graph().isUnambiguous(target.id())) {
                entries.add(screen.choice(target));
            }
        }
        screen.chooseRoute("chooseTarget", entries, current == null ? AUTOMATIC : current,
                target -> updateDefaultMirror(id, target));
    }

    private void updateDefaultMirror(String id, String target) {
        screen.edit(config -> {
            var old = config.sourceSettings().get(id);
            if (old == null)
                old = new WorldGraphConfig.SourceSettings(null, null, null, null, null);
            return config.withSourceSettings(id, old.withDefaultMirror(target));
        }, () -> show(id));
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
        screen.edit(config
                -> config.withMirrorSettings(
                        id, value == null ? null : new WorldGraphConfig.MirrorSettings(value)),
                () -> show(id));
    }

    private void chooseUpstream(WorldGraph.Node child) {
        var view = screen.view();
        var entries = new ArrayList<>(view.graph()
                        .nodes()
                        .values()
                        .stream()
                        .filter(node
                                -> node.kind() != WorldGraph.Kind.UNRESOLVED && !node.id().equals(child.id())
                                        && view.graph().isUnambiguous(node.id())
                                        && !view.graph().wouldCreateCycle(child.id(), node.id()))
                        .sorted(screen.nodeOrder())
                        .map(screen::choice)
                        .toList());
        var untracked = new HashMap<String, WorldGraph.Location>();
        for (WorldGraph.Location location : view.untrackedLocalWorlds()) {
            String key = WorldRelationshipLayout.untrackedKey(location);
            untracked.put(key, location);
            entries.add(WorldSelectionScreen.Choice.world(
                    key, Component.literal(location.folder()), location, WorldGraph.Kind.LOCAL));
        }
        showScreen(new WorldSelectionScreen(screen, label("chooseUpstream"), entries, child.parentId(),
                label("chooseUpstream"), choice -> {
                    var local = untracked.get(choice.id());
                    screen.worldEdit(() -> {
                        if (local == null)
                            service.worlds().setUpstream(child.id(), choice.id());
                        else
                            service.worlds().setUpstream(child.id(), local);
                    }, () -> show(child.id()));
                    return null;
                }));
    }

    private RoutingRow worldLocation(WorldGraph.Node node) {
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
        screen.worldEdit(() -> service.worlds().moveWorld(node.id(), location), () -> show(node.id()));
    }

    private void confirmDelete(WorldGraph.Node node) {
        showScreen(new ConfirmScreen(
                confirmed
                -> {
                    showScreen(screen);
                    if (confirmed)
                        screen.worldEdit(()
                                                 -> service.worlds().deleteWorld(node.id(), node.location()),
                                screen::showWorlds);
                },
                Component.translatable("selectWorld.deleteQuestion"),
                Component.translatable("selectWorld.deleteWarning", screen.displayName(node)),
                Component.translatable("selectWorld.deleteButton"), Component.translatable("gui.cancel")));
    }

    private static Component conflictLabel(ModConfig.ConflictStrategy value) {
        return value == null ? label("inherit") : WorldLabels.conflictStrategy(value);
    }
}
