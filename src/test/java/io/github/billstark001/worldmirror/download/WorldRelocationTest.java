package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldRelocationTest {
    private static final String SOURCE = "server:relocation.test";
    private static final String PARENT = "remote:relocation";
    private static final String NODE = "mirror:ab804f40-3cf2-4a92-a6e9-eec94dc70fbc";
    @TempDir Path root;

    @Test
    void anInterruptedMoveRecoversAccordingToDurableRouting() throws Exception {
        Fixture pending = fixture();
        Files.move(pending.source().resolve("a"), pending.target().resolve("a"));
        WorldRelocation.recover(pending.target(), pending.source(), allowed());
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        assertEquals("b contents", Files.readString(pending.source().resolve("b")));
        assertFalse(Files.exists(pending.source().resolve(WorldRelocation.JOURNAL)));
        assertFalse(Files.exists(pending.target()));
    }

    @Test
    void aCommittedMoveFinishesForwardAndRetainsTheOldReservation() throws Exception {
        Fixture pending = fixture();
        Files.move(pending.source().resolve("a"), pending.target().resolve("a"));
        WorldRelocation.recover(pending.source(), pending.target(), allowed());
        assertEquals("a contents", Files.readString(pending.target().resolve("a")));
        assertEquals("b contents", Files.readString(pending.target().resolve("b")));
        assertTrue(MirrorClaim.ownedBy(pending.source(), NODE));
        assertFalse(Files.exists(pending.target().resolve(WorldRelocation.JOURNAL)));
    }

    @Test
    void laterAmbiguityPreventsMovingAnyEarlierEntry() throws Exception {
        Fixture pending = fixture();
        Files.writeString(pending.target().resolve("b"), "other b");
        assertThrows(IOException.class,
                () -> WorldRelocation.recover(pending.source(), pending.target(), allowed()));
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        assertEquals("b contents", Files.readString(pending.source().resolve("b")));
        assertEquals("other b", Files.readString(pending.target().resolve("b")));
    }

    @Test
    void anotherMirrorWithTheSameSourceCannotBeAdopted() throws Exception {
        Fixture pending = fixture();
        assertTrue(WorldMetadata
                        .create("mirror:" + UUID.randomUUID(), WorldGraph.Kind.MIRROR, PARENT,
                                WorldGraph.Role.SYNC, SOURCE)
                        .save(pending.target()));
        assertThrows(IOException.class,
                () -> WorldRelocation.recover(pending.source(), pending.target(), allowed()));
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        assertTrue(Files.exists(pending.target().resolve(WorldMetadata.FILE_NAME)));
    }

    @Test
    void malformedOrConflictingPlansRetainBothRoots() throws Exception {
        Fixture pending = fixture();
        var changed = new java.util.HashMap<>(pending.plan());
        changed.put("operation", UUID.randomUUID());
        JsonSupport.writeDocument(pending.target().resolve(WorldRelocation.JOURNAL), changed);
        assertThrows(IOException.class,
                () -> WorldRelocation.recover(pending.source(), pending.target(), allowed()));
        changed = new java.util.HashMap<>(pending.plan());
        changed.put("entries", List.of("a", "../escape", WorldMetadata.FILE_NAME));
        JsonSupport.writeDocument(pending.source().resolve(WorldRelocation.JOURNAL), changed);
        JsonSupport.writeDocument(pending.target().resolve(WorldRelocation.JOURNAL), changed);
        assertThrows(IOException.class,
                () -> WorldRelocation.recover(pending.source(), pending.target(), allowed()));
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
    }

    @Test
    void unexpectedNewFilesAndNativeLocksPreventRecovery() throws Exception {
        Fixture pending = fixture();
        Files.writeString(pending.target().resolve("new-player-data"), "keep");
        assertThrows(IOException.class,
                () -> WorldRelocation.recover(pending.source(), pending.target(), allowed()));
        Files.delete(pending.target().resolve("new-player-data"));
        try (var lock = net.minecraft.util.DirectoryLock.create(pending.target())) {
            assertThrows(IOException.class,
                    () -> WorldRelocation.recover(pending.source(), pending.target(), allowed()));
            assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        }
    }

    @Test
    void legacyJournalCompletesUsingItsLegacyRoutingWitness() throws Exception {
        Path source = Files.createDirectories(root.resolve("downloaded_worlds/legacy"));
        Path target = Files.createDirectories(root.resolve("saves/legacy"));
        String legacyId = NODE.substring("mirror:".length());
        JsonSupport.writeDocument(source.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", SOURCE, "sourceType",
                        "server", "mirrorId", legacyId, "mirrorKind", "synchronized"));
        JsonSupport.writeDocument(target.resolve(MirrorClaim.FILE_NAME),
                Map.of("sourceId", SOURCE, "token", UUID.randomUUID()));
        var plan = Map.of("schema", 1, "operation", UUID.randomUUID(), "sourceId", SOURCE, "mirrorId",
                legacyId, "source", source.toString(), "target", target.toString(), "entries",
                List.of(WorldMetadata.FILE_NAME));
        JsonSupport.writeDocument(source.resolve(WorldRelocation.JOURNAL), plan);
        JsonSupport.writeDocument(target.resolve(WorldRelocation.JOURNAL), plan);
        var journal = WorldRelocation.journal(source);
        assertEquals(NODE, journal.nodeId());
        assertEquals(source, journal.source());
        assertEquals(target, journal.target());

        WorldRelocation.recover(source, target, allowed());

        assertTrue(Files.isRegularFile(target.resolve(WorldMetadata.FILE_NAME)));
        assertFalse(Files.exists(target.resolve(MirrorClaim.FILE_NAME)));
        assertFalse(Files.exists(source));
    }

    @Test
    void refusesAnExistingReservationForAnotherMirrorOfTheSameSource() throws Exception {
        Path source = Files.createDirectories(root.resolve("downloaded_worlds/actual"));
        Path target = Files.createDirectories(root.resolve("saves/actual"));
        assertTrue(WorldMetadata.create(NODE, WorldGraph.Kind.MIRROR, PARENT, WorldGraph.Role.SYNC, SOURCE)
                        .save(source));
        MirrorClaim.create(target, "mirror:" + UUID.randomUUID(), PARENT, SOURCE);

        assertThrows(
                IOException.class, () -> WorldRelocation.move(source, target, binding(source), () -> true));
        assertTrue(Files.isRegularFile(source.resolve(WorldMetadata.FILE_NAME)));
        assertFalse(Files.exists(target.resolve(WorldMetadata.FILE_NAME)));
    }

    @Test
    void rollsBackAnUnpublishedMove() throws Exception {
        Path source = Files.createDirectories(root.resolve("downloaded_worlds/actual"));
        Path target = root.resolve("saves/actual");
        assertTrue(WorldMetadata.create(NODE, WorldGraph.Kind.MIRROR, PARENT, WorldGraph.Role.SYNC, SOURCE)
                        .save(source));
        Files.writeString(source.resolve("entry"), "original data");

        assertThrows(
                IOException.class, () -> WorldRelocation.move(source, target, binding(source), () -> false));

        assertEquals("original data", Files.readString(source.resolve("entry")));
        assertEquals(NODE, WorldMetadata.loadIfPresent(source).orElseThrow().nodeId);
        assertFalse(Files.exists(source.resolve(WorldRelocation.JOURNAL)));
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesToMoveAnIdWhoseParentChanged() throws Exception {
        Path source = Files.createDirectories(root.resolve("downloaded_worlds/actual"));
        Path target = root.resolve("saves/actual");
        assertTrue(WorldMetadata
                        .create(NODE, WorldGraph.Kind.MIRROR, "remote:different-parent", WorldGraph.Role.SYNC,
                                SOURCE)
                        .save(source));
        assertThrows(
                IOException.class, () -> WorldRelocation.move(source, target, binding(source), () -> true));
        assertFalse(Files.exists(target));
        assertEquals(
                "remote:different-parent", WorldMetadata.loadIfPresent(source).orElseThrow().parentNodeId);
    }

    private static WorldGraphService.Target binding(Path source) {
        return new WorldGraphService.Target(NODE, PARENT, SOURCE, source);
    }

    private record Fixture(Path source, Path target, Map<String, Object> plan) {}

    private Fixture fixture() throws IOException {
        Path source = Files.createDirectories(root.resolve("downloaded_worlds/mirror"));
        Path target = Files.createDirectories(root.resolve("saves/mirror"));
        WorldMetadata metadata =
                WorldMetadata.create(NODE, WorldGraph.Kind.MIRROR, PARENT, WorldGraph.Role.SYNC, SOURCE);
        assertTrue(metadata.save(source));
        Files.writeString(source.resolve("a"), "a contents");
        Files.writeString(source.resolve("b"), "b contents");
        Map<String, Object> plan = Map.of("schema", 2, "operation", UUID.randomUUID(), "sourceId", SOURCE,
                "nodeId", NODE, "parentNodeId", PARENT, "source", source.toString(), "target",
                target.toString(), "entries", List.of("a", "b", WorldMetadata.FILE_NAME));
        JsonSupport.writeDocument(source.resolve(WorldRelocation.JOURNAL), plan);
        JsonSupport.writeDocument(target.resolve(WorldRelocation.JOURNAL), plan);
        return new Fixture(source, target, plan);
    }

    private Set<Path> allowed() {
        return Set.of(root.resolve("downloaded_worlds"), root.resolve("saves"));
    }
}
