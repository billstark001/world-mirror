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
    @TempDir Path root;

    @Test
    void anInterruptedMoveRecoversAccordingToDurableRouting() throws Exception {
        Fixture pending = fixture();
        Files.move(pending.source().resolve("a"), pending.target().resolve("a"));
        WorldRelocation.recover(pending.target(), SOURCE, pending.source(), allowed());
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        assertEquals("b contents", Files.readString(pending.source().resolve("b")));
        assertFalse(Files.exists(pending.source().resolve(WorldRelocation.JOURNAL)));
        assertFalse(Files.exists(pending.target()));
    }

    @Test
    void aCommittedMoveFinishesForwardAndRetainsTheOldReservation() throws Exception {
        Fixture pending = fixture();
        Files.move(pending.source().resolve("a"), pending.target().resolve("a"));
        WorldRelocation.recover(pending.source(), SOURCE, pending.target(), allowed());
        assertEquals("a contents", Files.readString(pending.target().resolve("a")));
        assertEquals("b contents", Files.readString(pending.target().resolve("b")));
        assertTrue(MirrorClaim.ownedBy(pending.source(), SOURCE));
        assertFalse(Files.exists(pending.target().resolve(WorldRelocation.JOURNAL)));
    }

    @Test
    void laterAmbiguityPreventsMovingAnyEarlierEntry() throws Exception {
        Fixture pending = fixture();
        Files.writeString(pending.target().resolve("b"), "other b");
        assertThrows(IOException.class, () -> WorldRelocation.recover(pending.source(), SOURCE, pending.target(), allowed()));
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        assertEquals("b contents", Files.readString(pending.source().resolve("b")));
        assertEquals("other b", Files.readString(pending.target().resolve("b")));
    }

    @Test
    void anotherMirrorWithTheSameSourceCannotBeAdopted() throws Exception {
        Fixture pending = fixture();
        assertTrue(WorldMetadata.create(SOURCE, "server", "synchronized").save(pending.target()));
        assertThrows(IOException.class, () -> WorldRelocation.recover(pending.source(), SOURCE, pending.target(), allowed()));
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        assertTrue(Files.exists(pending.target().resolve(WorldMetadata.FILE_NAME)));
    }

    @Test
    void malformedOrConflictingPlansRetainBothRoots() throws Exception {
        Fixture pending = fixture();
        var changed = new java.util.HashMap<>(pending.plan());
        changed.put("operation", UUID.randomUUID());
        JsonSupport.writeDocument(pending.target().resolve(WorldRelocation.JOURNAL), changed);
        assertThrows(IOException.class, () -> WorldRelocation.recover(pending.source(), SOURCE, pending.target(), allowed()));
        changed = new java.util.HashMap<>(pending.plan());
        changed.put("entries", List.of("a", "../escape", WorldMetadata.FILE_NAME));
        JsonSupport.writeDocument(pending.source().resolve(WorldRelocation.JOURNAL), changed);
        JsonSupport.writeDocument(pending.target().resolve(WorldRelocation.JOURNAL), changed);
        assertThrows(IOException.class, () -> WorldRelocation.recover(pending.source(), SOURCE, pending.target(), allowed()));
        assertEquals("a contents", Files.readString(pending.source().resolve("a")));
    }

    @Test
    void unexpectedNewFilesAndNativeLocksPreventRecovery() throws Exception {
        Fixture pending = fixture();
        Files.writeString(pending.target().resolve("new-player-data"), "keep");
        assertThrows(IOException.class, () -> WorldRelocation.recover(pending.source(), SOURCE, pending.target(), allowed()));
        Files.delete(pending.target().resolve("new-player-data"));
        try (var lock = net.minecraft.util.DirectoryLock.create(pending.target())) {
            assertThrows(IOException.class, () -> WorldRelocation.recover(pending.source(), SOURCE, pending.target(), allowed()));
            assertEquals("a contents", Files.readString(pending.source().resolve("a")));
        }
    }

    private record Fixture(Path source, Path target, Map<String, Object> plan) {}

    private Fixture fixture() throws IOException {
        Path source = Files.createDirectories(root.resolve("downloaded_worlds/mirror"));
        Path target = Files.createDirectories(root.resolve("saves/mirror"));
        WorldMetadata metadata = WorldMetadata.create(SOURCE, "server", "synchronized");
        assertTrue(metadata.save(source));
        Files.writeString(source.resolve("a"), "a contents");
        Files.writeString(source.resolve("b"), "b contents");
        Map<String, Object> plan = Map.of("schema", 1, "operation", UUID.randomUUID(), "sourceId", SOURCE,
                "mirrorId", metadata.mirrorId, "source", source.toString(), "target", target.toString(),
                "entries", List.of("a", "b", WorldMetadata.FILE_NAME));
        JsonSupport.writeDocument(source.resolve(WorldRelocation.JOURNAL), plan);
        JsonSupport.writeDocument(target.resolve(WorldRelocation.JOURNAL), plan);
        return new Fixture(source, target, plan);
    }

    private Set<Path> allowed() { return Set.of(root.resolve("downloaded_worlds"), root.resolve("saves")); }
}
