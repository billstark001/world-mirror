package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTargetSelectionTest extends WorldGraphFixture {
    private static final String SIBLING = "mirror:000-sibling";

    @Test
    void importedDefaultIsPinnedUntilAutomaticIsExplicitlySelected() throws Exception {
        Path legacy = folder("downloaded_worlds", "old-default");
        Path sibling = folder("downloaded_worlds", "sibling");
        writeLegacyMirror(legacy);
        writeMirror(sibling, SIBLING, REMOTE);
        legacyConfig("old-default", ModConfig.SaveLocation.DOWNLOADED);
        var service = service();
        service.refresh();

        assertEquals(MIRROR, service.configSnapshot().sourceSettings().get(REMOTE).pinnedMirrorId());
        assertEquals(legacy, service.preview(remote()));
        assertEquals(legacy, service.prepare(remote()).folder());
        assertEquals(sibling, service.prepare(remote(), SIBLING, false, () -> true).folder());
        assertEquals(legacy, service.preview(remote()));

        Path hidden = Files.move(legacy, game.resolve("elsewhere"));
        service.refresh();
        assertNull(service.preview(remote()));
        assertThrows(IOException.class, () -> service.prepare(remote()));
        assertEquals(List.of(sibling),
                service.targetsForSource(REMOTE).stream().map(WorldGraphService.Target::folder).toList());

        service.editRouting(config
                -> config.withSourceSettings(
                        REMOTE, config.sourceSettings().get(REMOTE).withPinnedMirror(null)));
        var reopened = service();
        reopened.refresh();
        assertEquals(sibling, reopened.preview(remote()));
        assertEquals(sibling, reopened.prepare(remote()).folder());
        assertNull(reopened.configSnapshot().sourceSettings().get(REMOTE).pinnedMirrorId());
        assertEquals(SIBLING, reopened.configSnapshot().sourceSettings().get(REMOTE).lastAutomaticMirrorId());
        assertTrue(Files.exists(hidden.resolve(WorldMetadata.FILE_NAME)));
    }

    @Test
    void automaticRemembersItsLastSelectionButFallsThroughWhenItDisappears() throws Exception {
        Path incumbent = folder("downloaded_worlds", "incumbent");
        writeMirror(incumbent, MIRROR, REMOTE);
        var service = service();
        assertEquals(incumbent, service.prepare(remote()).folder());
        Path later = folder("saves", "later");
        writeMirror(later, SIBLING, REMOTE);
        service.refresh();
        assertEquals(incumbent, service.preview(remote()));
        assertEquals(later, service.prepare(remote(), SIBLING, false, () -> true).folder());
        assertEquals(incumbent, service.preview(remote()));

        Files.move(incumbent, game.resolve("elsewhere"));
        service.refresh();
        assertEquals(later, service.preview(remote()));
        assertEquals(later, service.prepare(remote()).folder());
        var after = service.configSnapshot().sourceSettings().get(REMOTE);
        assertNull(after.pinnedMirrorId());
        assertEquals(SIBLING, after.lastAutomaticMirrorId());
    }

    @Test
    void deferredLegacyTargetDoesNotAdvertiseACreationPath() throws Exception {
        Path legacy = folder("downloaded_worlds", "old-default");
        writeLegacyMirror(legacy);
        legacyConfig("old-default", ModConfig.SaveLocation.DOWNLOADED);
        var service = service();
        try (var ignored = net.minecraft.util.DirectoryLock.create(legacy)) {
            service.refresh();
            assertNull(service.preview(remote()));
            assertThrows(IOException.class, () -> service.prepare(remote()));
            assertFalse(Files.exists(legacy.resolveSibling("old-default_2")));
        }
    }

    @Test
    void explicitSelectionUsesOnlyDirectSynchronizedTargets() throws Exception {
        Path direct = folder("saves", "direct");
        writeMirror(direct, MIRROR, REMOTE);
        writeMirror(folder("saves", "grandchild"), "mirror:grandchild", MIRROR);
        Path snapshot = folder("saves", "snapshot");
        assertTrue(WorldMetadata
                        .create("snapshot:archive", WorldGraph.Kind.SNAPSHOT, REMOTE,
                                WorldGraph.Role.SNAPSHOT, SOURCE)
                        .save(snapshot));
        var service = service();
        assertEquals(direct, service.prepare(remote()).folder());
        assertThrows(
                IOException.class, () -> service.prepare(remote(), "mirror:grandchild", false, () -> true));
        assertThrows(
                IOException.class, () -> service.prepare(remote(), "snapshot:archive", false, () -> true));
        assertEquals(direct, service.preview(remote()));
    }

    private void writeLegacyMirror(Path output) throws IOException {
        JsonSupport.writeDocument(output.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", SOURCE, "sourceType",
                        "server", "mirrorId", MIRROR_UUID, "mirrorKind", "synchronized"));
    }
}
