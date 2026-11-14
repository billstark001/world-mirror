package io.github.billstark001.worldmirror.legacy;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.LocalWorldIdentity;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.download.WorldGraphFixture;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.download.WorldGraphWorlds;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.util.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

class LegacyLocalRouteAdoptionTest extends WorldGraphFixture {
    private static final String OLD = "local:Old name";
    private static final Location SOURCE_LOCATION = new Location(Root.SAVES, "Renamed");

    @Test
    void explicitlyAdoptsRenamedSourceWithoutChangingIndexOrOtherSameNameWorld() throws Exception {
        Path source = local("Renamed");
        Path other = local("Same-name copy");
        Path target = oldMirror("old-destination", OLD);
        legacy("old-destination");
        var service = service();
        service.refresh();
        var pending = LegacyLocalRouteAdoption.pending(service.configSnapshot()).getFirst();
        assertNull(LocalWorldIdentity.read(source));
        assertNull(LocalWorldIdentity.read(other));
        assertEquals("Old name", pending.oldName());

        service.adoptLegacyLocalRoute(pending, SOURCE_LOCATION, null);

        String id = LocalWorldIdentity.read(source);
        assertNotNull(id);
        assertNull(LocalWorldIdentity.read(other));
        assertEquals(id, WorldMetadata.loadIfPresent(target).orElseThrow().parentNodeId);
        assertEquals(OLD, WorldMetadata.loadIfPresent(target).orElseThrow().sourceId);
        assertEquals(MIRROR, service.configSnapshot().sourceSettings().get(id).pinnedMirrorId());
        assertEquals(target, service.target(new WorldGraphService.Source(id, "Renamed", null)).folder());
        assertEquals(ModConfig.ConflictStrategy.MANUAL, service.conflictStrategy(MIRROR, id));
        assertTrue(service.configSnapshot().pendingImports().isEmpty());
        var reopened = service();
        reopened.refresh();
        assertTrue(LegacyLocalRouteAdoption.pending(reopened.configSnapshot()).isEmpty());
    }

    @Test
    void missingTargetTransfersCreationPreferencesAndKeepsAnExistingUserEdit() throws Exception {
        Path source = local("Renamed");
        legacy("missing-destination");
        String id = LocalWorldIdentity.ensure(source);
        var service = service();
        service.refresh();
        service.setSourceSaveLocation(id, ModConfig.SaveLocation.SAVES);
        var pending = LegacyLocalRouteAdoption.pending(service.configSnapshot()).getFirst();

        service.adoptLegacyLocalRoute(pending, SOURCE_LOCATION, id);

        var settings = service.configSnapshot().sourceSettings().get(id);
        assertNull(settings.pinnedMirrorId());
        assertEquals("preferred-destination", settings.preferredFolderName());
        assertEquals(ModConfig.SaveLocation.SAVES, settings.saveLocation());
        assertEquals(ModConfig.ConflictStrategy.MANUAL, settings.conflictStrategy());
        assertFalse(Files.exists(game.resolve("downloaded_worlds/missing-destination")));
    }

    @Test
    void refusesAnUnrelatedSaveAtTheRecordedDestinationAndKeepsThePendingRecord() throws Exception {
        Path source = local("Renamed");
        Path target = oldMirror("old-destination", "server:other.test");
        legacy("old-destination");
        var service = service();
        service.refresh();
        var pending = LegacyLocalRouteAdoption.pending(service.configSnapshot()).getFirst();
        var before = Files.readAllBytes(target.resolve(WorldMetadata.FILE_NAME));

        assertThrows(IOException.class, () -> service.adoptLegacyLocalRoute(pending, SOURCE_LOCATION, null));
        assertEquals(pending, LegacyLocalRouteAdoption.pending(service.configSnapshot()).getFirst());
        assertNull(LocalWorldIdentity.read(source));
        assertArrayEquals(before, Files.readAllBytes(target.resolve(WorldMetadata.FILE_NAME)));
        service.discardLegacyLocalRoute(pending);
        service.refresh();
        assertTrue(LegacyLocalRouteAdoption.pending(service.configSnapshot()).isEmpty());
    }

    @Test
    void recoversAfterTheEdgeWasWrittenBeforeSettingsPublication() throws Exception {
        Path source = local("Renamed");
        Path target = oldMirror("old-destination", OLD);
        legacy("old-destination");
        var service = service();
        service.refresh();
        var pending = LegacyLocalRouteAdoption.pending(service.configSnapshot()).getFirst();
        var oldNode = service.graph().nodes().get(MIRROR);
        String id = LocalWorldIdentity.ensure(source);
        Path journal = configDirectory().resolve(".world-graph-local-adoption.json");
        JsonSupport.writeDocument(
                journal, new LegacyLocalRouteAdoption.Plan(pending, SOURCE_LOCATION, id, oldNode));
        WorldGraphWorlds.changeParentOnDisk(target, oldNode, id);

        var reopened = service();
        reopened.refresh();
        assertFalse(Files.exists(journal));
        assertTrue(LegacyLocalRouteAdoption.pending(reopened.configSnapshot()).isEmpty());
        assertEquals(id, reopened.graph().nodes().get(MIRROR).parentId());
        assertEquals(MIRROR, reopened.configSnapshot().sourceSettings().get(id).pinnedMirrorId());
    }

    @Test
    void archivedPendingNoteFromPreviousBuildCanStillBeClaimed() throws Exception {
        var source = local("Renamed");
        legacy("missing-destination");
        var service = service();
        service.refresh();
        var configFile = configDirectory().resolve(WorldGraphConfig.FILE_NAME);
        Files.writeString(
                configFile, Files.readString(configFile).replace("legacy-local:" + OLD, "legacy:" + OLD));
        assertFalse(Files.exists(configDirectory().resolve("mirrors.json")));
        var reopened = service();
        reopened.refresh();
        var pending = LegacyLocalRouteAdoption.pending(reopened.configSnapshot()).getFirst();
        assertEquals("legacy-local:" + OLD, pending.key());
        reopened.adoptLegacyLocalRoute(pending, SOURCE_LOCATION, null);
        assertTrue(LegacyLocalRouteAdoption.pending(reopened.configSnapshot()).isEmpty());
        assertEquals("preferred-destination",
                reopened.configSnapshot()
                        .sourceSettings()
                        .get(LocalWorldIdentity.read(source))
                        .preferredFolderName());
    }

    private Path local(String name) throws IOException {
        var folder = folder("saves", name);
        var level = new CompoundTag();
        level.putString("LevelName", "Old name");
        var root = new CompoundTag();
        root.put("Data", level);
        NbtIo.writeCompressed(root, folder.resolve("level.dat"));
        return folder;
    }

    private Path oldMirror(String name, String source) throws IOException {
        Path path = folder("downloaded_worlds", name);
        JsonSupport.writeDocument(path.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", source, "sourceType",
                        "local", "mirrorId", MIRROR_UUID, "mirrorKind", "synchronized"));
        return path;
    }

    private void legacy(String destination) throws IOException {
        Files.createDirectories(configDirectory());
        JsonSupport.writeDocument(configDirectory().resolve("mirrors.json"),
                Map.of("entries", Map.of(OLD, "preferred-destination"), "resolvedFolderNames",
                        Map.of(OLD + '\0' + "DOWNLOADED", destination), "perWorldSaveLocation",
                        Map.of(OLD, "DOWNLOADED"), "perWorldConflictStrategy", Map.of(OLD, "MANUAL")));
    }
}
