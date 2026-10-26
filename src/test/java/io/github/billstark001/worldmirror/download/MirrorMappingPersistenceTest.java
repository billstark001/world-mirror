package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorMappingPersistenceTest {
    private static final String SOURCE = "server:example.test";

    @Test
    void competingSnapshotsCannotBothOverwriteTheSameRoutingGeneration(@TempDir Path root) throws Exception {
        Path file = root.resolve("mirrors.json");
        MirrorMapping first = new MirrorMapping(file, root.resolve("game"));
        assertTrue(first.recordMirrorLocation(SOURCE, "DOWNLOADED", "original"));
        MirrorMapping second = MirrorMapping.load(file, root.resolve("game"));
        var threads = new java.util.concurrent.CopyOnWriteArrayList<Thread>();
        var started = new java.util.concurrent.CountDownLatch(2);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<Boolean> a;
        java.util.concurrent.Future<Boolean> b;
        try (var blocker = new io.github.billstark001.worldmirror.util.FileTransaction(root)) {
            a = executor.submit(() -> {
                threads.add(Thread.currentThread()); started.countDown();
                return first.recordMirrorLocation(SOURCE, "SAVES", "renamed");
            });
            b = executor.submit(() -> {
                threads.add(Thread.currentThread()); started.countDown();
                return second.setPerWorldConflictStrategy(SOURCE, "MANUAL");
            });
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (threads.stream().anyMatch(thread -> thread.getState() == Thread.State.RUNNABLE) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(a.isDone()); assertFalse(b.isDone());
        } finally { executor.shutdown(); }
        int accepted = (a.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                + (b.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
        assertEquals(1, accepted);
    }

    @Test
    void previewsRemainAvailableWhilePersistenceWaitsForDiskAuthority(@TempDir Path root) throws Exception {
        Path file = root.resolve("mirrors.json");
        MirrorMapping mapping = new MirrorMapping(file, root.resolve("game"));
        assertTrue(mapping.recordMirrorLocation(SOURCE, "DOWNLOADED", "original"));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<Boolean> writer;
        try (var blocker = new io.github.billstark001.worldmirror.util.FileTransaction(root)) {
            writer = executor.submit(() -> {
                started.countDown();
                return mapping.recordMirrorLocation(SOURCE, "SAVES", "pending");
            });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var preview = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                for (int i = 0; i < 10_000; i++) {
                    assertEquals("DOWNLOADED", mapping.getPerWorldSaveLocation(SOURCE));
                    assertEquals("original", mapping.previewResolvedFolderName(SOURCE, "DOWNLOADED"));
                }
                return true;
            });
            assertTrue(preview.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(writer.isDone());
        } finally { executor.shutdown(); }
        assertTrue(writer.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("SAVES", mapping.getPerWorldSaveLocation(SOURCE));
    }

    @Test
    void repeatedClaimsDoNotReplaceAnUnchangedRoutingDocument(@TempDir Path root) throws Exception {
        MirrorMapping mapping = new MirrorMapping(root.resolve("mirrors.json"), root.resolve("game"));
        assertTrue(mapping.recordMirrorLocation(SOURCE, "DOWNLOADED", "mirror"));
        Path claimed = mapping.claimOutputDirectory(SOURCE);
        var before = Files.readAttributes(root.resolve("mirrors.json"), java.nio.file.attribute.BasicFileAttributes.class);
        for (int i = 0; i < 12; i++) assertEquals(claimed, mapping.claimOutputDirectory(SOURCE));
        var after = Files.readAttributes(root.resolve("mirrors.json"), java.nio.file.attribute.BasicFileAttributes.class);
        assertEquals(before.fileKey(), after.fileKey());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
    }

    @Test
    void externalRoutingChangesCannotBeOverwrittenByAnOldSnapshot(@TempDir Path root) throws Exception {
        Path file = root.resolve("mirrors.json");
        MirrorMapping mapping = new MirrorMapping(file, root.resolve("game"));
        assertTrue(mapping.recordMirrorLocation(SOURCE, "SAVES", "original"));
        Files.writeString(file, "{external edit}");
        assertFalse(mapping.setPerWorldConflictStrategy(SOURCE, "MANUAL"));
        assertEquals("{external edit}", Files.readString(file));
        assertNull(mapping.getPerWorldConflictStrategy(SOURCE));
        for (String name : new String[] {".", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> mapping.recordResolvedFolderName(SOURCE, "SAVES", name));
        }
    }

    @Test
    void loadsLegacyNullMapsAndRoundTripsSettingsWithoutSerializingRuntimePaths(@TempDir Path root)
            throws Exception {
        Path file = root.resolve("config/mirrors.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"entries\":null,\"resolvedFolderNames\":null,"
                + "\"perWorldSaveLocation\":null,\"perWorldConflictStrategy\":null}");
        MirrorMapping mapping = MirrorMapping.load(file, root.resolve("game"));
        assertEquals("example.test", mapping.previewBaseFolderName(SOURCE));
        assertTrue(mapping.recordMirrorLocation(SOURCE, "SAVES", "镜像"));
        assertTrue(mapping.setPerWorldConflictStrategy(SOURCE, "MANUAL"));

        MirrorMapping reloaded = MirrorMapping.load(file, root.resolve("game"));
        assertEquals("镜像", reloaded.previewResolvedFolderName(SOURCE, "SAVES"));
        assertEquals("MANUAL", reloaded.getPerWorldConflictStrategy(SOURCE));
        assertEquals(root.resolve("game/saves/镜像"), reloaded.previewOutputPath(SOURCE, ModConfig.SaveLocation.SAVES));
        String stored = Files.readString(file);
        assertFalse(stored.contains("storageFile"));
        assertFalse(stored.contains("gameDirectory"));
        assertFalse(Files.exists(root.resolve("game")));
    }

    @Test
    void failedMutationsLeaveAllInMemoryRoutingUnchanged(@TempDir Path root) throws Exception {
        Path file = Files.createDirectory(root.resolve("blocked.json"));
        Files.writeString(file.resolve("keep.txt"), "keep");
        MirrorMapping mapping = new MirrorMapping(file, root.resolve("game"));

        assertFalse(mapping.recordMirrorLocation(SOURCE, "SAVES", "new-name"));
        assertNull(mapping.getPerWorldSaveLocation(SOURCE));
        assertNull(mapping.previewResolvedFolderName(SOURCE, "SAVES"));
        assertFalse(mapping.setPerWorldConflictStrategy(SOURCE, "IGNORE"));
        assertNull(mapping.getPerWorldConflictStrategy(SOURCE));
        assertEquals("keep", Files.readString(file.resolve("keep.txt")));
        assertThrows(IllegalArgumentException.class, () ->
                mapping.recordResolvedFolderName(SOURCE, "SAVES", "../escape"));
    }

    @Test
    void relocationRollsBackWhenItsMappingCannotBeSaved(@TempDir Path root) throws Exception {
        Path game = root.resolve("game");
        Path file = root.resolve("mirrors.json");
        MirrorMapping mapping = new MirrorMapping(file, game);
        assertTrue(mapping.recordMirrorLocation(SOURCE, "DOWNLOADED", "mirror"));
        Path source = Files.createDirectories(game.resolve("downloaded_worlds/mirror"));
        assertTrue(WorldMetadata.create(SOURCE, "server", "synchronized").save(source));
        Files.writeString(source.resolve("keep.txt"), "mirror contents");
        Files.delete(file);
        Files.createDirectory(file);

        var result = mapping.moveToLocation(SOURCE, ModConfig.SaveLocation.SAVES);

        assertFalse(result.success());
        assertEquals("io_error", result.failureCode());
        assertEquals("DOWNLOADED", mapping.getPerWorldSaveLocation(SOURCE));
        assertEquals("mirror contents", Files.readString(source.resolve("keep.txt")));
        assertFalse(Files.exists(result.target()));
    }

    @Test
    void failedClaimDoesNotLeaveEmptyCollisionDirectories(@TempDir Path root) throws Exception {
        Path game = root.resolve("game");
        Path file = root.resolve("mirrors.json");
        MirrorMapping mapping = new MirrorMapping(file, game);
        assertTrue(mapping.recordMirrorLocation(SOURCE, "DOWNLOADED", "mirror"));
        Files.delete(file);
        Files.createDirectory(file);

        assertThrows(IOException.class, () -> mapping.claimOutputDirectory(SOURCE));

        try (var children = Files.list(game.resolve("downloaded_worlds"))) {
            assertEquals(0L, children.count());
        }
        assertEquals("mirror", mapping.previewResolvedFolderName(SOURCE, "DOWNLOADED"));
    }
    @Test
    void damagedRoutingIsPreservedAndCannotClaimOrSave(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("mirrors.json"), "{damaged");
        MirrorMapping mapping = MirrorMapping.load(file, root.resolve("game"));
        assertFalse(mapping.recordMirrorLocation(SOURCE, "SAVES", "new-name"));
        assertThrows(IOException.class, () -> mapping.claimOutputDirectory(SOURCE));
        assertEquals("{damaged", Files.readString(file));
        assertFalse(Files.exists(root.resolve("game")));
    }

    @Test
    void unfinishedClaimsResumeTheSameOwnedDirectory(@TempDir Path root) throws Exception {
        MirrorMapping mapping = new MirrorMapping(root.resolve("mirrors.json"), root.resolve("game"));
        assertTrue(mapping.recordMirrorLocation(SOURCE, "DOWNLOADED", "mirror"));
        Path first = mapping.claimOutputDirectory(SOURCE);
        Files.writeString(first.resolve("level.dat"), "unfinished world data");
        assertEquals(first, mapping.claimOutputDirectory(SOURCE));
        assertEquals(first, mapping.claimOutputDirectory(SOURCE));
        try (var folders = Files.list(first.getParent())) { assertEquals(1, folders.count()); }
    }

    @Test
    void relocationRespectsAnotherMinecraftSessionLock(@TempDir Path root) throws Exception {
        MirrorMapping mapping = new MirrorMapping(root.resolve("mirrors.json"), root.resolve("game"));
        assertTrue(mapping.recordMirrorLocation(SOURCE, "DOWNLOADED", "mirror"));
        Path source = Files.createDirectories(root.resolve("game/downloaded_worlds/mirror"));
        assertTrue(WorldMetadata.create(SOURCE, "server", "synchronized").save(source));
        Files.writeString(source.resolve("keep"), "original");
        try (var nativeLease = net.minecraft.util.DirectoryLock.create(source)) {
            assertFalse(mapping.moveToLocation(SOURCE, ModConfig.SaveLocation.SAVES).success());
            assertEquals("original", Files.readString(source.resolve("keep")));
        }
        var moved = mapping.moveToLocation(SOURCE, ModConfig.SaveLocation.SAVES);
        assertTrue(moved.success());
        assertEquals("original", Files.readString(moved.target().resolve("keep")));
        var returned = mapping.moveToLocation(SOURCE, ModConfig.SaveLocation.DOWNLOADED);
        assertTrue(returned.success());
        assertEquals(source, returned.target());
        assertEquals("original", Files.readString(source.resolve("keep")));
    }

}
