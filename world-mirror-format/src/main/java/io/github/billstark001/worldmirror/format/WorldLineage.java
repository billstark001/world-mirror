package io.github.billstark001.worldmirror.format;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** The typed part of the world graph shared by clients and offline tools. */
public final class WorldLineage {
    public enum Kind { LOCAL, MIRROR, SNAPSHOT, REMOTE, UNRESOLVED }

    public enum State { ROOT, MISSING, UNRESOLVED, INVALID, CYCLE }

    public record Link(String id, Kind kind, String parentId) {}

    @FunctionalInterface
    public interface Lookup<E extends Exception> {
        Link find(String id) throws E;
    }

    public record Trace(State state, List<Link> links, String stopId) {
        public Trace {
            links = List.copyOf(links);
        }

        public Link root() {
            return state == State.ROOT ? links.getLast() : null;
        }
    }

    private WorldLineage() {}

    public static Kind kindOfId(String id) {
        if (id == null || id.chars().anyMatch(Character::isISOControl))
            return null;
        int delimiter = id.indexOf(':');
        if (delimiter < 0 || id.substring(delimiter + 1).isBlank())
            return null;
        return switch (id.substring(0, delimiter)) {
            case "local" -> Kind.LOCAL;
            case "mirror" -> Kind.MIRROR;
            case "snapshot" -> Kind.SNAPSHOT;
            case "remote" -> Kind.REMOTE;
            case "unresolved" -> Kind.UNRESOLVED;
            default -> null;
        };
    }

    public static boolean allowsParent(Kind kind) {
        return kind == Kind.MIRROR || kind == Kind.SNAPSHOT;
    }

    /** Follow links from {@code startId}. Use {@code descendantId} when the first edge comes from disk. */
    public static <E extends Exception> Trace trace(String descendantId, String startId, Lookup<E> lookup)
            throws E {
        var links = new ArrayList<Link>();
        var seen = new HashSet<String>();
        if (descendantId != null)
            seen.add(descendantId);
        String id = startId;
        while (id != null) {
            Kind expected = kindOfId(id);
            if (expected == null)
                return new Trace(State.INVALID, links, id);
            if (!seen.add(id))
                return new Trace(State.CYCLE, links, id);
            Link link = lookup.find(id);
            if (link == null) {
                State missing = expected == Kind.UNRESOLVED ? State.UNRESOLVED : State.MISSING;
                return new Trace(missing, links, id);
            }
            if (!id.equals(link.id()) || link.kind() != expected
                    || link.parentId() != null && !allowsParent(expected)) {
                return new Trace(State.INVALID, links, id);
            }
            if (expected == Kind.UNRESOLVED)
                return new Trace(State.UNRESOLVED, links, id);
            links.add(link);
            id = link.parentId();
        }
        return new Trace(links.isEmpty() ? State.MISSING : State.ROOT, links, null);
    }
}
