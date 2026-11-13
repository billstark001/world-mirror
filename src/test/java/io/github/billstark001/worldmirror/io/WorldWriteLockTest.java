package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.DirectoryLock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldWriteLockTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nestedWorldWritersRetainTheVanillaLockUntilTheLastClose(@TempDir Path world) throws Exception {
        try (WorldWriteLock outer = WorldWriteLock.acquire(world)) {
            assertVanillaCannotOpen(world);
            try (WorldWriteLock inner = WorldWriteLock.acquire(world.resolve("."))) {
                assertVanillaCannotOpen(world);
            }
            assertVanillaCannotOpen(world);
        }
        assertFalse(DirectoryLock.isLocked(world));
    }

    @Test
    void aVanillaOpenWorldIsRejectedAndFailedAcquisitionCanBeRetried(@TempDir Path world) throws Exception {
        try (DirectoryLock vanilla = DirectoryLock.create(world)) {
            assertThrows(IOException.class, () -> WorldWriteLock.acquire(world));
        }
        try (WorldWriteLock acquired = WorldWriteLock.acquire(world)) {
            assertVanillaCannotOpen(world);
        }
    }

    @Test
    void symbolicSessionLockDoesNotChangeItsTarget(@TempDir Path root) throws Exception {
        Path world = Files.createDirectory(root.resolve("world"));
        byte[] original = {1, 2, 3, 4};
        Path outside = Files.write(root.resolve("outside.lock"), original);
        try {
            Files.createSymbolicLink(world.resolve(DirectoryLock.LOCK_FILE), outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable: " + unavailable);
        }

        assertThrows(IOException.class, () -> WorldWriteLock.acquire(world));
        assertArrayEquals(original, Files.readAllBytes(outside));
    }

    @Test
    void parallelWritersSerializeBeforeTryingTheOperatingSystemLock(@TempDir Path world) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Boolean> second;
            try (WorldWriteLock first = WorldWriteLock.acquire(world)) {
                CountDownLatch started = new CountDownLatch(1);
                second = executor.submit(() -> {
                    started.countDown();
                    try (WorldWriteLock ignored = WorldWriteLock.acquire(world)) {
                        assertVanillaCannotOpen(world);
                        return true;
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            }
            assertTrue(second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertFalse(DirectoryLock.isLocked(world));
    }

    @Test
    void worldSettingsCannotBePatchedWhileVanillaHasTheSaveOpen(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createVoidWorld(world, "test", WorldSettingsSnapshot.defaults()));
        var metadata = WorldMetadata.create("mirror:settings", WorldGraph.Kind.MIRROR, "local:settings",
                WorldGraph.Role.SYNC, "local:settings");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(),
                MirrorWorldgenAssets.ASSET_REVISION);
        assertTrue(metadata.save(world));
        byte[] original = Files.readAllBytes(world.resolve("level.dat"));
        WorldSettingsSnapshot changed = new WorldSettingsSnapshot(12_345, 12_345, false, false, 2);
        try (DirectoryLock vanilla = DirectoryLock.create(world)) {
            assertFalse(WorldStructureCreator.syncWorldSetting(
                    new MirrorTarget(world, "mirror:settings", "local:settings"), changed,
                    WorldStructureCreator.Setting.TIME));
            assertArrayEquals(original, Files.readAllBytes(world.resolve("level.dat")));
        }
        assertTrue(WorldStructureCreator.syncWorldSetting(
                new MirrorTarget(world, "mirror:settings", "local:settings"), changed,
                WorldStructureCreator.Setting.TIME));
    }

    private static void assertVanillaCannotOpen(Path world) {
        try (DirectoryLock ignored = DirectoryLock.create(world)) {
            org.junit.jupiter.api.Assertions.fail("Vanilla opened a world held by World Mirror");
        } catch (IOException | OverlappingFileLockException expected) {
            // Windows can reject the channel write first; Linux reaches tryLock.
            // Both are native rejections, independent of our IOException facade.
        }
    }
}
