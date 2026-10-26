package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Full coordinator transactions against temporary native regions, metadata, and SQLite. */
class ExportTransactionTest {
    @Test
    void aDamagedNeighborCannotAuthorizeHeaderRepairOrEraseHealthyReceipts(@TempDir Path world) throws Exception {
        assertTrue(DownloadExportCoordinator.write(job(terrain(1), world, new OverwriteResolver())).metadataCommitted());
        Path file = io.github.billstark001.worldmirror.io.ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
        byte[] bytes = Files.readAllBytes(file);
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 1 << 8 | 1); // Neighbor illegally overlaps the header.
        Files.write(file, bytes);
        var outcome = DownloadExportCoordinator.write(job(terrain(2), world, new OverwriteResolver()));
        assertEquals(0, outcome.result().totalWritten());
        assertTrue(outcome.remaining().hasTerrain());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(Set.of(new ChunkPos(1, 0)), outcome.result().unreadableChunks().get(Level.OVERWORLD));
        try (var db = ChunkDatabase.open(world, "server:transaction")) {
            assertTrue(db.shouldSkipUpdate("minecraft:overworld", new ChunkPos(0, 0), "world_mirror", 100L));
        }
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void failedEntityRegionsDoNotBlockSuccessiveTerrainAndMetadataCommits(@TempDir Path world) throws Exception {
        Path entities = io.github.billstark001.worldmirror.io.ChunkExporter.dimensionDirForDimension(world, Level.OVERWORLD)
                .resolve("entities/r.0.0.mca");
        Files.createDirectories(entities.getParent());
        byte[] corrupt = {1, 2, 3, 4, 5};
        Files.write(entities, corrupt);
        UUID uuid = new UUID(88, 1);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        entity.putIntArray("UUID", net.minecraft.core.UUIDUtil.uuidToIntArray(uuid));
        var update = new EntityTracker.ChunkUpdate(1, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, new EntityTracker.EntityRecord(uuid, Set.of(uuid), entity)), Set.of());
        var status = new ExportStatusStore();
        ExportWork.Frozen remaining = null;
        for (int revision = 1; revision <= 2; revision++) {
            var base = terrain(revision);
            var work = new ExportWork.Frozen(
                    base.context(),
                    base.terrain(),
                    Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)),
                    Map.of(),
                    base.settings(),
                    0,
                    OptionalLong.empty());
            status.queued(work);
            var phases = new java.util.ArrayList<io.github.billstark001.worldmirror.io.ExportProgress.Part>();
            var outcome = DownloadExportCoordinator.write(new DownloadExportCoordinator.ExportJob(work, () -> world,
                    ModConfig.DownloadPipelineMode.STABLE_PERIODIC, false, false, new OverwriteResolver(), -1L,
                    (part, dimension, chunks) -> {
                        status.writing(work, part, dimension, chunks);
                        phases.add(part);
                        assertEquals(ExportStatus.State.WRITING, status.inRange(work.context().source().id(), dimension,0,0,0,0).getFirst().state());
                    }));
            assertEquals(java.util.List.of(io.github.billstark001.worldmirror.io.ExportProgress.Part.TERRAIN,
                    io.github.billstark001.worldmirror.io.ExportProgress.Part.ENTITIES), phases);
            assertEquals(1, status.finish(work, outcome).counts().partial());
            remaining = outcome.remaining();
            assertTrue(outcome.result().chunkWritesSuccessful());
            assertFalse(outcome.result().entityWritesSuccessful());
            assertTrue(outcome.indexCommitted());
            assertTrue(outcome.metadataCommitted());
            assertFalse(outcome.remaining().hasTerrain());
            assertTrue(outcome.remaining().hasEntities());
            assertEquals(revision * 100L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
            assertArrayEquals(corrupt, Files.readAllBytes(entities));
        }
        Files.delete(entities);
        var retry = remaining.retry();
        assertFalse(retry.hasTerrain(), "entity recovery must not rewrite committed terrain");
        status.queued(retry);
        var recovered = DownloadExportCoordinator.write(job(retry, world, new OverwriteResolver()));
        assertTrue(recovered.successful());
        assertEquals(0, recovered.result().totalWritten());
        assertTrue(status.finish(retry, recovered).settled());
        assertEquals(1, status.health(retry.context().source().id()).counts().succeeded());
    }

    @Test
    void indexFailureRetainsVerifiedTerrainUntilItsReceiptIsCommitted(@TempDir Path world) throws Exception {
        var status = new ExportStatusStore();
        assertTrue(DownloadExportCoordinator.write(job(terrain(1), world, new OverwriteResolver())).successful());
        Path database = world.resolve(io.github.billstark001.worldmirror.format.MirrorFormat.DATABASE_FILE);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath()); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_capture BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL, 'injected'); END");
            var work = terrain(2);
            status.queued(work);
            var failed = DownloadExportCoordinator.write(job(work, world, new OverwriteResolver()));
            assertEquals(1, status.finish(work, failed).counts().failed());
            assertEquals(ExportStatus.FailureStage.INDEX, status.health(work.context().source().id()).stage());
            assertTrue(failed.result().chunkWritesSuccessful());
            assertFalse(failed.indexCommitted());
            assertTrue(failed.durableWritten().isEmpty());
            assertTrue(failed.remaining().hasTerrain());
            assertEquals(100L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
            sql.execute("DROP TRIGGER reject_capture");
            var retry = DownloadExportCoordinator.write(job(failed.remaining().retry(), world, new OverwriteResolver()));
            assertTrue(retry.successful());
            assertFalse(retry.remaining().hasTerrain());
            assertEquals(200L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
            assertTrue(status.finish(failed.remaining(), retry).settled());
        }
    }

    @Test
    void metadataFailureKeepsOnlyTheDurableTimeForAMetadataOnlyRetry(@TempDir Path world) throws Exception {
        var status = new ExportStatusStore();
        assertTrue(DownloadExportCoordinator.write(job(terrain(1), world, new OverwriteResolver())).successful());
        Path metadata = world.resolve(WorldMetadata.FILE_NAME), backup = world.resolve("metadata-backup.json");
        ConflictResolver obstructMetadataAfterInspection = context -> {
            try {
                Files.move(metadata, backup);
                Files.createDirectory(metadata);
                Files.writeString(metadata.resolve("blocker"), "injected metadata failure");
            } catch (java.io.IOException failure) { throw new UncheckedIOException(failure); }
            return true;
        };
        var work = terrain(2);
        status.queued(work);
        var failed = DownloadExportCoordinator.write(job(work, world, obstructMetadataAfterInspection));
        var health = status.finish(work, failed);
        assertEquals(1, health.counts().partial());
        assertTrue(health.blocked());
        assertEquals(ExportStatus.FailureStage.METADATA, health.stage());
        assertTrue(failed.result().chunkWritesSuccessful());
        assertTrue(failed.indexCommitted());
        assertFalse(failed.metadataCommitted());
        assertFalse(failed.remaining().hasTerrain());
        assertEquals(200L, failed.remaining().durableCaptureTime());
        Files.delete(metadata.resolve("blocker"));
        Files.delete(metadata);
        Files.move(backup, metadata);
        var retry = DownloadExportCoordinator.write(job(failed.remaining().retry(), world, new OverwriteResolver()));
        assertTrue(retry.successful());
        assertEquals(0, retry.result().totalWritten());
        assertEquals(200L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
        assertTrue(status.finish(failed.remaining(), retry).settled());
    }

    @Test
    void changedTargetIdentityCannotAuthorizeAnotherSourcesWrite(@TempDir Path world) throws Exception {
        assertTrue(DownloadExportCoordinator.write(job(terrain(1), world, new OverwriteResolver())).successful());
        var metadata = WorldMetadata.loadIfPresent(world).orElseThrow();
        metadata.sourceId = "server:other";
        assertTrue(metadata.save(world));
        var outcome = DownloadExportCoordinator.write(job(terrain(2), world, new OverwriteResolver()));
        assertFalse(outcome.successful());
        assertEquals(0, outcome.result().totalWritten());
        assertTrue(outcome.remaining().hasTerrain());
        assertEquals("server:other", WorldMetadata.loadIfPresent(world).orElseThrow().sourceId);
    }

    private static ExportWork.Frozen terrain(int revision) {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("marker", revision);
        var snapshot = ChunkListener.snapshotOf(Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0),
                new ChunkListener.CapturedChunk(nbt, revision * 100L, revision))));
        return new ExportWork.Frozen(
                new ExportWork.Context(DownloadExportCoordinator.Trigger.STOP, false,
                new ExportWork.Source("server:transaction", "server")),
                snapshot,
                Map.of(),
                Map.of(),
                WorldSettingsSnapshot.defaults(),
                0,
                OptionalLong.empty());
    }

    private static DownloadExportCoordinator.ExportJob job(ExportWork.Frozen work, Path world, ConflictResolver resolver) {
        return new DownloadExportCoordinator.ExportJob(work, () -> world, ModConfig.DownloadPipelineMode.STABLE_PERIODIC,
                false, false, resolver, -1L, io.github.billstark001.worldmirror.io.ExportProgress.NONE);
    }
    @Test
    void preparingAnotherDimensionCannotEraseEarlierTerrainReceipts(@TempDir Path world) throws Exception {
        var original = terrain(1);
        var dimensions = new java.util.HashMap<net.minecraft.resources.ResourceKey<Level>,
                Map<ChunkPos, ChunkListener.CapturedChunk>>();
        for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            dimensions.put(dimension, original.terrain().chunks().get(Level.OVERWORLD));
        }
        var snapshot = ChunkListener.snapshotOf(dimensions);
        var bad = new java.util.ArrayList<>(snapshot.chunks().keySet()).getLast();
        Path blocked = io.github.billstark001.worldmirror.io.ChunkExporter.regionDirForDimension(world, bad);
        Files.createDirectories(blocked.getParent()); Files.writeString(blocked, "not a directory");
        var work = new ExportWork.Frozen(original.context(), snapshot, Map.of(), Map.of(), original.settings(),
                0, OptionalLong.empty());
        var outcome = DownloadExportCoordinator.write(job(work, world, new OverwriteResolver()));
        assertEquals(2, outcome.result().totalWritten());
        assertTrue(outcome.indexCommitted());
        assertEquals(1, outcome.remaining().terrainCount());
        assertTrue(outcome.remaining().terrain().chunks().containsKey(bad));
    }

    @Test
    void invalidEntityDimensionDoesNotEraseDurableTerrain(@TempDir Path world) throws Exception {
        var original = terrain(1);
        var invalid = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.parse("audit:../escape"));
        var work = new ExportWork.Frozen(original.context(), original.terrain(),
                Map.of(invalid, Map.of(new ChunkPos(0, 0), new EntityTracker.ChunkUpdate(
                        2, EntityTracker.Observation.COMPLETE, Map.of(), Set.of()))), Map.of(), original.settings(),
                0, OptionalLong.empty());
        var outcome = DownloadExportCoordinator.write(job(work, world, new OverwriteResolver()));
        assertEquals(1, outcome.result().totalWritten());
        assertTrue(outcome.indexCommitted());
        assertFalse(outcome.remaining().hasTerrain());
        assertTrue(outcome.remaining().hasEntities());
    }

    @Test
    void corruptOptionalClocksPreserveTheirBytesAndAllowTerrain(@TempDir Path world) throws Exception {
        assertTrue(DownloadExportCoordinator.write(job(terrain(1), world, new OverwriteResolver())).successful());
        Path clock = world.resolve("data/minecraft/world_clocks.dat");
        Files.createDirectories(clock.getParent());
        byte[] corrupt = {1, 2, 3}; Files.write(clock, corrupt);
        var outcome = DownloadExportCoordinator.write(job(terrain(2), world, new OverwriteResolver()));
        assertTrue(outcome.successful());
        assertEquals(1, outcome.result().totalWritten());
        assertArrayEquals(corrupt, Files.readAllBytes(clock));
    }

    @Test
    void failedRuleReadsNeitherWriteTerrainNorDeleteProtectedClaims(@TempDir Path world) throws Exception {
        assertTrue(DownloadExportCoordinator.write(job(terrain(1), world, new OverwriteResolver())).successful());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + world.resolve("data/world_mirror.sqlite"));
             var sql = connection.createStatement()) {
            sql.execute("UPDATE chunks SET update_source='player'");
        }
        var work = terrain(2);
        var outcome = DownloadExportCoordinator.write(new DownloadExportCoordinator.ExportJob(work, () -> world,
                ModConfig.DownloadPipelineMode.STABLE_PERIODIC, false, false, new OverwriteResolver(), -1L,
                (part, dimension, chunks) -> {
                    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + world.resolve("data/world_mirror.sqlite"));
                         var sql = connection.createStatement()) { sql.execute("DROP TABLE update_sources"); }
                    catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
                }));
        assertEquals(0, outcome.result().totalWritten());
        assertTrue(outcome.remaining().hasTerrain());
        assertTrue(outcome.result().unreadableChunks().values().stream().allMatch(Set::isEmpty));
        try (var db = ChunkDatabase.open(world, "server:transaction")) {
            assertEquals("player", db.queryAll("minecraft:overworld").getFirst().updateSource());
            assertTrue(db.shouldSkipUpdate("minecraft:overworld", new ChunkPos(0, 0), "world_mirror", 200L));
        }
    }

}
