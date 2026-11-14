package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.ui.WorldRelationshipLayout.Card;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/** Display names and details for relationship cards and their tooltips. */
final class WorldRelationshipLabels {
    private WorldRelationshipLabels() {}

    static List<Component> tooltip(
            WorldGraphService.RoutingView view, WorldRelationshipLayout.Result layout, Card card) {
        List<Component> details = new ArrayList<>();
        details.add(Component.literal(card.name()));
        details.add(category(view, card));
        var direct = layout.children().getOrDefault(card.key(), List.of());
        var node = card.node();
        if (node != null && node.parentId() != null) {
            details.add(label("upstream", layout.byKey().get(node.parentId()).name()));
        } else if (card.untracked() != null || node != null && node.location() != null) {
            WorldGraph.Location location = node == null ? card.untracked() : node.location();
            details.add(label("location", WorldLabels.path(location)));
        }
        var count = label("graphChildren", direct.size());
        if (node != null) {
            var selection = view.targetSelection(node.id());
            if (selection.target() != null) {
                String name = layout.byKey().get(selection.target().nodeId()).name();
                count.append(" · ").append(label("targetDefault",
                        selection.requestedMirrorId() == null ? label("automaticTarget", name) : name));
            } else if (selection.requestedMirrorId() != null) {
                String id = selection.requestedMirrorId();
                Card requested = layout.byKey().get(id);
                count.append(" · ").append(label("targetDefault",
                        label("unavailableTarget", requested == null ? id : requested.name())));
            }
        }
        details.add(count);
        if (node != null)
            details.add(label("node", node.id()));
        return details;
    }

    static Component category(WorldGraphService.RoutingView view, Card card) {
        Component category = switch (card.warning()) {
            case MISSING -> label("graphMissing");
            case CYCLE -> label("graphCycle");
            case AMBIGUOUS -> label("ambiguous");
            case NONE ->
                card.untracked() != null
                        ? label(view.orphanedMirrors().contains(card.untracked()) ? "graphRecoverableMirror"
                                                                                  : "graphUntracked")
                        : card.node().kind() == WorldGraph.Kind.MIRROR&& card.node().role()
                                == WorldGraph.Role.SNAPSHOT
                        ? label("graphLinkedSnapshot")
                        : WorldLabels.kind(card.node().kind());
        };
        var location = card.node() == null ? card.untracked() : card.node().location();
        return location != null && location.root() == WorldGraph.Root.DOWNLOADED
                ? label("graphDownloaded", category)
                : category;
    }

    static MutableComponent label(String key, Object... args) {
        return Component.translatable("screen.worldmirror.routing." + key, args);
    }
}
