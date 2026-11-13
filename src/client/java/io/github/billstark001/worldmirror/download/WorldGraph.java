package io.github.billstark001.worldmirror.download;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Reconciles observations without modifying a world or assuming an absent parent was deleted. */
public final class WorldGraph {
    public enum Root { SAVES, DOWNLOADED }
    public enum Kind { LOCAL, MIRROR, SNAPSHOT, REMOTE, UNRESOLVED }
    public enum Role { SYNC, SNAPSHOT }

    private static final Comparator<Location> LOCATION_ORDER = Comparator.comparing(Location::root)
            .thenComparing(Location::folder, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Location::folder);
    private static final Comparator<Node> NODE_ORDER = Comparator.comparing(Node::id);

    private WorldGraph() {}

    public record Location(Root root, String folder) {
        public Location {
            Objects.requireNonNull(root);
            Objects.requireNonNull(folder);
            if (folder.isBlank() || folder.equals(".") || folder.equals("..")
                    || folder.indexOf('/') >= 0 || folder.indexOf('\\') >= 0 || folder.indexOf(':') >= 0
                    || folder.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Expected a single world folder segment: " + folder);
            }
        }
    }

    public record Node(String id, String parentId, Location location, Kind kind, Role role) {
        public Node {
            requireId(id);
            Objects.requireNonNull(kind);
            boolean fileBacked = kind == Kind.LOCAL || kind == Kind.MIRROR || kind == Kind.SNAPSHOT;
            if (fileBacked != (location != null)) {
                throw new IllegalArgumentException("World location does not match node kind: " + id);
            }
            if (kind == Kind.MIRROR) {
                requireId(parentId);
                Objects.requireNonNull(role);
            } else if (parentId != null || role != (kind == Kind.SNAPSHOT ? Role.SNAPSHOT : null)) {
                throw new IllegalArgumentException("Invalid parent or role for root node: " + id);
            }
        }
    }

    public record Duplicate(Node original, Node copy) {}

    public static final class Snapshot {
        private final Map<String, Node> nodes;
        private final Set<String> cyclicIds;
        private final Set<String> missingParents;
        private final List<Duplicate> duplicates;
        private final Set<String> ambiguousIds;
        private final Map<String, List<Node>> children;

        private Snapshot(Map<String, Node> nodes, Set<String> cyclicIds, Set<String> missingParents,
                         List<Duplicate> duplicates) {
            this.nodes = Map.copyOf(nodes);
            this.cyclicIds = Set.copyOf(cyclicIds);
            this.missingParents = Set.copyOf(missingParents);
            this.duplicates = List.copyOf(duplicates);
            Map<String, List<Node>> byParent = new HashMap<>();
            for (Node node : nodes.values()) {
                if (node.parentId() != null) byParent.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node);
            }
            byParent.replaceAll((parent, values) -> values.stream().sorted(NODE_ORDER).toList());
            this.children = Map.copyOf(byParent);
            this.ambiguousIds = ambiguousAncestry(children, cyclicIds, duplicates);
        }

        public Map<String, Node> nodes() { return nodes; }
        public Set<String> cyclicIds() { return cyclicIds; }
        public Set<String> missingParents() { return missingParents; }
        public List<Duplicate> duplicates() { return duplicates; }

        /** Includes standalone snapshots that have a lineage edge. */
        public List<Node> childrenOf(String parentId) {
            requireId(parentId);
            return children.getOrDefault(parentId, List.of());
        }

        /** A configured choice is retained while unavailable. The caller can offer a session override. */
        public Optional<Node> defaultMirror(String parentId, String configuredId) {
            if (!isUnambiguous(parentId)) return Optional.empty();
            return childrenOf(parentId).stream().filter(node -> node.role() == Role.SYNC)
                    .filter(node -> isUnambiguous(node.id()))
                    .filter(node -> configuredId == null || configuredId.equals(node.id())).findFirst();
        }

        public boolean isUnambiguous(String id) {
            return !ambiguousIds.contains(id);
        }

