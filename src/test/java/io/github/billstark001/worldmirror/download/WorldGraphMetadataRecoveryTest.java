package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.legacy.LegacyWorldMetadata;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldGraphMetadataRecoveryTest extends WorldGraphFixture {
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
}
