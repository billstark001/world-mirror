package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraphService.RoutingView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Looks up names and addresses from the same routing snapshot displayed by the editor. */
final class RoutingWorldPresentation {
    private final WorldGraph.Snapshot graph;
    private final Map<String, List<String>> addressesBySource;
    private final Map<String, String> remoteNames;

    RoutingWorldPresentation(RoutingView view) {
        graph = view.graph();
        Map<String, List<String>> addresses = new HashMap<>();
        view.config().endpointRules().forEach((address, rule) -> {
            for (String id : rule.sourceIds())
                addresses.computeIfAbsent(id, ignored -> new ArrayList<>()).add(address);
        });
        addresses.replaceAll((id, aliases) -> aliases.stream().sorted().toList());
        addressesBySource = Map.copyOf(addresses);

        Map<String, String> names = new HashMap<>();
        addressesBySource.forEach((id, aliases) -> names.put(id, aliases.getFirst() + " · " + id));
        remoteNames = Map.copyOf(names);
    }

    Comparator<WorldGraph.Node> nodeOrder() {
        return Comparator.comparing((WorldGraph.Node node) -> name(node)).thenComparing(WorldGraph.Node::id);
    }

    String name(String id) {
        WorldGraph.Node node = graph.nodes().get(id);
        return node == null ? id : name(node);
    }

    String name(WorldGraph.Node node) {
        if (node.location() != null)
            return node.location().folder();
        return node.kind() == WorldGraph.Kind.REMOTE ? remoteNames.getOrDefault(node.id(), node.id())
                                                     : node.id();
    }

    List<String> addresses(String id) {
        return addressesBySource.getOrDefault(id, List.of());
    }
}
