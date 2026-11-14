package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.billstark001.worldmirror.download.WorldGraph.Kind.MIRROR;
import static io.github.billstark001.worldmirror.download.WorldGraph.Kind.REMOTE;
import static io.github.billstark001.worldmirror.download.WorldGraph.Role.SNAPSHOT;
import static io.github.billstark001.worldmirror.download.WorldGraph.Role.SYNC;
import static io.github.billstark001.worldmirror.download.WorldGraph.Root.DOWNLOADED;
import static io.github.billstark001.worldmirror.download.WorldGraph.Root.SAVES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGraphTest {
    private static final String REMOTE_ID = "remote:original";

    @Test
    void duplicateSelectionHonorsKnownLocationAndUsesDeterministicFallback() {
        var saved = mirror("mirror:a", REMOTE_ID, SAVES, "Beta");
        var downloaded = mirror("mirror:a", REMOTE_ID, DOWNLOADED, "Alpha");
        var child = mirror("mirror:child", saved.id(), SAVES, "Child");
        var grandchild = mirror("mirror:grandchild", child.id(), SAVES, "Grandchild");
        var withoutHint = WorldGraph.reconcile(List.of(downloaded, saved, child, grandchild), Map.of());
        var withHint = WorldGraph.reconcile(
                List.of(child, saved, downloaded, grandchild), Map.of(saved.id(), downloaded.location()));

        assertEquals(saved, withoutHint.nodes().get(saved.id()));
        assertEquals(downloaded, withHint.nodes().get(saved.id()));
        assertEquals(List.of(new WorldGraph.Duplicate(downloaded, saved)), withHint.duplicates());
        assertFalse(withHint.hasUniqueLocation(saved.id()));
        assertTrue(withHint.hasDuplicateAt(saved.location()));
        assertTrue(withHint.hasDuplicateAt(downloaded.location()));
        assertTrue(withHint.hasUniqueLocation(child.id()));
        assertFalse(withHint.hasDuplicateAt(child.location()));
        assertEquals(List.of(child), withHint.childrenOf(saved.id()));
        assertTrue(withHint.defaultMirror(saved.id(), null).isEmpty());
        assertTrue(withHint.defaultMirror(child.id(), null).isEmpty());

        var moved = WorldGraph.reconcile(List.of(saved, child), Map.of(saved.id(), downloaded.location()));
        assertTrue(moved.duplicates().isEmpty());
        assertTrue(moved.hasUniqueLocation(saved.id()));
        assertFalse(moved.hasDuplicateAt(saved.location()));
        assertEquals(child, moved.defaultMirror(saved.id(), null).orElseThrow());
    }

    @Test
    void retainsMissingEdgesAndResolvesThemOnTheNextObservation() {
        var parent = mirror("mirror:parent", REMOTE_ID, DOWNLOADED, "Parent");
        var child = mirror("mirror:child", parent.id(), SAVES, "Child");
        var descendant = mirror("mirror:descendant", child.id(), SAVES, "Descendant");
        var absent = WorldGraph.reconcile(List.of(child, descendant, remote()), Map.of());
        assertEquals(Set.of(parent.id()), absent.missingParents());
        assertEquals(parent.id(), absent.nodes().get(child.id()).parentId());
        assertTrue(absent.findRoot(child.id()).isEmpty());
        assertEquals(descendant, absent.defaultMirror(child.id(), null).orElseThrow());

        var present = WorldGraph.reconcile(List.of(parent, child, remote()), Map.of());
        assertTrue(present.missingParents().isEmpty());
        assertEquals(remote(), present.findRoot(child.id()).orElseThrow());
    }

    @Test
    void reportsCycleMembersWithoutDeletingTheirEdges() {
        var first = mirror("mirror:a", "mirror:b", SAVES, "First");
        var second = mirror("mirror:b", first.id(), SAVES, "Second");
        var dependent = mirror("mirror:c", second.id(), SAVES, "Dependent");
        var descendant = mirror("mirror:descendant", dependent.id(), SAVES, "Descendant");
        var self = mirror("mirror:self", "mirror:self", SAVES, "Self");
        var valid = mirror("mirror:valid", REMOTE_ID, SAVES, "Valid");
        var graph = WorldGraph.reconcile(
                List.of(descendant, dependent, first, valid, second, self, remote()), Map.of());

        assertEquals(Set.of(first.id(), second.id(), self.id()), graph.cyclicIds());
        assertEquals(first.id(), graph.nodes().get(second.id()).parentId());
        assertTrue(graph.findRoot(dependent.id()).isEmpty());
        assertTrue(graph.defaultMirror(dependent.id(), null).isEmpty());
        assertTrue(graph.wouldCreateCycle(first.id(), dependent.id()));
        assertFalse(graph.wouldCreateCycle(valid.id(), dependent.id()));
        assertEquals(valid, graph.defaultMirror(REMOTE_ID, null).orElseThrow());
    }

    @Test
    void resolvesARemoteRootThroughBothMirrorAndSnapshotNodes() {
        var snapshot = new WorldGraph.Node("snapshot:intermediate", REMOTE_ID,
                new WorldGraph.Location(SAVES, "Snapshot"), WorldGraph.Kind.SNAPSHOT,
                WorldGraph.Role.SNAPSHOT);
        var child = mirror("mirror:child", snapshot.id(), SAVES, "Child");
        var graph = WorldGraph.reconcile(List.of(child, snapshot, remote()), Map.of());

        assertEquals(remote(), graph.findRoot(child.id()).orElseThrow());
        assertEquals(List.of(child), graph.childrenOf(snapshot.id()));
    }

    @Test
    void choosesOnlyDirectSynchronizedChildrenAndRetainsAnUnavailableDefault() {
        var first = mirror("mirror:a", REMOTE_ID, SAVES, "Z");
        var second = mirror("mirror:b", REMOTE_ID, SAVES, "A");
        var grandchild = mirror("mirror:1", first.id(), SAVES, "Grandchild");
        var snapshot = new WorldGraph.Node(
                "mirror:0", REMOTE_ID, new WorldGraph.Location(SAVES, "Snapshot"), MIRROR, SNAPSHOT);
        var graph = WorldGraph.reconcile(List.of(second, grandchild, first, snapshot, remote()), Map.of());

        assertEquals(first, graph.defaultMirror(REMOTE_ID, null).orElseThrow());
        assertEquals(second, graph.defaultMirror(REMOTE_ID, second.id()).orElseThrow());
        assertTrue(graph.defaultMirror(REMOTE_ID, "mirror:missing").isEmpty());
        assertTrue(graph.defaultMirror(REMOTE_ID, grandchild.id()).isEmpty());
        assertTrue(graph.defaultMirror(REMOTE_ID, snapshot.id()).isEmpty());
    }

    private static WorldGraph.Node remote() {
        return new WorldGraph.Node(REMOTE_ID, null, null, REMOTE, null);
    }

    private static WorldGraph.Node mirror(String id, String parent, WorldGraph.Root root, String folder) {
        return new WorldGraph.Node(id, parent, new WorldGraph.Location(root, folder), MIRROR, SYNC);
    }
}
