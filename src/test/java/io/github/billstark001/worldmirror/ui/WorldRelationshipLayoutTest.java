package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Role;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.ui.viewport.MapViewport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldRelationshipLayoutTest {
    @Test
    void placesTrackedTreeFirstAndKeepsSiblingsInDistinctRows() {
        Node source = new Node("remote:server", null, null, Kind.REMOTE, null);
        Node mirror = mirror("download", source.id(), Role.SYNC);
        Node snapshot = mirror("snapshot", source.id(), Role.SNAPSHOT);
        Node nested = mirror("nested", mirror.id(), Role.SYNC);
        var local = new Location(Root.SAVES, "aaa");
        var layout = WorldRelationshipLayout.create(
                WorldGraph.reconcile(List.of(snapshot, nested, source, mirror), Map.of()), List.of(local),
                Node::id);

        var root = layout.byKey().get(source.id());
        var left = layout.byKey().get(mirror.id());
        var right = layout.byKey().get(snapshot.id());
        var leaf = layout.byKey().get(nested.id());
        assertEquals(left.x(), right.x());
        assertTrue(left.x() > root.x());
        assertTrue(leaf.x() > left.x());
        assertTrue(left.y() < root.y() && root.y() < right.y());
        assertEquals(left.y(), leaf.y());
        assertEquals(List.of(left, right), layout.children().get(source.id()));
        assertEquals(3, layout.edges().size());
        assertTrue(layout.cards().getLast().y() > layout.trackedHeight());
        assertEquals(local, layout.cards().getLast().untracked());
    }

    @Test
    void keepsActualEdgesAcrossCyclesAndMissingParents() {
        Node a = mirror("a", "mirror:b", Role.SYNC);
        Node b = mirror("b", a.id(), Role.SYNC);
        Node self = mirror("self", "mirror:self", Role.SYNC);
        Node orphan = mirror("orphan", "local:missing", Role.SNAPSHOT);
        var layout = WorldRelationshipLayout.create(
                WorldGraph.reconcile(List.of(a, b, self, orphan), Map.of()), List.of(), Node::id);

        assertEquals(5, layout.cards().size());
        assertEquals(4, layout.edges().size());
        assertEquals(3, layout.edges().stream().filter(WorldRelationshipLayout.Edge::cycle).count());
        assertEquals(WorldRelationshipLayout.Warning.MISSING, layout.byKey().get("local:missing").warning());
        assertEquals(List.of(layout.byKey().get(a.id())), layout.children().get(b.id()));
        assertEquals(List.of(layout.byKey().get(b.id())), layout.children().get(a.id()));
    }

    @Test
    void keepsTheModelPointUnderPointerWhenZoomingAfterADrag() {
        var viewport = new MapViewport();
        viewport.centerOn(11, 21);
        viewport.beginDrag(4, 9);
        assertTrue(viewport.dragTo(1, 3, 2));
        assertTrue(viewport.endDrag());
        assertEquals(12.5, viewport.x());
        assertEquals(24, viewport.y());
        double modelX = viewport.toModelX(40, 30, 2);
        double modelY = viewport.toModelY(50, 30, 2);

        viewport.zoomAt(40, 50, 30, 30, 2, 0.5);

        assertEquals(40, viewport.toScreenX(modelX, 30, 0.5));
        assertEquals(50, viewport.toScreenY(modelY, 30, 0.5));
    }

    private static Node mirror(String id, String parent, Role role) {
        return new Node("mirror:" + id, parent, new Location(Root.SAVES, id), Kind.MIRROR, role);
    }
}
