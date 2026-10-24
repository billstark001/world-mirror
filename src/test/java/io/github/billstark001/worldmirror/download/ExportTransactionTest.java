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
            var outcome = DownloadExportCoordinator.write(job(work, world, new OverwriteResolver()));
            assertTrue(outcome.result().chunkWritesSuccessful());
            assertFalse(outcome.result().entityWritesSuccessful());
            assertTrue(outcome.indexCommitted());
            assertTrue(outcome.metadataCommitted());
            assertFalse(outcome.remaining().hasTerrain());
            assertTrue(outcome.remaining().hasEntities());
            assertEquals(revision * 100L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
            assertArrayEquals(corrupt, Files.readAllBytes(entities));
        }
    }

    @Test
    void indexFailureRetainsVerifiedTerrainUntilItsReceiptIsCommitted(@TempDir Path world) throws Exception {
        assertTrue(DownloadExportCoordinator.write(job(terrain(1), world, new OverwriteResolver())).successful());
        Path database = world.resolve(io.github.billstark001.worldmirror.format.MirrorFormat.DATABASE_FILE);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath()); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_capture BEFORE INSERT ON chunks BEGIN SELECT RAISE(FAIL, 'injected'); END");
            var failed = DownloadExportCoordinator.write(job(terrain(2), world, new OverwriteResolver()));
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
        }
    }

    @Test
    void metadataFailureKeepsOnlyTheDurableTimeForAMetadataOnlyRetry(@TempDir Path world) throws Exception {
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
        var failed = DownloadExportCoordinator.write(job(terrain(2), world, obstructMetadataAfterInspection));
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
        return new DownloadExportCoordinator.ExportJob(work, world, ModConfig.DownloadPipelineMode.STABLE_PERIODIC,
                false, false, resolver, -1L);
    }
}
