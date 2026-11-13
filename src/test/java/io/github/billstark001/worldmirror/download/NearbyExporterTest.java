package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyExporterTest {
    @Test
    void concurrentExportsClaimDifferentFoldersAndKeepExistingSaves(@TempDir Path root) throws Exception {
        Path occupied = Files.createDirectory(root.resolve("snapshot"));
        Files.writeString(occupied.resolve("keep.txt"), "existing save");
        var requests = new ArrayList<Callable<Path>>();
        for (int export = 0; export < 8; export ++) {
            requests.add(() -> NearbyExporter.claimOutputFolder(root, "snapshot"));
        }
        var claimed = new HashSet<Path>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(requests)) claimed.add(result.get());
        }

        assertEquals(8, claimed.size());
        assertTrue(claimed.stream().allMatch(Files::isDirectory));
        assertEquals("existing save", Files.readString(occupied.resolve("keep.txt")));
        assertThrows(IllegalArgumentException.class, () -> NearbyExporter.claimOutputFolder(root, ".."));
    }

    @Test
    void independentLineageOwnsItsNamespaceAndHasNoParent() {
        var first = NearbyExporter.independentLineage();
        var second = NearbyExporter.independentLineage();

        assertTrue(first.sourceId().startsWith("snapshot:"));
        assertNotEquals(first.sourceId(), second.sourceId());
        assertEquals(first.nodeId(), first.sourceId());
        assertNull(first.parentNodeId());
    }

    @Test
    void exportNamesKeepUnicodeAndDoNotSplitSurrogatePairs(@TempDir Path root) throws Exception {
        String unicode = "镜像ワールド";
        assertEquals(unicode, MirrorFolders.sanitiseFolderName(unicode, "NearbyExport"));
        assertEquals("NearbyExport", MirrorFolders.sanitiseFolderName("...", "NearbyExport"));
        assertEquals("_CON", MirrorFolders.sanitiseFolderName("CON", "NearbyExport"));
        String truncated = MirrorFolders.sanitiseFolderName("a".repeat(63) + "🌍", "NearbyExport");
        assertEquals("a".repeat(63), truncated);
        assertTrue(Files.isDirectory(NearbyExporter.claimOutputFolder(root, truncated)));
    }

    @Test
    void linkedExportsCanUseCurrentOrOriginalWorld() {
        var source = new WorldGraph.Node("remote:origin", null, null, WorldGraph.Kind.REMOTE, null);
        var mirror = new WorldGraph.Node("mirror:current", source.id(),
                new WorldGraph.Location(WorldGraph.Root.SAVES, "current"), WorldGraph.Kind.MIRROR,
                WorldGraph.Role.SYNC);
        var graph = WorldGraph.reconcile(java.util.List.of(source, mirror), java.util.Map.of());
        var inherited = NearbyExporter.resolveLinkedLineage(
                NearbyExporter.Choice.INHERIT_ORIGINAL, graph, mirror.id(), null);
        var derived = NearbyExporter.resolveLinkedLineage(
                NearbyExporter.Choice.CURRENT_MIRROR, graph, mirror.id(), null);
        assertEquals(source.id(), inherited.parentNodeId());
        assertEquals(mirror.id(), derived.parentNodeId());
        assertNotEquals(inherited.nodeId(), derived.nodeId());
    }

    @Test
    void missingParentLeavesCurrentMirrorAndIndependentSnapshotAvailable() {
        var source = new WorldGraph.Node("mirror:opened", "local:missing",
                new WorldGraph.Location(WorldGraph.Root.SAVES, "opened"), WorldGraph.Kind.MIRROR,
                WorldGraph.Role.SYNC);
        var graph = WorldGraph.reconcile(List.of(source), Map.of());
        assertEquals(List.of(NearbyExporter.Choice.CURRENT_MIRROR, NearbyExporter.Choice.INDEPENDENT),
                NearbyExporter.lineageChoices(graph, source.id(), false));
        assertEquals(source.id(),
                NearbyExporter
                        .resolveLinkedLineage(NearbyExporter.Choice.CURRENT_MIRROR, graph, source.id(), null)
                        .parentNodeId());
        assertThrows(IllegalArgumentException.class,
                ()
                        -> NearbyExporter.resolveLinkedLineage(
                                NearbyExporter.Choice.INHERIT_ORIGINAL, graph, source.id(), null));
        assertEquals(List.of(NearbyExporter.Choice.INDEPENDENT),
                NearbyExporter.lineageChoices(graph, null, false));
    }

    @Test
    void disconnectedSnapshotAndResolvedRemoteSourceOfferSeparateLineageChoices() {
        var graph = WorldGraph.reconcile(List.of(), Map.of());
        assertEquals(List.of(NearbyExporter.Choice.INHERIT_ORIGINAL, NearbyExporter.Choice.INDEPENDENT),
                NearbyExporter.lineageChoices(graph, "remote:resolved", false));
        var linked = NearbyExporter.resolveLinkedLineage(
                NearbyExporter.Choice.INHERIT_ORIGINAL, graph, "remote:resolved", "server:alias.test");
        assertEquals("remote:resolved", linked.parentNodeId());
        assertEquals("server:alias.test", linked.sourceId());
    }

    @Test
    void untrackedLocalSourceOffersLinkedExportBeforeItsIdentityIsRegistered() {
        var graph = WorldGraph.reconcile(List.of(), Map.of());
        assertEquals(List.of(NearbyExporter.Choice.INHERIT_ORIGINAL, NearbyExporter.Choice.INDEPENDENT),
                NearbyExporter.lineageChoices(graph, null, true));
        var source = new WorldGraph.Node("local:original", null,
                new WorldGraph.Location(WorldGraph.Root.SAVES, "original"), WorldGraph.Kind.LOCAL, null);
        var linked = NearbyExporter.resolveLinkedLineage(NearbyExporter.Choice.INHERIT_ORIGINAL,
                WorldGraph.reconcile(List.of(source), Map.of()), source.id(), null);
        assertEquals("local:original", linked.parentNodeId());
        assertEquals("local:original", linked.sourceId());
    }
}
