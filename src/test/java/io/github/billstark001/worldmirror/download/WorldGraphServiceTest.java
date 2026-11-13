package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.legacy.LegacyWorldMetadata;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldGraphServiceTest {
    private static final String SOURCE = "server:Example.org:25565";
    private static final String ENDPOINT = "example.org";
    private static final String REMOTE = WorldGraphConfig.deterministicId("remote", ENDPOINT);
    private static final String MIRROR_UUID = "a0866b26-53bd-42f1-97be-753132b30794";
    private static final String MIRROR = "mirror:" + MIRROR_UUID;
    @TempDir Path game;

    @Test
    void migratesLegacyConfigMetadataAndIndexThenOnlyUsesTheGraph() throws Exception {
        Path output = folder("downloaded_worlds", "legacy");
        JsonSupport.writeDocument(output.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", SOURCE, "sourceType",
                        "server", "mirrorId", MIRROR_UUID, "mirrorKind", "synchronized", "lastSyncTime", 2000,
                        "chunkUpdateTimes", Map.of("minecraft:overworld|5,9", 2000)));
        Path legacy = legacyConfig("legacy", ModConfig.SaveLocation.DOWNLOADED);
        String oldConfig = Files.readString(legacy);
        var service = service();

        service.refresh();

        var target = service.target(remote());
        assertNotNull(target);
        assertEquals(MIRROR, target.nodeId());
        assertEquals(output, target.folder());
        assertEquals(SOURCE, target.sourceId());
        var metadata = WorldMetadata.loadIfPresent(output).orElseThrow();
        assertEquals(REMOTE, metadata.parentNodeId);
        assertEquals(2000, metadata.lastSyncTime);
        assertFalse(Files.readString(output.resolve(WorldMetadata.FILE_NAME)).contains("chunkUpdateTimes"));
        assertEquals(1, ChunkDatabase.countAllReadOnly(output, SOURCE));
        assertEquals(ModConfig.ConflictStrategy.MANUAL, service.conflictStrategy(MIRROR, REMOTE));
        assertFalse(Files.exists(legacy));
        try (var files = Files.list(configDirectory())) {
            Path archive = files.filter(path -> path.getFileName().toString().startsWith("mirrors.v1-"))
                                   .findFirst()
                                   .orElseThrow();
            assertEquals(oldConfig, Files.readString(archive));
        }
        assertFalse(Files.exists(configDirectory().resolve(".world-graph-import.json")));

        var reopened = service();
        reopened.refresh();
        assertEquals(target, reopened.target(remote()));
        assertEquals(1, ChunkDatabase.countAllReadOnly(output, SOURCE));
    }

    @Test
    void folderCopyGetsFreshIdButKnownOriginalRemainsDefaultAndKeepsIncomingEdges() throws Exception {
        Path original = folder("downloaded_worlds", "Z-original");
        writeMirror(original, MIRROR, REMOTE);
        var service = service();
        assertEquals(MIRROR, service.prepare(remote()).nodeId());
        Path copy = folder("saves", "A-copy");
        Files.copy(original.resolve(WorldMetadata.FILE_NAME), copy.resolve(WorldMetadata.FILE_NAME));
        String childId = "mirror:" + UUID.randomUUID();
        writeMirror(folder("saves", "Child"), childId, MIRROR);

        service.refresh();

        var copyMetadata = WorldMetadata.loadIfPresent(copy).orElseThrow();
        assertNotEquals(MIRROR, copyMetadata.nodeId);
        assertEquals(REMOTE, copyMetadata.parentNodeId);
        assertEquals(SOURCE, copyMetadata.sourceId);
        assertEquals(original, service.target(remote()).folder());
        assertEquals(MIRROR, service.graph().nodes().get(childId).parentId());
        assertTrue(service.graph().duplicates().isEmpty());

        Path renamed = original.resolveSibling("B-renamed");
        Files.move(original, renamed);
        service.refresh();
        assertEquals(renamed, service.target(remote()).folder());
        assertEquals(MIRROR, service.target(remote()).nodeId());

        Path storedElsewhere = game.resolve("temporarily-away");
        Files.move(renamed, storedElsewhere);
        service.refresh();
        assertNull(service.target(remote()));
        assertEquals(Set.of(MIRROR), service.graph().missingParents());
        assertEquals(MIRROR, service.graph().nodes().get(childId).parentId());
        assertThrows(IOException.class, () -> service.prepare(remote()));

        Files.move(storedElsewhere, renamed);
        service.refresh();
        assertEquals(MIRROR, service.target(remote()).nodeId());
        assertTrue(service.graph().missingParents().isEmpty());
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
        assertNull(service.sourceNode(remote()));
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
            assertThrows(IOException.class, () -> service.move(target, ModConfig.SaveLocation.SAVES));
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

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void ignoresJunctionsWhenDiscoveringWorlds() throws Exception {
        Path outside = Files.createDirectory(game.resolve("external-save"));
        writeMirror(outside, MIRROR, REMOTE);
        Path junction = Files.createDirectory(game.resolve("saves")).resolve("Junction");
        var command = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "New-Item -ItemType Junction -Path $env:WM_TEST_JUNCTION -Target $env:WM_TEST_EXTERNAL -ErrorAction Stop")
                              .redirectErrorStream(true);
        command.environment().put("WM_TEST_JUNCTION", junction.toString());
        command.environment().put("WM_TEST_EXTERNAL", outside.toString());
        var process = command.start();
        String result =
                new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            assumeTrue(process.waitFor() == 0, result);
            var service = service();
            service.refresh();
            assertFalse(service.graph().nodes().containsKey(MIRROR));
            assertEquals(MIRROR, WorldMetadata.loadIfPresent(outside).orElseThrow().nodeId);
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void lockedCopyDefersIdentityRepairUntilItCloses() throws Exception {
        Path original = folder("downloaded_worlds", "Original");
        writeMirror(original, MIRROR, REMOTE);
        var service = service();
        service.prepare(remote());
        Path copy = folder("saves", "Copy");
        Files.copy(original.resolve(WorldMetadata.FILE_NAME), copy.resolve(WorldMetadata.FILE_NAME));

        try (var ignored = net.minecraft.util.DirectoryLock.create(copy)) {
            service.refresh();
            assertEquals(1, service.graph().duplicates().size());
            assertEquals(MIRROR, WorldMetadata.loadIfPresent(copy).orElseThrow().nodeId);
            assertNull(service.target(remote()));
        }

        service.refresh();
        assertTrue(service.graph().duplicates().isEmpty());
        assertNotEquals(MIRROR, WorldMetadata.loadIfPresent(copy).orElseThrow().nodeId);
        assertEquals(original, service.target(remote()).folder());
    }

    @Test
    void lockedLegacyTargetKeepsItsRoutingAndDoesNotCreateAnotherMirror() throws Exception {
        Path output = folder("downloaded_worlds", "locked-legacy");
        JsonSupport.writeDocument(output.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", SOURCE, "sourceType",
                        "server", "mirrorId", MIRROR_UUID));
        legacyConfig("locked-legacy", ModConfig.SaveLocation.DOWNLOADED);
        var service = service();
        try (var ignored = net.minecraft.util.DirectoryLock.create(output)) {
            service.refresh();
            assertNull(service.target(remote()));
            assertThrows(IOException.class, () -> service.prepare(remote()));
            assertTrue(LegacyWorldMetadata.hasLegacyHeader(output));
        }

        var reopened = service();
        assertEquals(MIRROR, reopened.prepare(remote()).nodeId());
        assertEquals(output, reopened.target(remote()).folder());
        assertEquals(ModConfig.ConflictStrategy.MANUAL, reopened.conflictStrategy(MIRROR, REMOTE));
        try (var paths = Files.list(output.getParent())) {
            assertEquals(List.of(output), paths.toList());
        }
    }

    @Test
    void preservesLastConfirmedOriginalWhenItTemporarilyLeavesTheScanRoots() throws Exception {
        Path original = folder("downloaded_worlds", "original");
        writeMirror(original, MIRROR, REMOTE);
        var service = service();
        service.refresh();
        Path held = game.resolve("held");
        Files.move(original, held);
        service.refresh();
        assertFalse(service.graph().nodes().containsKey(MIRROR));
        assertEquals(new WorldGraph.Location(WorldGraph.Root.DOWNLOADED, "original"),
                WorldGraphConfig.open(configDirectory().resolve(WorldGraphConfig.FILE_NAME))
                        .snapshot()
                        .locations()
                        .get(MIRROR));
        Files.move(held, original);
        Path copy = folder("saves", "copy");
        Files.copy(original.resolve(WorldMetadata.FILE_NAME), copy.resolve(WorldMetadata.FILE_NAME));

        service.refresh();

        assertEquals(MIRROR, WorldMetadata.loadIfPresent(original).orElseThrow().nodeId);
        assertNotEquals(MIRROR, WorldMetadata.loadIfPresent(copy).orElseThrow().nodeId);
    }

    @Test
    void importsAnOldSaveAfterPublishingCurrentConfig() throws Exception {
        var service = service();
        service.refresh();
        assertTrue(Files.exists(configDirectory().resolve(WorldGraphConfig.FILE_NAME)));
        Path output = folder("downloaded_worlds", "late");
        JsonSupport.writeDocument(output.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", SOURCE, "sourceType",
                        "server", "mirrorKind", "synchronized"));

        service.refresh();

        String nodeId = WorldMetadata.loadIfPresent(output).orElseThrow().nodeId;
        assertTrue(nodeId.startsWith("mirror:"));
        assertEquals(nodeId, service.target(remote()).nodeId());
        assertFalse(Files.exists(configDirectory().resolve(".world-graph-import.json")));
        service.refresh();
        assertEquals(nodeId, service.target(remote()).nodeId());
    }

    @Test
    void resumesAnUnfinishedClaimAtTheSameFolderAfterReopen() throws Exception {
        var service = service();
        service.setSaveLocation(remote(), ModConfig.SaveLocation.DOWNLOADED);
        var initial = service.prepare(remote());
        Path config = configDirectory().resolve(WorldGraphConfig.FILE_NAME);
        var version = FileProof.version(config);

        assertEquals(initial, service.prepare(remote()));
        assertEquals(version, FileProof.version(config));
        assertTrue(MirrorClaim.ownedBy(initial.folder(), initial.nodeId()));

        var reopened = service();
        reopened.refresh();
        assertEquals(initial, reopened.prepare(remote()));
        try (var folders = Files.list(initial.folder().getParent())) {
            assertEquals(List.of(initial.folder()), folders.toList());
        }
    }

    @Test
    void reassignsAFileCopyOfAnUnfinishedReservation() throws Exception {
        var service = service();
        service.setSaveLocation(remote(), ModConfig.SaveLocation.DOWNLOADED);
        var initial = service.prepare(remote());
        Path copy = folder("saves", "Copy");
        Files.copy(initial.folder().resolve(MirrorClaim.FILE_NAME), copy.resolve(MirrorClaim.FILE_NAME));

        service.refresh();

        var claim = MirrorClaim.read(copy);
        assertNotNull(claim);
        assertNotEquals(initial.nodeId(), claim.nodeId());
        assertEquals(initial.parentNodeId(), claim.parentNodeId());
        assertEquals(initial, service.target(remote()));
        assertTrue(service.graph().duplicates().isEmpty());
    }

    @Test
    void resumesAnInterruptedImportWithItsOriginallyAllocatedId() throws Exception {
        Path output = folder("downloaded_worlds", "legacy");
        JsonSupport.writeDocument(output.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", SOURCE, "sourceType",
                        "server"));
        Path oldConfig = legacyConfig("legacy", ModConfig.SaveLocation.DOWNLOADED);
        Path plan = configDirectory().resolve(".world-graph-import.json");
        JsonSupport.writeDocument(plan,
                Map.of("legacyDigest", FileProof.sha256(oldConfig), "allocatedIds",
                        Map.of("DOWNLOADED/legacy", MIRROR)));

        var service = service();
        service.refresh();

        assertEquals(MIRROR, WorldMetadata.loadIfPresent(output).orElseThrow().nodeId);
        assertEquals(MIRROR, service.target(remote()).nodeId());
        assertFalse(Files.exists(plan));
        assertFalse(Files.exists(oldConfig));
    }

    @Test
    void recoversLegacyRelocationBeforeMigratingTheSave() throws Exception {
        Path original = folder("downloaded_worlds", "pending");
        Path destination = folder("saves", "pending");
        JsonSupport.writeDocument(original.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", SOURCE, "sourceType",
                        "server", "mirrorId", MIRROR_UUID));
        Files.writeString(destination.resolve("entry"), "existing chunk data");
        var journal = Map.of("schema", 1, "operation", UUID.randomUUID(), "sourceId", SOURCE, "mirrorId",
                MIRROR_UUID, "source", original.toString(), "target", destination.toString(), "entries",
                List.of(WorldMetadata.FILE_NAME, "entry"));
        JsonSupport.writeDocument(original.resolve(WorldRelocation.JOURNAL), journal);
        JsonSupport.writeDocument(destination.resolve(WorldRelocation.JOURNAL), journal);
        legacyConfig("pending", ModConfig.SaveLocation.DOWNLOADED);

        var service = service();
        service.refresh();

        assertEquals("existing chunk data", Files.readString(original.resolve("entry")));
        assertEquals(MIRROR, WorldMetadata.loadIfPresent(original).orElseThrow().nodeId);
        assertEquals(original, service.target(remote()).folder());
        assertFalse(Files.exists(destination));
    }

    @Test
    void movingATargetPreservesItsBindingAcrossRefreshAndReopen() throws Exception {
        Path original = folder("downloaded_worlds", "original");
        writeMirror(original, MIRROR, REMOTE);
        var service = service();
        var selected = service.prepare(remote());

        var move = service.move(selected, ModConfig.SaveLocation.SAVES);

        assertEquals(original, move.source());
        assertEquals(game.resolve("saves/original"), move.target());
        assertEquals(MIRROR, WorldMetadata.loadIfPresent(move.target()).orElseThrow().nodeId);
        assertEquals(move.target(), service.currentTarget(selected).folder());
        var reopened = service();
        reopened.refresh();
        assertEquals(move.target(), reopened.target(remote()).folder());

        var returned = reopened.move(reopened.target(remote()), ModConfig.SaveLocation.DOWNLOADED);
        assertEquals(original, returned.target());
        assertEquals(MIRROR, reopened.target(remote()).nodeId());
    }

    @Test
    void interruptedCurrentRelocationKeepsItsCommitWitnessWhileTheDestinationIsLocked() throws Exception {
        Path original = folder("downloaded_worlds", "interrupted");
        Path destination = folder("saves", "interrupted");
        writeMirror(original, MIRROR, REMOTE);
        var service = service();
        service.prepare(remote());
        Files.writeString(destination.resolve("entry"), "existing chunk data");
        var journal = Map.of("schema", 2, "operation", UUID.randomUUID(), "sourceId", SOURCE, "nodeId",
                MIRROR, "parentNodeId", REMOTE, "source", original.toString(), "target",
                destination.toString(), "entries", List.of(WorldMetadata.FILE_NAME, "entry"));
        JsonSupport.writeDocument(original.resolve(WorldRelocation.JOURNAL), journal);
        JsonSupport.writeDocument(destination.resolve(WorldRelocation.JOURNAL), journal);

        try (var ignored = net.minecraft.util.DirectoryLock.create(destination)) {
            service.refresh();
            assertNull(service.target(remote()));
            var persisted =
                    WorldGraphConfig.open(configDirectory().resolve(WorldGraphConfig.FILE_NAME)).snapshot();
            assertEquals(new WorldGraph.Location(WorldGraph.Root.DOWNLOADED, "interrupted"),
                    persisted.locations().get(MIRROR));
        }

        service.refresh();
        assertEquals(original, service.target(remote()).folder());
        assertEquals("existing chunk data", Files.readString(original.resolve("entry")));
        assertFalse(Files.exists(destination));
    }

    @Test
    void lostConfigDoesNotTreatAnOldMoveReservationAsTheMirror() throws Exception {
        Path reservation = folder("saves", "Z-old");
        Path completed = folder("downloaded_worlds", "A-current");
        MirrorClaim.create(reservation, MIRROR, REMOTE, SOURCE);
        writeMirror(completed, MIRROR, REMOTE);

        var service = service();
        service.refresh();

        assertEquals(completed, service.target(remote()).folder());
        assertEquals(MIRROR, WorldMetadata.loadIfPresent(completed).orElseThrow().nodeId);
        assertTrue(service.graph().duplicates().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({".,unnamed", "..,unnamed", "CON,_CON", "aux.txt,_aux.txt", "nul,_nul", "COM1,_COM1",
            "LPT9.foo,_LPT9.foo", "world.,world"})
    void generatesSafeOutputFolderNames(String label, String expected) {
        assertEquals(expected, MirrorFolders.generate(label));
        assertThrows(IllegalArgumentException.class, () -> MirrorFolders.available(game, "../outside"));
    }

    private WorldGraphService service() {
        return new WorldGraphService(game, configDirectory());
    }

    private Path configDirectory() {
        return game.resolve("config/worldmirror");
    }

    private WorldGraphService.Source remote() {
        return new WorldGraphService.Source(null, ENDPOINT, ENDPOINT);
    }

    private Path folder(String root, String name) throws IOException {
        return Files.createDirectories(game.resolve(root).resolve(name));
    }

    private static void writeMirror(Path folder, String id, String parent) {
        assertTrue(WorldMetadata.create(id, WorldGraph.Kind.MIRROR, parent, WorldGraph.Role.SYNC, SOURCE)
                        .save(folder));
    }

    private Path legacyConfig(String name, ModConfig.SaveLocation location) throws IOException {
        Files.createDirectories(configDirectory());
        Path file = configDirectory().resolve("mirrors.json");
        JsonSupport.writeDocument(file,
                Map.of("entries", Map.of(SOURCE, name), "resolvedFolderNames",
                        Map.of(SOURCE + '\0' + location.name(), name), "perWorldSaveLocation",
                        Map.of(SOURCE, location), "perWorldConflictStrategy",
                        Map.of(SOURCE, ModConfig.ConflictStrategy.MANUAL)));
        return file;
    }
}
