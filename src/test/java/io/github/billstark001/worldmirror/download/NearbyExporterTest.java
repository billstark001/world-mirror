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
        for (int export = 0; export < 8; export++) {
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
                null, "server:test", "server");
        var second = NearbyExporter.resolveLineage(NearbyExporter.Choice.INDEPENDENT,
                null, "server:test", "server");

        assertTrue(first.sourceId().startsWith("snapshot:"));
        assertNotEquals(first.sourceId(), second.sourceId());
        assertEquals("nearby_export", first.sourceType());
        assertNull(first.parentMirrorId());
    }

    @Test
    void exportNamesKeepUnicodeAndDoNotSplitSurrogatePairs(@TempDir Path root) throws Exception {
        String unicode = "镜像ワールド";
        assertEquals(unicode, MirrorMapping.sanitiseFolderName(unicode, "NearbyExport"));
        assertEquals("NearbyExport", MirrorMapping.sanitiseFolderName("...", "NearbyExport"));
        assertEquals("_CON", MirrorMapping.sanitiseFolderName("CON", "NearbyExport"));
        String truncated = MirrorMapping.sanitiseFolderName("a".repeat(63) + "🌍", "NearbyExport");
        assertEquals("a".repeat(63), truncated);
        assertTrue(Files.isDirectory(NearbyExporter.claimOutputFolder(root, truncated)));
    }

    @Test
    void futureMetadataWithoutIdentityIsNeverRewrittenForLineage(@TempDir Path world) throws Exception {
        String original = "{\"sourceId\":\"server:test\",\"worldgenSchema\":999,\"futureField\":123}";
        Path file = world.resolve(WorldMetadata.FILE_NAME);
        Files.writeString(file, original);
        WorldMetadata metadata = WorldMetadata.loadIfPresent(world).orElseThrow();
        var current = new MirrorWorldContext.Snapshot(world, MirrorWorldContext.State.FUTURE, metadata);

        org.junit.jupiter.api.Assertions.assertFalse(NearbyExporter.ensureCurrentMirrorIdentity(current));
        assertEquals(original, Files.readString(file));
        var independent = NearbyExporter.resolveLineage(NearbyExporter.Choice.INDEPENDENT,
                metadata, "fallback", "server");
        assertTrue(independent.sourceId().startsWith("snapshot:"));
        assertNull(metadata.mirrorId);
    }
}
