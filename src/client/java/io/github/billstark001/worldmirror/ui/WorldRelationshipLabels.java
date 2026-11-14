package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.ui.WorldRelationshipLayout.Card;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        var settings = view.config().sourceSettings().get(card.key());
        String preferred = settings == null          ? null
                : settings.defaultMirrorId() == null ? settings.inheritedMirrorId()
                                                     : settings.defaultMirrorId();
        if (preferred != null
                || direct.stream().anyMatch(child -> child.node().role() == WorldGraph.Role.SYNC)) {
            var target = view.graph().defaultMirror(card.key(), preferred).orElse(null);
            Card targetCard = target == null ? layout.byKey().get(Objects.requireNonNullElse(preferred, ""))
                                             : layout.byKey().get(target.id());
            if (targetCard != null || preferred != null) {
                count.append(" · ").append(
                        label("targetDefault", targetCard == null ? preferred : targetCard.name()));
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
