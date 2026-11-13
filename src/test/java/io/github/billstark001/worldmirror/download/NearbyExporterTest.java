package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
    void independentLineageDoesNotRequireAnExistingMirror() {
        var first = NearbyExporter.resolveLineage(NearbyExporter.Choice.INDEPENDENT,
                WorldGraph.reconcile(java.util.List.of(), java.util.Map.of()), null, null);
        var second = NearbyExporter.resolveLineage(NearbyExporter.Choice.INDEPENDENT,
                WorldGraph.reconcile(java.util.List.of(), java.util.Map.of()), null, null);

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
        var inherited = NearbyExporter.resolveLineage(
                NearbyExporter.Choice.INHERIT_ORIGINAL, graph, mirror.id(), null);
        var derived =
                NearbyExporter.resolveLineage(NearbyExporter.Choice.CURRENT_MIRROR, graph, mirror.id(), null);
        assertEquals(source.id(), inherited.parentNodeId());
        assertEquals(mirror.id(), derived.parentNodeId());
        assertNotEquals(inherited.nodeId(), derived.nodeId());
    }
}
