package io.github.billstark001.worldmirror.ui;

import static io.github.billstark001.worldmirror.ui.StatusScreenApi.showScreen;
import static io.github.billstark001.worldmirror.ui.WorldRoutingScreen.label;

import io.github.billstark001.worldmirror.download.LegacyLocalRouteAdoption;
import io.github.billstark001.worldmirror.download.LegacyLocalRouteAdoption.PendingRoute;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/** Lets the player explicitly adopt or discard unresolved routes from before stable local IDs. */
final class LegacyLocalRoutes {
    private record Source(WorldGraph.Location location, String id) {}

    private final WorldRoutingScreen screen;
    private final WorldGraphService service;

    LegacyLocalRoutes(WorldRoutingScreen screen, WorldGraphService service) {
        this.screen = screen;
        this.service = service;
    }

    void show() {
        var rows = routes().stream()
                           .map(route
                                   -> new RoutingRow(Component.literal(route.oldName()),
                                           route.destination() == null
                                                   ? null
                                                   : Component.literal(WorldLabels.path(route.destination())),
                                           () -> show(route, this::show)))
                           .toList();
        screen.show(label("legacyLocalRoutes"), rows, screen::showWorlds);
    }

    List<PendingRoute> routes() {
        return LegacyLocalRouteAdoption.pending(screen.view().config());
    }

    PendingRoute recordedAt(WorldGraph.Location location) {
        var candidates = routes().stream().filter(route -> location.equals(route.destination())).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    void confirmUpstream(PendingRoute route, WorldGraph.Location location, String id, Runnable completed) {
        confirm(route, new Source(location, id), completed);
    }

    void show(PendingRoute route, Runnable back) {
        var rows = new ArrayList<RoutingRow>();
        if (route.destination() != null)
            rows.add(RoutingRow.info(label("legacyLocalDestination", WorldLabels.path(route.destination()))));
        rows.add(RoutingRow.row(label("legacyLocalAdopt"), () -> choose(route, back)));
        rows.add(RoutingRow.row(label("legacyLocalDiscard"), () -> discard(route, back)));
        screen.show(label("legacyLocalRoute", route.oldName()), rows, back);
    }

    private void choose(PendingRoute route, Runnable back) {
        var view = screen.view();
        var sources = new HashMap<String, Source>();
        var choices = new ArrayList<WorldSelectionScreen.Choice>();
        view.graph()
                .nodes()
                .values()
                .stream()
                .filter(node -> node.kind() == WorldGraph.Kind.LOCAL)
                .filter(node
                        -> view.graph().hasUniqueLocation(node.id()) && view.graph().isUnambiguous(node.id()))
                .sorted(screen.worlds().nodeOrder())
                .forEach(node -> {
                    choices.add(screen.choice(node));
                    sources.put(node.id(), new Source(node.location(), node.id()));
                });
        for (var location : view.untrackedLocalWorlds()) {
            String id = WorldRelationshipLayout.untrackedKey(location);
            choices.add(WorldSelectionScreen.Choice.world(
                    id, Component.literal(location.folder()), location, WorldGraph.Kind.LOCAL));
            sources.put(id, new Source(location, null));
        }
        screen.chooseRoute("legacyLocalChoose", List.copyOf(choices), null, id -> {
            Source source = sources.get(id);
            if (source != null)
                confirm(route, source, back);
        });
    }

    private void confirm(PendingRoute route, Source source, Runnable back) {
        showScreen(new ConfirmScreen(
                confirmed
                -> {
                    showScreen(screen);
                    if (confirmed)
                        screen.worldEdit(
                                ()
                                        -> service.localRoutes().adopt(route, source.location(), source.id()),
                                back);
                },
                label("legacyLocalRoute", route.oldName()),
                label("legacyLocalConfirm", route.oldName(), WorldLabels.path(source.location())),
                label("legacyLocalAdopt"), Component.translatable("gui.cancel")));
    }

    private void discard(PendingRoute route, Runnable back) {
        showScreen(new ConfirmScreen(
                confirmed
                -> {
                    showScreen(screen);
                    if (confirmed)
                        screen.worldEdit(() -> service.localRoutes().discard(route), back);
                },
                label("legacyLocalRoute", route.oldName()),
                label("legacyLocalDiscardConfirm", route.oldName()), label("legacyLocalDiscard"),
                Component.translatable("gui.cancel")));
    }
}
