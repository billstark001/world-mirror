package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Role;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphService.RoutingView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingWorldPresentationTest {
    @Test
    void listsSortedAliasesForEverySourceAndRebuildsFromTheNewSnapshot() {
        var first = node("remote:first", null, Kind.REMOTE);
        var second = node("remote:second", null, Kind.REMOTE);
        var unbound = node("remote:unbound", null, Kind.REMOTE);
        var graph = WorldGraph.reconcile(List.of(first, second, unbound), Map.of());
        var both = new EndpointRule(
                "endpoint:shared", Set.of(first.id(), second.id()), second.id(), IdPolicy.PREFER_BOUND_ID);
        var onlyFirst = new EndpointRule("endpoint:shared", Set.of(first.id()), null, IdPolicy.ENDPOINT_ONLY);
        var config = Snapshot.empty().withEndpointRule("z.test", both).withEndpointRule("a.test", onlyFirst);
        var old =
                new RoutingWorldPresentation(new RoutingView(graph, config, List.of(), List.of(), Map.of()));

        assertEquals(List.of("a.test", "z.test"), old.addresses(first.id()));
        assertEquals("a.test · remote:first", old.name(first));
        assertEquals("z.test · remote:second", old.name(second));
        assertEquals(unbound.id(), old.name(unbound));
        assertEquals(List.of("z.test"), old.addresses(second.id()));

        var updated = new RoutingWorldPresentation(new RoutingView(
                graph, config.withEndpointRule("a.test", null), List.of(), List.of(), Map.of()));
        assertEquals("z.test · remote:first", updated.name(first));
        assertEquals("a.test · remote:first", old.name(first));
    }

    @Test
    void upstreamCandidatesExcludeDescendantsOfAWorldInACycleButPermitRepair() {
        var remote = node("remote:valid", null, Kind.REMOTE);
        var unresolved = node("unresolved:gone", null, Kind.UNRESOLVED);
        var first = node("mirror:first", "mirror:second", Kind.MIRROR);
        var second = node("mirror:second", first.id(), Kind.MIRROR);
        var descendant = node("mirror:descendant", second.id(), Kind.MIRROR);
        var graph = WorldGraph.reconcile(List.of(remote, unresolved, first, second, descendant), Map.of());

        assertEquals(List.of(remote), WorldRouteDetails.upstreamCandidates(graph, first.id()));
    }

    @Test
    void upstreamCandidatesKeepAncestorsAndPeersButExcludeAnEntireLongBranch() {
        var remote = node("remote:valid", null, Kind.REMOTE);
        var peer = node("mirror:peer", remote.id(), Kind.MIRROR);
        var root = node("mirror:root", remote.id(), Kind.MIRROR);
        var nodes = new ArrayList<>(List.of(remote, peer, root));
        var parent = root;
        for (int i = 0; i < 5_000; i++) {
            parent = node("mirror:" + i, parent.id(), Kind.MIRROR);
            nodes.add(parent);
        }
        var graph = WorldGraph.reconcile(nodes, Map.of());

        assertEquals(
                Set.of(remote, peer), Set.copyOf(WorldRouteDetails.upstreamCandidates(graph, root.id())));
    }

    private static Node node(String id, String upstream, Kind kind) {
        return kind == Kind.MIRROR
                ? new Node(id, upstream, new Location(Root.SAVES, id.substring(7)), kind, Role.SYNC)
                : new Node(id, null, null, kind, null);
    }
}
