package io.github.billstark001.worldmirror.conflict;

import io.github.billstark001.worldmirror.io.ChunkExporter;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Random;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConflictManagerPersistenceTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void streamsConflictChunksToVanillaRegionFiles(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(37, -12);
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.putInt("xPos", pos.getMinBlockX() >> 4);
        chunk.putInt("zPos", pos.getMinBlockZ() >> 4);
        chunk.putString("Status", "minecraft:full");

        assertTrue(savePending(world, pos, chunk, Level.OVERWORLD));
        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }

    @Test
    void reportsPersistenceFailureInsteadOfSettlingTheConflict(@TempDir Path root) throws Exception {
        Path notDirectory = root.resolve("not-a-directory");
        Files.writeString(notDirectory, "occupied");

        assertFalse(savePending(
                notDirectory, new ChunkPos(0, 0), new CompoundTag(), Level.OVERWORLD));
    }

    @Test
    void readOnlyConflictQueriesDoNotExpandLegacyEmptyRegionFiles(@TempDir Path world) throws Exception {
        seedMetadata(world);
        Path directory = Files.createDirectories(ConflictManager.getConflictDir(world, Level.OVERWORLD));
        Path placeholder = Files.createFile(directory.resolve("r.0.0.mca"));

        assertFalse(ConflictManager.hasConflict(world, new ChunkPos(0, 0), Level.OVERWORLD));
        assertTrue(ConflictManager.listConflicts(world, Level.OVERWORLD).isEmpty());
        assertEquals(0, ConflictManager.countAllConflicts(world));

        assertEquals(0L, Files.size(placeholder));
        ConflictManager.clearAllConflicts(target(world), false);
        assertFalse(Files.exists(placeholder));
    }

    @Test
    void conflictQueriesCanReadWindowsReadOnlyRegionFiles(@TempDir Path world) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        ChunkPos pos = new ChunkPos(0, 0);
        assertTrue(savePending(world, pos, chunk(pos, 1), Level.OVERWORLD));
        Path region = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
        Files.setAttribute(region, "dos:readonly", true);
        try {
            assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
            assertEquals(Set.of(pos), ConflictManager.listConflicts(world, Level.OVERWORLD));
            assertEquals(1, ConflictManager.countAllConflicts(world));
        } finally {
            Files.setAttribute(region, "dos:readonly", false);
        }
    }

    @Test
    void bulkOverwriteRetainsConflictWhenWorldWriteFails(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        chunk.putString("Status", "minecraft:full");
        assertTrue(savePending(world, pos, chunk, Level.OVERWORLD));

        Path blockedRegionFile = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD)
                .resolve("r.0.0.mca");
        Files.createDirectories(blockedRegionFile);

        ConflictManager.clearAllConflicts(target(world), true);

        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }

    @Test
    void oversizedConflictsCanBeListedAndResolvedWithoutLosingNeighbours(@TempDir Path world)
            throws Exception {
        ChunkPos largePos = new ChunkPos(0, 0);
        ChunkPos neighbour = new ChunkPos(1, 0);
        CompoundTag large = chunk(largePos, 1);
        byte[] payload = new byte[1_200_000];
        new Random(85L).nextBytes(payload);
        large.putByteArray("payload", payload);
        assertTrue(savePending(world, largePos, large, Level.OVERWORLD));
        assertTrue(savePending(world, neighbour, chunk(neighbour, 2), Level.OVERWORLD));
        assertTrue(ConflictManager.hasConflict(world, largePos, Level.OVERWORLD));
        assertEquals(Set.of(largePos, neighbour), ConflictManager.listConflicts(world, Level.OVERWORLD));
        assertEquals(2, ConflictManager.countAllConflicts(world));

        ConflictManager.resolveConflict(target(world), largePos, Level.OVERWORLD, true);

        assertFalse(ConflictManager.hasConflict(world, largePos, Level.OVERWORLD));
        assertTrue(ConflictManager.hasConflict(world, neighbour, Level.OVERWORLD));
        assertEquals(large, readWorldChunk(world, largePos));
        assertFalse(Files.exists(ConflictManager.getConflictDir(world, Level.OVERWORLD)
                .resolve("c.0.0.mcc")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bulkResolutionHandlesEveryDimensionAndRemovesExternalPayloads(
            boolean overwrite, @TempDir Path world) throws Exception {
        var custom = ResourceKey.create(Registries.DIMENSION, Identifier.parse("example:nested/caves"));
        List<ResourceKey<Level>> dimensions = List.of(Level.OVERWORLD, Level.NETHER, Level.END, custom);
        ChunkPos pos = new ChunkPos(-33, 71);
        CompoundTag nbt = chunk(pos, 4);
        byte[] payload = new byte[1_100_000];
        new Random(93L).nextBytes(payload);
        nbt.putByteArray("payload", payload);
        for (var dimension : dimensions) {
            assertTrue(savePending(world, pos, nbt, dimension));
            assertEquals(Set.of(pos), ConflictManager.listConflicts(world, dimension));
        }
        assertEquals(4, ConflictManager.countAllConflicts(world));

        ConflictManager.clearAllConflicts(target(world), overwrite);

        assertEquals(0, ConflictManager.countAllConflicts(world));
        assertFalse(Files.exists(world.resolve(ConflictManager.CONFLICT_ROOT)));
        for (var dimension : dimensions) {
            Path directory = ChunkExporter.regionDirForDimension(world, dimension);
            if (overwrite) assertEquals(nbt, readWorldChunk(world, pos, dimension));
            else if (Files.exists(directory)) {
                try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overwriteDoesNotHoldTheConflictLockWhileWaitingForTheWorldAndRetainsNewerConflicts(
            boolean bulk, @TempDir Path root) throws Exception {
        Path world = distinctLockWorld(root);
        ChunkPos pos = new ChunkPos(0, 0);
        Path worldRegion = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
        assertTrue(savePending(world, pos, chunk(pos, 1), Level.OVERWORLD));
        AtomicReference<Thread> resolvingThread = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> resolution;
            synchronized (McaWriteSupport.lockFor(worldRegion)) {
                resolution = executor.submit(() -> {
                    resolvingThread.set(Thread.currentThread());
                    started.countDown();
                    if (bulk) org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> ConflictManager.clearAllConflicts(target(world), true));
                    else ConflictManager.resolveConflict(target(world), pos, Level.OVERWORLD, true);
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (resolvingThread.get().getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline) Thread.sleep(1L);
                assertEquals(Thread.State.BLOCKED, resolvingThread.get().getState());
                var saved = executor.submit(() ->
                        savePending(world, pos, chunk(pos, 2), Level.OVERWORLD));
                assertTrue(saved.get(2, TimeUnit.SECONDS));
            }
            resolution.get(2, TimeUnit.SECONDS);
        }

        assertEquals(1, readWorldChunk(world, pos).getIntOr("marker", -1));
        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
        ConflictManager.resolveConflict(target(world), pos, Level.OVERWORLD, true);
        assertEquals(2, readWorldChunk(world, pos).getIntOr("marker", -1));
        assertFalse(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }

    @Test
    void concurrentResolutionsCannotApplyAnOlderSnapshotAfterANewerOne(@TempDir Path root) throws Exception {
        Path world = distinctLockWorld(root);
        ChunkPos pos = new ChunkPos(0, 0);
        Path worldRegion = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
        assertTrue(savePending(world, pos, chunk(pos, 1), Level.OVERWORLD));
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> first;
            java.util.concurrent.Future<?> second;
            synchronized (McaWriteSupport.lockFor(worldRegion)) {
                first = executor.submit(() -> {
                    firstThread.set(Thread.currentThread());
                    ConflictManager.resolveConflict(target(world), pos, Level.OVERWORLD, true);
                });
                awaitBlocked(firstThread);
                assertTrue(savePending(world, pos, chunk(pos, 2), Level.OVERWORLD));
                second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> ConflictManager.clearAllConflicts(target(world), true));
                });
                awaitBlocked(secondThread);
                var waiting = ManagementFactory.getThreadMXBean().getThreadInfo(secondThread.get().threadId());
                assertEquals(firstThread.get().threadId(), waiting.getLockOwnerId(),
                        "The second decision must wait for the first resolution, not compete for its world write");
            }
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
        assertEquals(2, readWorldChunk(world, pos).getIntOr("marker", -1));
        assertFalse(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }

    private static void awaitBlocked(AtomicReference<Thread> thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((thread.get() == null || thread.get().getState() != Thread.State.BLOCKED)
                && System.nanoTime() < deadline) Thread.sleep(1L);
        assertTrue(thread.get() != null && thread.get().getState() == Thread.State.BLOCKED);
    }

    private static Path distinctLockWorld(Path root) {
        for (int index = 0; index < 256; index++) {
            Path world = root.resolve("world-" + index);
            Path region = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
            Path conflicts = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
            if (McaWriteSupport.lockFor(region) != McaWriteSupport.lockFor(conflicts)) return world;
        }
        throw new AssertionError("Could not find distinct lock stripes for the concurrency fixture");
    }

    private static CompoundTag chunk(ChunkPos pos, int marker) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.putInt("xPos", pos.getMinBlockX() >> 4);
        chunk.putInt("zPos", pos.getMinBlockZ() >> 4);
        chunk.putString("Status", "minecraft:full");
        chunk.putInt("marker", marker);
        return chunk;
    }

    private static CompoundTag readWorldChunk(Path world, ChunkPos pos) throws Exception {
        return readWorldChunk(world, pos, Level.OVERWORLD);
    }

    private static CompoundTag readWorldChunk(Path world, ChunkPos pos, ResourceKey<Level> dimension)
            throws Exception {
        Path directory = ChunkExporter.regionDirForDimension(world, dimension);
        Path file = directory.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        try (RegionFile region = new RegionFile(new RegionStorageInfo("test", dimension, "chunk"),
                file, directory, false); var input = region.getChunkDataInputStream(pos)) {
            return NbtIo.read(input);
        }
    }
    private static io.github.billstark001.worldmirror.io.MirrorTarget target(Path world) {
        return new io.github.billstark001.worldmirror.io.MirrorTarget(world, "server:conflict-test");
    }

    private static boolean savePending(Path world, ChunkPos pos, CompoundTag chunk, ResourceKey<Level> dimension) {
        seedMetadata(world);
        return ConflictManager.saveConflict(world, pos, chunk, dimension);
    }
    private static void seedMetadata(Path world) {
        if (Files.exists(world) && !Files.isDirectory(world)) return;
        if (Files.notExists(world.resolve(io.github.billstark001.worldmirror.download.WorldMetadata.FILE_NAME))) {
            assertTrue(io.github.billstark001.worldmirror.io.WorldStructureCreator.createLoadableWorld(
                    world, "conflict fixture", true, true,
                    io.github.billstark001.worldmirror.io.WorldSettingsSnapshot.defaults()));
            var metadata = io.github.billstark001.worldmirror.download.WorldMetadata.create("server:conflict-test", "server", "synchronized");
            metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(),
                    io.github.billstark001.worldmirror.io.MirrorWorldgenAssets.ASSET_REVISION);
            metadata.save(world);
        }
    }

    @Test
    void unknownTargetIdentityKeepsPendingConflicts(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        assertTrue(savePending(world, pos, chunk(pos, 1), Level.OVERWORLD));
        var foreign = new io.github.billstark001.worldmirror.io.MirrorTarget(world, "server:other");
        assertFalse(ConflictManager.resolveConflict(foreign, pos, Level.OVERWORLD, true));
        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ConflictManager.clearAllConflicts(foreign, true));
    }

    @Test
    void invalidConflictEntriesAreUnavailableRatherThanEmpty(@TempDir Path world) throws Exception {
        Path directory = Files.createDirectories(ConflictManager.getConflictDir(world, Level.OVERWORLD));
        Files.createDirectory(directory.resolve("r.0.0.mca"));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> ConflictManager.countAllConflicts(world));
    }

}