        public Optional<Node> findRoot(String id) {
            if (!isUnambiguous(id)) return Optional.empty();
            Node node = nodes.get(id);
            while (node != null) {
                if (node.parentId() == null) {
                    return node.kind() == Kind.UNRESOLVED ? Optional.empty() : Optional.of(node);
                }
                node = nodes.get(node.parentId());
            }
            return Optional.empty();
        }

        public boolean wouldCreateCycle(String childId, String parentId) {
            requireId(childId);
            requireId(parentId);
            Set<String> seen = new HashSet<>();
            String cursor = parentId;
            while (cursor != null && seen.add(cursor)) {
                if (childId.equals(cursor)) return true;
                Node parent = nodes.get(cursor);
                cursor = parent == null ? null : parent.parentId();
            }
            return false;
        }
    }

    private static Set<String> ambiguousAncestry(Map<String, List<Node>> children, Set<String> cycles, List<Duplicate> duplicates) {
        Set<String> ambiguous = new HashSet<>(cycles);
        for (Duplicate duplicate : duplicates) ambiguous.add(duplicate.original().id());
        if (ambiguous.isEmpty()) return Set.of();

        List<String> queue = new ArrayList<>(ambiguous);
        for (int i = 0; i < queue.size(); i++) {
            for (Node child : children.getOrDefault(queue.get(i), List.of())) {
                if (ambiguous.add(child.id())) queue.add(child.id());
            }
        }
        return Set.copyOf(ambiguous);
    }

    public static Snapshot reconcile(Collection<Node> observed, Map<String, Location> lastKnown) {
        Map<String, Set<Node>> observations = new TreeMap<>();
        for (Node node : observed) {
            observations.computeIfAbsent(node.id(), ignored -> new LinkedHashSet<>()).add(node);
        }

        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Duplicate> duplicates = new ArrayList<>();
        observations.forEach((id, copies) -> {
            List<Node> ordered = copies.stream().sorted(Comparator
                    .comparing((Node node) -> !Objects.equals(node.location(), lastKnown.get(id)))
                    .thenComparing(Node::location, Comparator.nullsLast(LOCATION_ORDER))
                    .thenComparing(Node::kind).thenComparing(Node::role, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Node::parentId, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
            Node original = ordered.getFirst();
            nodes.put(id, original);
            ordered.stream().skip(1).forEach(copy -> duplicates.add(new Duplicate(original, copy)));
        });

        Set<String> missingParents = new HashSet<>();
        for (Node node : nodes.values()) {
            if (node.parentId() == null) continue;
            Node parent = nodes.get(node.parentId());
            if (parent == null || parent.kind() == Kind.UNRESOLVED) missingParents.add(node.parentId());
        }
        return new Snapshot(nodes, findCycles(nodes), missingParents, duplicates);
    }

    /** Each node has at most one outgoing edge. Track the current walk iteratively. */
    private static Set<String> findCycles(Map<String, Node> nodes) {
        Set<String> checked = new HashSet<>();
        Set<String> cycles = new HashSet<>();
        for (String start : nodes.keySet()) {
            if (checked.contains(start)) continue;
            Map<String, Integer> walk = new HashMap<>();
            List<String> path = new ArrayList<>();
            String cursor = start;
            while (cursor != null && !checked.contains(cursor)) {
                Node node = nodes.get(cursor);
                if (node == null) break;
                Integer cycleStart = walk.putIfAbsent(cursor, path.size());
                if (cycleStart != null) {
                    cycles.addAll(path.subList(cycleStart, path.size()));
                    break;
                }
                path.add(cursor);
                cursor = node.parentId();
            }
            checked.addAll(path);
        }
        return cycles;
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Missing or invalid world node ID");
        }
    }

    static boolean isNodeId(String id) {
        if (id == null || id.chars().anyMatch(Character::isISOControl)) return false;
        int delimiter = id.indexOf(':');
        if (delimiter < 0 || id.substring(delimiter + 1).isBlank()) return false;
        return switch (id.substring(0, delimiter)) {
            case "local", "mirror", "snapshot", "remote", "unresolved" -> true;
            default -> false;
        };
    }
}
