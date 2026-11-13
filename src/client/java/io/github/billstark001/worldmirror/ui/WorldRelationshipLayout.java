package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Assigns world cards to depth columns and allocates a separate row to each leaf. */
final class WorldRelationshipLayout {
    static final int CARD_WIDTH = 142;
    static final int CARD_HEIGHT = 34;
    private static final int COLUMN_STEP = 188;
    private static final int ROW_STEP = 49;

    enum Warning { NONE, MISSING, CYCLE, AMBIGUOUS }

    record Card(String key, String name, WorldGraph.Node node, WorldGraph.Location untracked, Warning warning,
            double x, double y) {
        boolean contains(double px, double py) {
            return Math.abs(px - x) <= CARD_WIDTH / 2.0 && Math.abs(py - y) <= CARD_HEIGHT / 2.0;
        }
    }

    record Edge(Card parent, Card child, boolean cycle) {}

    record Result(Map<String, Card> byKey, List<Card> cards, List<Edge> edges,
            Map<String, List<Card>> children, double width, double height, double trackedHeight) {}

    private record World(
            String key, String name, WorldGraph.Node node, WorldGraph.Location untracked, Warning warning) {
        String parent() {
            return node == null ? null : node.parentId();
        }

        Card at(int depth, double row) {
            return new Card(key, name, node, untracked, warning, CARD_WIDTH / 2.0 + depth * COLUMN_STEP,
                    CARD_HEIGHT / 2.0 + row * ROW_STEP);
        }
    }

    private record Visit(World world, int depth, boolean expanded) {}

    private WorldRelationshipLayout() {}

    static Result create(WorldGraph.Snapshot graph, List<WorldGraph.Location> untracked,
            Function<WorldGraph.Node, String> displayName) {
        Map<String, World> worlds = new HashMap<>();
        graph.nodes().forEach((id, node) -> {
            Warning warning = Warning.NONE;
            if (graph.cyclicIds().contains(id))
                warning = Warning.CYCLE;
            else if (!graph.isUnambiguous(id))
                warning = Warning.AMBIGUOUS;
            else if (node.kind() == WorldGraph.Kind.UNRESOLVED)
                warning = Warning.MISSING;
            worlds.put(id, new World(id, displayName.apply(node), node, null, warning));
        });
        graph.missingParents().forEach(
                id -> worlds.putIfAbsent(id, new World(id, id, null, null, Warning.MISSING)));
        untracked.forEach(location -> {
            // IDs in a saved graph cannot contain control characters.
            String key = untrackedKey(location);
            worlds.put(key, new World(key, location.folder(), null, location, Warning.NONE));
        });
        Comparator<World> order = Comparator.comparing((World world) -> world.untracked() != null)
                                          .thenComparing(World::name, String.CASE_INSENSITIVE_ORDER)
                                          .thenComparing(World::name)
                                          .thenComparing(World::key);
        List<World> sorted = worlds.values().stream().sorted(order).toList();
        Set<String> brokenCycles = cycleRoots(graph, sorted);
        List<World> roots = new ArrayList<>();
        Map<String, List<World>> children = new HashMap<>();
        for (World world : sorted) {
            if (world.parent() == null || brokenCycles.contains(world.key()))
                roots.add(world);
            else
                children.computeIfAbsent(world.parent(), ignored -> new ArrayList<>()).add(world);
        }

        Map<String, Card> placed = new LinkedHashMap<>();
        int depth = 0;
        double row = 0;
        double trackedRows = 0;
        ArrayDeque<Visit> visits = new ArrayDeque<>();
        for (World root : roots) {
            visits.push(new Visit(root, 0, false));
            while (!visits.isEmpty()) {
                Visit visit = visits.pop();
                World world = visit.world();
                List<World> direct = children.getOrDefault(world.key(), List.of());
                if (direct.isEmpty()) {
                    placed.put(world.key(), world.at(visit.depth(), row++));
                } else if (visit.expanded()) {
                    Card first = placed.get(direct.getFirst().key());
                    Card last = placed.get(direct.getLast().key());
                    double midpoint = ((first.y() + last.y()) / 2 - CARD_HEIGHT / 2.0) / ROW_STEP;
                    placed.put(world.key(), world.at(visit.depth(), midpoint));
                } else {
                    visits.push(new Visit(world, visit.depth(), true));
                    for (int i = direct.size() - 1; i >= 0; i--)
                        visits.push(new Visit(direct.get(i), visit.depth() + 1, false));
                }
                depth = Math.max(depth, visit.depth());
            }
            if (root.untracked() == null)
                trackedRows = row;
            row += 0.5;
        }

        List<Card> cards = placed.values()
                                   .stream()
                                   .sorted(Comparator.comparingDouble(Card::y).thenComparingDouble(Card::x))
                                   .toList();
        List<Edge> edges = new ArrayList<>();
        for (World world : sorted) {
            if (world.parent() != null) {
                Card parent = placed.get(world.parent());
                Card child = placed.get(world.key());
                edges.add(new Edge(parent, child,
                        parent.warning() == Warning.CYCLE && child.warning() == Warning.CYCLE));
            }
        }
        Map<String, List<Card>> downstream = new HashMap<>();
        for (Edge edge : edges)
            downstream.computeIfAbsent(edge.parent().key(), ignored -> new ArrayList<>()).add(edge.child());
        downstream.replaceAll((key, value) -> List.copyOf(value));
        return new Result(Map.copyOf(placed), cards, List.copyOf(edges), Map.copyOf(downstream),
                depth * COLUMN_STEP + CARD_WIDTH, Math.max(0, row - 1.5) * ROW_STEP + CARD_HEIGHT,
                trackedRows == 0 ? 0 : (trackedRows - 1) * ROW_STEP + CARD_HEIGHT);
    }

    static String untrackedKey(WorldGraph.Location location) {
        return "\0" + location.root().name() + "/" + location.folder();
    }

    /** Keeps a displayable tree per cyclic component while retaining every actual edge for rendering. */
    private static Set<String> cycleRoots(WorldGraph.Snapshot graph, List<World> sorted) {
        Set<String> roots = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (World world : sorted) {
            String id = world.key();
            if (!graph.cyclicIds().contains(id) || !visited.add(id))
                continue;
            roots.add(id);
            String next = world.parent();
            while (visited.add(next)) next = graph.nodes().get(next).parentId();
        }
        return roots;
    }
}
