package io.github.billstark001.worldmirror.conflict;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.SeedCaptureReceipt;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        Path directory = Files.createDirectories(ConflictManager.getConflictDir(world, Level.OVERWORLD));
        Files.createFile(directory.resolve("r.1.-1.mca"));
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

        assertFalse(savePending(notDirectory, new ChunkPos(0, 0), new CompoundTag(), Level.OVERWORLD));
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

        Path blockedRegionFile = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        Files.createDirectories(blockedRegionFile);

        ConflictManager.clearAllConflicts(target(world), true);

        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }

    @Test
    void publishingALegacyConflictCleansStaleBlockEntitiesAndRetiresTheOriginal(@TempDir Path world)
            throws Exception {
        var pos = new ChunkPos(0, 0);
        var stale = chunk(pos, 4);
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 1);
        chest.putInt("y", 63);
        chest.putInt("z", 1);
        var entries = new ListTag();
        entries.add(chest);
        stale.put("block_entities", entries);
        var blocks = new CompoundTag();
        var palette = new ListTag();
        palette.add(StringTag.valueOf("minecraft:stone"));
        blocks.put("palette", palette);
        var section = new CompoundTag();
        section.putByte("Y", (byte) 3);
        section.put("block_states", blocks);
        var sections = new ListTag();
        sections.add(section);
        stale.put("sections", sections);
        assertTrue(savePending(world, pos, stale, Level.OVERWORLD));

        assertTrue(ConflictManager.resolveConflict(target(world), pos, Level.OVERWORLD, true));
        assertTrue(readWorldChunk(world, pos).getListOrEmpty("block_entities").isEmpty());
        assertFalse(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }

    @Test
    void captureReceiptPublishesIndexAndEquivalentPendingTerrainIsNotRewritten(@TempDir Path world)
            throws Exception {
        seedMetadata(world);
        var target = target(world);
        var pos = new ChunkPos(0, 0);
        var capture = receiptedChunk(target, pos, 7, 312L);
        var newerSameTerrain = receiptedChunk(target, pos, 7, 456L);
        assertTrue(
                ConflictManager.saveConflicts(world, Level.OVERWORLD, Map.of(pos, capture), target.nodeId()));
        Path pending = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var evidence = Files.readAllBytes(pending);
        assertTrue(ConflictManager.saveConflicts(
                world, Level.OVERWORLD, Map.of(pos, newerSameTerrain), target.nodeId()));
        assertArrayEquals(evidence, Files.readAllBytes(pending));
        assertFalse(ConflictManager.saveConflict(world, pos, chunk(pos, 9), Level.OVERWORLD));
        assertArrayEquals(evidence, Files.readAllBytes(pending));

        assertTrue(ConflictManager.resolveConflict(target, pos, Level.OVERWORLD, true));
        assertFalse(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
        assertEquals(capture, readWorldChunk(world, pos));
        try (var db = ChunkDatabase.open(world, target.sourceId())) {
            assertEquals(new ChunkDatabase.ChunkRecord(0, 0, 312L, "world_mirror"),
                    db.queryChunk("minecraft:overworld", pos));
        }
    }

    @Test
    void bulkReceiptApprovalPreservesNewerCaptureAndPublishesNeighbor(@TempDir Path world) throws Exception {
        seedMetadata(world);
        var target = target(world);
        var protectedPos = new ChunkPos(0, 0);
        var neighbor = new ChunkPos(1, 0);
        var original = chunk(protectedPos, 1);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(protectedPos, original));
        try (var db = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(db.recordUpdates("minecraft:overworld", Map.of(protectedPos, 100L), "replay_import"));
        }
        var first = receiptedChunk(target, protectedPos, 2, 90L);
        var second = receiptedChunk(target, neighbor, 3, 200L);
        assertTrue(ConflictManager.saveConflicts(
                world, Level.OVERWORLD, Map.of(protectedPos, first, neighbor, second), target.nodeId()));

        assertFalse(ConflictManager.clearAllConflicts(target, true));
        assertTrue(ConflictManager.hasConflict(world, protectedPos, Level.OVERWORLD));
        assertFalse(ConflictManager.hasConflict(world, neighbor, Level.OVERWORLD));
        assertEquals(original, readWorldChunk(world, protectedPos));
        assertEquals(second, readWorldChunk(world, neighbor));
        try (var db = ChunkDatabase.open(world, target.sourceId())) {
            assertEquals(new ChunkDatabase.ChunkRecord(0, 0, 100L, "replay_import"),
                    db.queryChunk("minecraft:overworld", protectedPos));
            assertEquals(new ChunkDatabase.ChunkRecord(1, 0, 200L, "world_mirror"),
                    db.queryChunk("minecraft:overworld", neighbor));
        }
    }

    @Test
    void retiresAnAppliedConflictAfterNativeResave(@TempDir Path world) throws Exception {
        seedMetadata(world);
        var mirror = target(world);
        var pos = new ChunkPos(0, 0);
        var captured = receiptedChunk(mirror, pos, 2, 100L);
        assertTrue(ConflictManager.saveConflicts(
                world, Level.OVERWORLD, Map.of(pos, captured), mirror.nodeId()));
        var resaved = captured.copy();
        resaved.putLong("LastUpdate", 12_345L);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, resaved));
        try (var db = ChunkDatabase.open(world, mirror.sourceId())) {
            assertTrue(db.recordUpdates("minecraft:overworld", Map.of(pos, 100L), "world_mirror"));
        }
        var fileBefore = Files.readAllBytes(region);
        assertTrue(ConflictManager.resolveConflict(mirror, pos, Level.OVERWORLD, true));
        assertArrayEquals(fileBefore, Files.readAllBytes(region));
        assertFalse(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
        assertEquals(100L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
    }

    @Test
    void oneMalformedPendingReceiptDoesNotBlockTheRestOfItsRegion(@TempDir Path world) throws Exception {
        seedMetadata(world);
        var mirror = target(world);
        var rejected = new ChunkPos(0, 0);
        var good = new ChunkPos(1, 0);
        var corrupted = receiptedChunk(mirror, rejected, 1, 100L);
        corrupted.putLong("LastUpdate", 99L);
        var healthy = receiptedChunk(mirror, good, 2, 100L);
        assertEquals(Set.of(good),
                ConflictManager.stageConflicts(
                        world, Level.OVERWORLD, Map.of(rejected, corrupted, good, healthy), mirror.nodeId()));
        Path pending = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(pending, Level.OVERWORLD, Map.of(rejected, corrupted));

        assertFalse(ConflictManager.clearAllConflicts(mirror, true));
        assertEquals(Set.of(rejected), ConflictManager.listConflicts(world, Level.OVERWORLD));
        assertEquals(healthy, readWorldChunk(world, good));
    }

    private static CompoundTag receiptedChunk(
            io.github.billstark001.worldmirror.io.MirrorTarget target, ChunkPos pos, int marker, long time) {
        var result = chunk(pos, marker);
        SeedCaptureReceipt.write(
                result, target.nodeId(), new SeedCaptureReceipt.Receipt("world_mirror", time));
        return result;
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
        assertFalse(
                Files.exists(ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("c.0.0.mcc")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bulkResolutionHandlesEveryDimensionAndRemovesExternalPayloads(boolean overwrite, @TempDir Path world)
            throws Exception {
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
            Path directory = WorldSaveLayout.regionDirectory(world, dimension);
            if (overwrite)
                assertEquals(nbt, readWorldChunk(world, pos, dimension));
            else if (Files.exists(directory)) {
                try (var files = Files.list(directory)) {
                    assertEquals(0, files.count());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overwriteDoesNotHoldTheConflictLockWhileWaitingForTheWorldAndRetainsNewerConflicts(
            boolean bulk, @TempDir Path root) throws Exception {
        Path world = distinctLockWorld(root);
        ChunkPos pos = new ChunkPos(0, 0);
        Path worldRegion = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        assertTrue(savePending(world, pos, chunk(pos, 1), Level.OVERWORLD));
        AtomicReference<Thread> resolvingThread = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> resolution;
            synchronized (McaWriteSupport.lockFor(worldRegion)) {
                resolution = executor.submit(() -> {
                    resolvingThread.set(Thread.currentThread());
                    started.countDown();
                    if (bulk)
                        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                                () -> ConflictManager.clearAllConflicts(target(world), true));
                    else
                        ConflictManager.resolveConflict(target(world), pos, Level.OVERWORLD, true);
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (resolvingThread.get().getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline)
                    Thread.sleep(1L);
                assertEquals(Thread.State.BLOCKED, resolvingThread.get().getState());
                var saved = executor.submit(() -> savePending(world, pos, chunk(pos, 2), Level.OVERWORLD));
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
        Path worldRegion = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
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
                    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                            () -> ConflictManager.clearAllConflicts(target(world), true));
                });
                awaitBlocked(secondThread);
                var waiting =
                        ManagementFactory.getThreadMXBean().getThreadInfo(secondThread.get().threadId());
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
                && System.nanoTime() < deadline)
            Thread.sleep(1L);
        assertTrue(thread.get() != null && thread.get().getState() == Thread.State.BLOCKED);
    }

    private static Path distinctLockWorld(Path root) {
        for (int index = 0; index < 256; index++) {
            Path world = root.resolve("world-" + index);
            Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
            Path conflicts = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
            if (McaWriteSupport.lockFor(region) != McaWriteSupport.lockFor(conflicts))
                return world;
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
        Path directory = WorldSaveLayout.regionDirectory(world, dimension);
        Path file = directory.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        try (RegionFile region = new RegionFile(
                     new RegionStorageInfo("test", dimension, "chunk"), file, directory, false);
                var input = region.getChunkDataInputStream(pos)) {
            return NbtIo.read(input);
        }
    }
    private static io.github.billstark001.worldmirror.io.MirrorTarget target(Path world) {
        return new io.github.billstark001.worldmirror.io.MirrorTarget(
                world, "mirror:conflict-test", "server:conflict-test");
    }

    private static boolean savePending(
            Path world, ChunkPos pos, CompoundTag chunk, ResourceKey<Level> dimension) {
        seedMetadata(world);
        return ConflictManager.saveConflict(world, pos, chunk, dimension);
    }
    private static void seedMetadata(Path world) {
        if (Files.exists(world) && !Files.isDirectory(world))
            return;
        if (Files.notExists(world.resolve(WorldMetadata.FILE_NAME))) {
            assertTrue(io.github.billstark001.worldmirror.io.WorldStructureCreator.createVoidWorld(world,
                    "conflict fixture",
                    io.github.billstark001.worldmirror.io.WorldSettingsSnapshot.defaults()));
            var metadata = WorldMetadata.create("mirror:conflict-test", WorldGraph.Kind.MIRROR,
                    "remote:conflict-test", WorldGraph.Role.SYNC, "server:conflict-test");
            metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(),
                    io.github.billstark001.worldmirror.io.MirrorWorldgenAssets.ASSET_REVISION);
            metadata.save(world);
        }
    }

    @Test
    void unknownTargetIdentityKeepsPendingConflicts(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        assertTrue(savePending(world, pos, chunk(pos, 1), Level.OVERWORLD));
        var foreign = new io.github.billstark001.worldmirror.io.MirrorTarget(
                world, "mirror:other", "server:conflict-test");
        assertFalse(ConflictManager.resolveConflict(foreign, pos, Level.OVERWORLD, true));
        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> ConflictManager.clearAllConflicts(foreign, true));
    }

    @Test
    void invalidConflictEntriesAreUnavailableRatherThanEmpty(@TempDir Path world) throws Exception {
        Path directory = Files.createDirectories(ConflictManager.getConflictDir(world, Level.OVERWORLD));
        Files.createDirectory(directory.resolve("r.0.0.mca"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> ConflictManager.countAllConflicts(world));
    }
}
