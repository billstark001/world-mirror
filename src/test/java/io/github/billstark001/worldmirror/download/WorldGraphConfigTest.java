package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGraphConfigTest {
    @Test
    void roundTripsTypedGraphAndRetainsMissingReferences(@TempDir Path root) throws Exception {
        Path file = root.resolve(WorldGraphConfig.FILE_NAME);
        WorldGraphConfig config = WorldGraphConfig.open(file);
        WorldGraph.Location location = new WorldGraph.Location(WorldGraph.Root.SAVES, "镜像");
        WorldGraph.Node node = new WorldGraph.Node(
                "mirror", "remote:missing-source", location, WorldGraph.Kind.MIRROR, WorldGraph.Role.SYNC);
        var sources = new HashSet<>(Set.of("remote:missing-source", "remote:other-source"));
        var nodes = new HashMap<>(Map.of(node.id(), node));
        var bindings = new ArrayList<>(List.of(
                new WorldGraphConfig.StableBinding("provider", "group", "id", "remote:missing-source")));
        var next = new WorldGraphConfig.Snapshot(WorldGraphConfig.SCHEMA, nodes,
                Map.of(node.id(), node.location()),
                Map.of("remote:missing-source",
                        new WorldGraphConfig.SourceSettings(node.id(), ModConfig.SaveLocation.SAVES, "镜像",
                                ModConfig.ConflictStrategy.IGNORE)),
                Map.of(node.id(), new WorldGraphConfig.MirrorSettings(ModConfig.ConflictStrategy.MANUAL)),
                Map.of("example.test",
                        new WorldGraphConfig.EndpointRule(
                                "group", sources, "remote:missing-source", WorldGraphConfig.IdPolicy.AUTO)),
                bindings,
                Map.of("pending",
                        new WorldGraphConfig.PendingImport(null, location, "local_source_ambiguous")));
        nodes.clear();
        sources.clear();
        bindings.clear();

        assertFalse(config.exists());
        assertTrue(config.replace(config.snapshot(), next));
        assertTrue(config.exists());
        var loaded = WorldGraphConfig.open(file).snapshot();
        assertEquals(next, loaded);
        assertEquals(Map.of("mirror", location), loaded.locations());
        assertEquals(Set.of("remote:missing-source", "remote:other-source"),
                loaded.endpointRules().get("example.test").sourceIds());
        assertEquals("remote:missing-source", loaded.stableBindings().getFirst().sourceId());
        assertThrows(UnsupportedOperationException.class, () -> loaded.nodes().clear());
    }

    @Test
    void endpointRulesCannotClaimLocalFileIdentities() {
        assertThrows(IllegalArgumentException.class,
                ()
                        -> new WorldGraphConfig.EndpointRule("group", Set.of("mirror:physical-save"), null,
                                WorldGraphConfig.IdPolicy.AUTO));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldGraphConfig.StableBinding("provider", "group", "advertised", "local:world"));
    }

    @Test
    void stalePublicationLeavesTheDiskAndPublishedSnapshotsUntouched(@TempDir Path root) throws Exception {
        Path file = root.resolve(WorldGraphConfig.FILE_NAME);
        WorldGraphConfig first = WorldGraphConfig.open(file);
        WorldGraphConfig second = WorldGraphConfig.open(file);
        var missing = first.snapshot();
        var firstNode = new WorldGraph.Node("first", null, null, WorldGraph.Kind.REMOTE, null);
        var secondNode = new WorldGraph.Node("second", null, null, WorldGraph.Kind.REMOTE, null);
        var one = missing.withNodes(Map.of(firstNode.id(), firstNode));
        var two = missing.withNodes(Map.of(secondNode.id(), secondNode));

        assertTrue(first.replace(missing, one));
        assertFalse(first.replace(missing, two));
        assertThrows(IOException.class, () -> second.replace(second.snapshot(), two));
        assertFalse(second.exists());
        assertTrue(second.snapshot().nodes().isEmpty());
        assertEquals(one, WorldGraphConfig.open(file).snapshot());
    }

    @Test
    void damagedAndFutureDocumentsAreNeverReplaced(@TempDir Path root) throws Exception {
        Path file = root.resolve(WorldGraphConfig.FILE_NAME);
        for (String text : List.of("{malformed", "{\"schema\":3}", "{\"schema\":2}")) {
            Files.writeString(file, text);
            assertThrows(IOException.class, () -> WorldGraphConfig.open(file));
            assertEquals(text, Files.readString(file));
        }
    }

    @Test
    void endpointNormalizationUsesOnlyPlayerEnteredAddress() {
        assertEquals("example.test", WorldGraphConfig.normalizeEndpoint("EXAMPLE.test:25565"));
        assertEquals("xn--bcher-kva.test:25566", WorldGraphConfig.normalizeEndpoint("Bücher.Test:25566"));
        assertEquals("[2001:db8::1]", WorldGraphConfig.normalizeEndpoint("2001:0DB8:0:0:0:0:0:1"));
        assertEquals("[2001:db8::1]:25566", WorldGraphConfig.normalizeEndpoint("[2001:DB8::1]:25566"));
        assertEquals("example.test.", WorldGraphConfig.normalizeEndpoint("Example.Test."));
        for (String invalid : List.of("", ".", "example.test:", "example.test:-1", "example.test:0",
                     "example.test:65536", "https://example.test", "[example.test]", "[2001:db8::1]tail")) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorldGraphConfig.normalizeEndpoint(invalid), invalid);
        }
    }
}
