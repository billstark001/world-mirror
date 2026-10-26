package io.github.billstark001.worldmirror.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class FileTransactionTest {
    @Test
    void cleanupFailureCannotTurnAKnownCommitIntoAFailedSave(@TempDir Path root) throws Exception {
        Path collision;
        try (FileTransaction transaction = new FileTransaction(root)) {
            Path staged = Files.writeString(transaction.stagingDirectory().resolve("document"), "committed");
            transaction.commit(Map.of(Path.of("document"), staged));
            Path journal = transaction.stagingDirectory().getParent();
            collision = Files.createDirectory(root.resolve(FileTransaction.CLEANUP_PREFIX
                    + journal.getFileName().toString().substring(FileTransaction.PREFIX.length())));
            Files.writeString(collision.resolve("retain"), "unknown cleanup collision");
            assertDoesNotThrow(transaction::close);
            assertEquals("committed", Files.readString(root.resolve("document")));
            assertTrue(Files.exists(journal.resolve("committed")));
        }
        Files.delete(collision.resolve("retain"));
        Files.delete(collision);
        FileTransaction.recover(root);
        assertEquals("committed", Files.readString(root.resolve("document")));
    }

    @Test
    void commitsVerifiedBytesAndRetiresItsJournal(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a"), "old");
        try (FileTransaction transaction = new FileTransaction(root)) {
            Path a = Files.writeString(transaction.stagingDirectory().resolve("a"), "new");
            Path b = Files.writeString(transaction.stagingDirectory().resolve("b"), "added");
            transaction.commit(Map.of(Path.of("a"), a, Path.of("b"), b));
        }
        assertEquals("new", Files.readString(root.resolve("a")));
        assertEquals("added", Files.readString(root.resolve("b")));
        try (var files = Files.list(root)) { assertEquals(2, files.count()); }
    }

    @Test
    void preparedReplacementIsRecoveredAfterRestart(@TempDir Path root) throws Exception {
        Path journal = Files.createDirectory(root.resolve(".worldmirror-transaction-fixture"));
        Files.createDirectory(journal.resolve("old"));
        Files.writeString(journal.resolve("old/0"), "original");
        Files.writeString(root.resolve("a"), "interrupted replacement");
        Properties properties = new Properties();
        properties.setProperty("schema", "2");
        properties.setProperty("count", "1");
        properties.setProperty("path.0", "a");
        properties.setProperty("existed.0", "true");
        properties.setProperty("sha256.0", FileProof.sha256(journal.resolve("old/0")));
        properties.setProperty("new.0", FileProof.sha256(root.resolve("a")));
        try (var output = Files.newOutputStream(journal.resolve("prepared"))) { properties.store(output, "fixture"); }
        Files.writeString(journal.resolve("manifest.sha256"), FileProof.sha256(journal.resolve("prepared")));
        FileTransaction.recover(root);
        assertEquals("original", Files.readString(root.resolve("a")));
        assertFalse(Files.exists(journal));
    }

    @Test
    void recoveryNeverFollowsAnEscapingTarget(@TempDir Path root) throws Exception {
        try (FileTransaction transaction = new FileTransaction(root)) {
            Path staged = Files.writeString(transaction.stagingDirectory().resolve("data"), "new");
            assertThrows(IOException.class, () -> transaction.commit(Map.of(Path.of("../escape"), staged)));
        }
        assertFalse(Files.exists(root.resolve("../escape")));
    }

    @Test
    void corruptRecoveryBackupCannotOverwriteTheCurrentFile(@TempDir Path root) throws Exception {
        Path journal = Files.createDirectory(root.resolve(".worldmirror-transaction-corrupt"));
        Files.createDirectory(journal.resolve("old"));
        Files.writeString(journal.resolve("old/0"), "original");
        Properties manifest = new Properties();
        manifest.setProperty("schema", "2");
        manifest.setProperty("count", "1"); manifest.setProperty("path.0", "data");
        manifest.setProperty("existed.0", "true");
        manifest.setProperty("sha256.0", FileProof.sha256(journal.resolve("old/0")));
        Files.writeString(journal.resolve("old/0"), "damaged backup");
        Files.writeString(root.resolve("data"), "current");
        manifest.setProperty("new.0", FileProof.sha256(root.resolve("data")));
        try (var output = Files.newOutputStream(journal.resolve("prepared"))) { manifest.store(output, "fixture"); }
        Files.writeString(journal.resolve("manifest.sha256"), FileProof.sha256(journal.resolve("prepared")));
        assertThrows(IOException.class, () -> FileTransaction.recover(root));
        assertEquals("current", Files.readString(root.resolve("data")));
        assertTrue(Files.exists(journal.resolve("old/0")));
    }

    @Test
    void worldLeaseRecoversNestedTransactionsOnlyWhenMarked(@TempDir Path world) throws Exception {
        Path region = Files.createDirectories(world.resolve("dimensions/minecraft/overworld/entities"));
        Path journal = Files.createDirectory(region.resolve(".worldmirror-transaction-fixture"));
        Files.createDirectory(journal.resolve("old"));
        Files.writeString(journal.resolve("old/0"), "original");
        Files.writeString(region.resolve("payload"), "interrupted");
        Properties manifest = new Properties();
        manifest.setProperty("schema", "2");
        manifest.setProperty("count", "1"); manifest.setProperty("path.0", "payload");
        manifest.setProperty("existed.0", "true");
        manifest.setProperty("sha256.0", FileProof.sha256(journal.resolve("old/0")));
        manifest.setProperty("new.0", FileProof.sha256(region.resolve("payload")));
        try (var output = Files.newOutputStream(journal.resolve("prepared"))) { manifest.store(output, "fixture"); }
        Files.writeString(journal.resolve("manifest.sha256"), FileProof.sha256(journal.resolve("prepared")));
        Files.writeString(world.resolve(".worldmirror-recovery-required"), "fixture");
        try (var lease = io.github.billstark001.worldmirror.io.WorldWriteLock.acquire(world)) {
            assertEquals("original", Files.readString(region.resolve("payload")));
            assertFalse(WorldRecovery.required(world));
        }
        assertFalse(Files.exists(journal));
    }

    @Test
    void unknownNewerContentPreventsEveryRollback(@TempDir Path root) throws Exception {
        Path journal = prepared(root, Map.of("a", new Images("old-a", "new-a"), "b", new Images("old-b", "new-b")));
        Files.writeString(root.resolve("b"), "external edit");
        assertThrows(IOException.class, () -> FileTransaction.recover(root));
        assertEquals("new-a", Files.readString(root.resolve("a")));
        assertEquals("external edit", Files.readString(root.resolve("b")));
        assertTrue(Files.exists(journal.resolve("old/0")));
        assertTrue(Files.exists(journal.resolve("old/1")));
    }

    @Test
    void aVerifiedCommitDoesNotDependOnUnusedBackups(@TempDir Path root) throws Exception {
        Path journal = prepared(root, Map.of("a", new Images("old", "committed")));
        Files.writeString(journal.resolve("committed"), FileProof.sha256(journal.resolve("prepared")));
        Files.delete(journal.resolve("old/0"));
        FileTransaction.recover(root);
        assertEquals("committed", Files.readString(root.resolve("a")));
        assertFalse(Files.exists(journal));
    }

    @Test
    void partialOrConflictingDecisionsRetainAllEvidence(@TempDir Path root) throws Exception {
        Path journal = prepared(root, Map.of("a", new Images("old", "new")));
        Files.writeString(journal.resolve("committed"), "");
        assertThrows(IOException.class, () -> FileTransaction.recover(root));
        assertEquals("new", Files.readString(root.resolve("a")));
        String proof = FileProof.sha256(journal.resolve("prepared"));
        Files.writeString(journal.resolve("committed"), proof);
        Files.writeString(journal.resolve("rolled-back"), proof);
        assertThrows(IOException.class, () -> FileTransaction.recover(root));
        assertTrue(Files.exists(journal.resolve("old/0")));
    }

    @Test
    void partialRollbackResumesAndRemovesOnlyProvenNewFiles(@TempDir Path root) throws Exception {
        Path journal = prepared(root, Map.of("a", new Images("old-a", "new-a"), "b", new Images(null, "added")));
        Files.writeString(root.resolve("a"), "old-a");
        FileTransaction.recover(root);
        assertEquals("old-a", Files.readString(root.resolve("a")));
        assertFalse(Files.exists(root.resolve("b")));
        assertFalse(Files.exists(journal));
        assertDoesNotThrow(() -> FileTransaction.recover(root));
    }

    @Test
    void unsupportedOldRecoveryDataIsRetainedInsteadOfGuessed(@TempDir Path root) throws Exception {
        Path journal = prepared(root, Map.of("a", new Images("old", "new")));
        Properties manifest = readManifest(journal);
        manifest.setProperty("schema", "1");
        writeManifest(journal, manifest);
        assertThrows(IOException.class, () -> FileTransaction.recover(root));
        assertEquals("new", Files.readString(root.resolve("a")));
        assertTrue(Files.exists(journal.resolve("old/0")));
    }

    @Test
    void invalidLaterTargetsCannotAuthorizeEarlierRestoration(@TempDir Path root) throws Exception {
        Path journal = prepared(root, Map.of("a", new Images("old-a", "new-a"), "b", new Images(null, "added")));
        Properties manifest = readManifest(journal);
        for (String invalid : new String[] {"../escape", "a/child", "session.lock"}) {
            manifest.setProperty("path.1", invalid);
            writeManifest(journal, manifest);
            assertThrows(IOException.class, () -> FileTransaction.recover(root));
            assertEquals("new-a", Files.readString(root.resolve("a")));
            assertTrue(Files.exists(journal));
        }
    }

    @Test
    void activeTransactionsCannotBeRecoveredOrDeleted(@TempDir Path root) throws Exception {
        try (FileTransaction active = new FileTransaction(root)) {
            Path staged = Files.writeString(active.stagingDirectory().resolve("a"), "new");
            assertThrows(IOException.class, () -> new FileTransaction(root));
            assertThrows(IOException.class, () -> FileTransaction.recover(root));
            assertEquals("new", Files.readString(staged));
            active.commit(Map.of(Path.of("a"), staged));
        }
        assertEquals("new", Files.readString(root.resolve("a")));
    }

    @Test
    void ownerLockProtectsLiveUnpreparedStaging(@TempDir Path root) throws Exception {
        Path journal = Files.createDirectory(root.resolve(".worldmirror-transaction-active"));
        try (var channel = java.nio.channels.FileChannel.open(journal.resolve("owner.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE); var lease = channel.lock()) {
            assertThrows(IOException.class, () -> FileTransaction.recover(root));
            assertTrue(Files.isDirectory(journal));
        }
        FileTransaction.recover(root);
        assertFalse(Files.exists(journal));
    }

    @Test
    void recoveryMarkerIsWrittenOncePerWorldLease(@TempDir Path world) throws Exception {
        Path directory = Files.createDirectory(world.resolve("entities"));
        Path marker = world.resolve(".worldmirror-recovery-required");
        try (var lease = io.github.billstark001.worldmirror.io.WorldWriteLock.acquire(world)) {
            replace(directory, "first");
            assertTrue(WorldRecovery.required(world));
            var sentinel = java.nio.file.attribute.FileTime.fromMillis(1_000L);
            Files.setLastModifiedTime(marker, sentinel);
            replace(directory, "second");
            assertEquals(sentinel, Files.getLastModifiedTime(marker));
        }
        assertFalse(WorldRecovery.required(world));
        assertEquals("second", Files.readString(directory.resolve("payload")));
    }

    private static void replace(Path root, String value) throws IOException {
        try (FileTransaction transaction = new FileTransaction(root)) {
            Path file = Files.writeString(transaction.stagingDirectory().resolve("payload"), value);
            transaction.commit(Map.of(Path.of("payload"), file));
        }
    }

    private record Images(String original, String replacement) {}

    private static Path prepared(Path root, Map<String, Images> images) throws IOException {
        Path journal = Files.createTempDirectory(root, ".worldmirror-transaction-fixture-");
        Files.createDirectory(journal.resolve("old"));
        Properties manifest = new Properties();
        manifest.setProperty("schema", "2");
        manifest.setProperty("count", Integer.toString(images.size()));
        int index = 0;
        for (String name : images.keySet().stream().sorted().toList()) {
            Images contents = images.get(name);
            manifest.setProperty("path." + index, name);
            manifest.setProperty("existed." + index, Boolean.toString(contents.original() != null));
            if (contents.original() != null) {
                Path backup = Files.writeString(journal.resolve("old/" + index), contents.original());
                manifest.setProperty("sha256." + index, FileProof.sha256(backup));
            }
            Path current = Files.writeString(root.resolve(name), contents.replacement());
            manifest.setProperty("new." + index, FileProof.sha256(current));
            index++;
        }
        writeManifest(journal, manifest);
        return journal;
    }

    private static Properties readManifest(Path journal) throws IOException {
        Properties manifest = new Properties();
        try (var input = Files.newInputStream(journal.resolve("prepared"))) { manifest.load(input); }
        return manifest;
    }

    private static void writeManifest(Path journal, Properties manifest) throws IOException {
        try (var output = Files.newOutputStream(journal.resolve("prepared"))) { manifest.store(output, "fixture"); }
        Files.writeString(journal.resolve("manifest.sha256"), FileProof.sha256(journal.resolve("prepared")));
    }
}
