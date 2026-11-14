package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldGraphRoutingTest extends WorldGraphFixture {
    @Test
    void resolvesTwoLogicalWorldsAndFourMirrorsWithoutRekeyingSaveData() throws Exception {
        String first = "remote:first", second = "remote:second", group = "endpoint:fixture";
        String alias = "alias.example.org";
        String firstA = "mirror:first-a", firstB = "mirror:first-b";
        String secondA = "mirror:second-a", secondB = "mirror:second-b";
        Path firstFolder = folder("downloaded_worlds", "first-a");
        writeMirror(firstFolder, firstA, first);
        writeMirror(folder("saves", "first-b"), firstB, first);
        writeMirror(folder("downloaded_worlds", "second-a"), secondA, second);
        writeMirror(folder("saves", "second-b"), secondB, second);
        try (var connection = ChunkDatabase.openWritableConnection(firstFolder)) {
            ChunkIndexStore.initialize(connection);
            new ChunkIndexStore(connection, SOURCE)
                    .recordUpdates("minecraft:overworld",
                            Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), 1L), "world_mirror");
        }
        Path metadata = firstFolder.resolve(WorldMetadata.FILE_NAME);
        Path database = firstFolder.resolve(MirrorFormat.DATABASE_FILE);
        byte[] oldMetadata = Files.readAllBytes(metadata), oldDatabase = Files.readAllBytes(database);
        var service = service();
        service.refresh();
        var rule = new WorldGraphConfig.EndpointRule(
                group, Set.of(first, second), first, WorldGraphConfig.IdPolicy.PREFER_BOUND_ID);
        service.editRouting(snapshot
                -> snapshot.withEndpointRule(ENDPOINT, rule)
                        .withEndpointRule(alias, rule)
                        .withStableBinding(new WorldGraphConfig.StableBinding("xaero", group, "17", first))
                        .withStableBinding(new WorldGraphConfig.StableBinding("xaero", group, "-8", second))
                        .withSourceSettings(first,
                                new WorldGraphConfig.SourceSettings(firstB, null, null, null, firstA)));
        service.routing().beginObservation(new WorldGraphService.Source(null, alias, alias));
        assertEquals(State.WAITING_ID, service.routing().currentResolution().state());
        assertTrue(service.routing().observeProvider(ProviderObservation.advertised("xaero", "17"), false));
        assertEquals(firstB, service.target(service.routing().source()).nodeId());
        assertEquals(firstA, service.prepare(service.routing().source(), firstA, false, () -> true).nodeId());
        assertEquals(firstB, service.target(service.routing().source()).nodeId());
        service.editRouting(snapshot
                -> snapshot.withSourceSettings(
                        first, snapshot.sourceSettings().get(first).withDefaultMirror(null)));
        assertEquals(firstA, service.target(service.routing().source()).nodeId());

        assertTrue(service.routing().observeProvider(ProviderObservation.advertised("xaero", "-8"), false));
        assertEquals(second, service.routing().currentResolution().sourceNodeId());
        assertEquals(Set.of(secondA, secondB),
                service.targetsForSource(second)
                        .stream()
                        .map(WorldGraphService.Target::nodeId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(service.routing().observeProvider(ProviderObservation.advertised("xaero", "99"), true));
        assertEquals(State.CHOOSE_SOURCE, service.routing().currentResolution().state());
        assertEquals(second, service.routing().chooseSource(service.routing().observation(), second, false));
        assertEquals(secondA, service.prepare(service.routing().source()).nodeId());

        service.editRouting(snapshot -> snapshot.withEndpointRule(alias, null));
        assertArrayEquals(oldMetadata, Files.readAllBytes(metadata));
        assertArrayEquals(oldDatabase, Files.readAllBytes(database));
    }

    @Test
    void persistsNewSourceAndItsBindingWhenDownloadTargetIsPrepared() throws Exception {
        var service = service();
        service.refresh();
        service.routing().beginObservation(remote());
        var id = ProviderObservation.advertised("xaero", "987");
        assertTrue(service.routing().observeProvider(id, false));
        assertEquals(State.CHOOSE_SOURCE, service.routing().currentResolution().state());
        String selected = service.routing().chooseSource(service.routing().observation(), null, true);
        service.setSaveLocation(service.routing().source(), ModConfig.SaveLocation.DOWNLOADED);
        var target = service.prepare(service.routing().source());
        assertEquals(selected, target.parentNodeId());
        assertEquals(selected, target.sourceId());
        var reopened = service();
        reopened.refresh();
        reopened.routing().beginObservation(remote());
        reopened.routing().observeProvider(id, false);
        assertEquals(selected, reopened.routing().currentResolution().sourceNodeId());
        assertEquals(target, reopened.target(reopened.routing().source()));
    }

    @Test
    void missingGraphConfigRebuildsAmbiguousEndpointCandidatesFromTheSaves() throws Exception {
        Path first = folder("downloaded_worlds", "First");
        Path second = folder("saves", "Second");
        String firstParent = "remote:first-split", secondParent = "remote:second-split";
        writeMirror(first, MIRROR, firstParent);
        String secondNode = "mirror:" + UUID.randomUUID();
        assertTrue(WorldMetadata
                        .create(secondNode, WorldGraph.Kind.MIRROR, secondParent, WorldGraph.Role.SYNC,
                                "server:" + ENDPOINT)
                        .save(second));
        var service = service();
        service.refresh();

        var config = WorldGraphConfig.open(configDirectory().resolve(WorldGraphConfig.FILE_NAME)).snapshot();
        assertEquals(Set.of(firstParent, secondParent), config.endpointRules().get(ENDPOINT).sourceIds());
        assertNull(service.routing().sourceNode(remote()));
        assertThrows(IOException.class, () -> service.prepare(remote()));
        assertEquals(4, service.graph().nodes().size());
    }

    @Test
    void allGraphMutationsRespectTheCrossProcessLease() throws Exception {
        Path original = folder("downloaded_worlds", "Original");
        writeMirror(original, MIRROR, REMOTE);
        var service = service();
        var target = service.prepare(remote());

        try (var channel = FileChannel.open(
                     configDirectory().resolve(".world-graph.lock"), StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            assertThrows(IOException.class, service::refresh);
            assertThrows(IOException.class, () -> service.prepare(remote()));
            assertThrows(
                    IOException.class, () -> service.setSaveLocation(remote(), ModConfig.SaveLocation.SAVES));
            assertThrows(IOException.class,
                    () -> service.setConflictStrategy(MIRROR, REMOTE, ModConfig.ConflictStrategy.MANUAL));
            assertThrows(
                    IOException.class, () -> service.worlds().move(target, ModConfig.SaveLocation.SAVES));
        }

        assertEquals(target, service.target(remote()));
        assertTrue(Files.isRegularFile(original.resolve(WorldMetadata.FILE_NAME)));
        assertFalse(Files.exists(game.resolve("saves")));
    }

    @Test
    void interruptedPreparationDoesNotReserveAWorld() throws Exception {
        var service = service();
        assertThrows(IOException.class, () -> service.prepare(remote(), () -> false));
        assertNull(service.target(remote()));
        assertFalse(Files.exists(game.resolve("downloaded_worlds")));
    }

    @ParameterizedTest
    @CsvSource({".,unnamed", "..,unnamed", "CON,_CON", "aux.txt,_aux.txt", "nul,_nul", "COM1,_COM1",
            "LPT9.foo,_LPT9.foo", "world.,world"})
    void generatesSafeOutputFolderNames(String label, String expected) {
        assertEquals(expected, MirrorFolders.generate(label));
        assertThrows(IllegalArgumentException.class, () -> MirrorFolders.available(game, "../outside"));
    }
}
