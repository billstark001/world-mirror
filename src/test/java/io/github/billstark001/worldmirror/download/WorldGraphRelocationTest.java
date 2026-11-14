package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldGraphRelocationTest extends WorldGraphFixture {
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

        var move = service.worlds().move(selected, ModConfig.SaveLocation.SAVES);

        assertEquals(original, move.source());
        assertEquals(game.resolve("saves/original"), move.target());
        assertEquals(MIRROR, WorldMetadata.loadIfPresent(move.target()).orElseThrow().nodeId);
        assertEquals(move.target(), service.currentTarget(selected).folder());
        var reopened = service();
        reopened.refresh();
        assertEquals(move.target(), reopened.target(remote()).folder());

        var returned = reopened.worlds().move(reopened.target(remote()), ModConfig.SaveLocation.DOWNLOADED);
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
}
