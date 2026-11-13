package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.MirrorWorldgenDefinition;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.DirectoryLock;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MirrorWorldgenUpgradeTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void upgradesRevisionTwoOnEveryTarget(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));
        WorldMetadata metadata = WorldMetadata.create(
                "mirror:test", WorldGraph.Kind.MIRROR, "local:test", WorldGraph.Role.SYNC, "local:test");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        metadata.save(world);

        assertEquals(MirrorMigrationPlan.State.OUTDATED, MirrorMigrationCoordinator.inspect(world).state());
        MirrorMigrationCoordinator.Result result = MirrorMigrationCoordinator.migrateApproved(world);
        assertTrue(result.success());
        assertTrue(Files.isRegularFile(result.backup()));
        assertEquals(MirrorMigrationPlan.State.CURRENT, MirrorMigrationCoordinator.inspect(world).state());
        assertEquals(MirrorWorldgenAssets.ASSET_REVISION,
                WorldMetadata.loadIfPresent(world).orElseThrow().generation.assetRevision());
    }

    @Test
    void approvedUpgradeRejectsAReplacementWithTheSameIndexSource(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));
        String source = "server:shared";
        var approved = new MirrorTarget(world, "mirror:original", source);
        WorldMetadata replacement = WorldMetadata.create(
                "mirror:replacement", WorldGraph.Kind.MIRROR, "remote:shared", WorldGraph.Role.SYNC, source);
        replacement.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        assertTrue(replacement.save(world));
        Path metadataFile = world.resolve(WorldMetadata.FILE_NAME);
        byte[] original = Files.readAllBytes(metadataFile);

        var result = MirrorMigrationCoordinator.migrateApproved(
                approved, MirrorMigrationCoordinator.ProgressListener.NONE);

        assertFalse(result.success());
        assertEquals("mirror_identity_changed", result.failure());
        assertArrayEquals(original, Files.readAllBytes(metadataFile));
        assertEquals(MirrorMigrationPlan.State.OUTDATED, MirrorMigrationCoordinator.inspect(world).state());
    }

    @Test
    void upgradesAnExisting263MirrorByRewritingWorldgenSettings(@TempDir Path world) throws Exception {
        assumeTrue(SharedConstants.DATA_PACK_FORMAT_MAJOR >= 121);
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));

        WorldMetadata metadata = WorldMetadata.create(
                "mirror:test", WorldGraph.Kind.MIRROR, "local:test", WorldGraph.Role.SYNC, "local:test");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        metadata.save(world);

        Path settingsFile = world.resolve("data/minecraft/world_gen_settings.dat");
        CompoundTag oldSettings = NbtIo.readCompressed(settingsFile, NbtAccounter.unlimitedHeap());
        oldSettings.getCompoundOrEmpty("data").putBoolean("stale_worldgen_marker", true);
        NbtIo.writeCompressed(oldSettings, settingsFile);

        assertEquals(MirrorMigrationPlan.State.OUTDATED, MirrorMigrationCoordinator.inspect(world).state());
        assertTrue(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertEquals(MirrorMigrationPlan.State.CURRENT, MirrorMigrationCoordinator.inspect(world).state());

        CompoundTag updated =
                NbtIo.readCompressed(settingsFile, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
        assertTrue(updated.getBooleanOr("stale_worldgen_marker", false));
        CompoundTag dimensions = updated.getCompoundOrEmpty("dimensions");
        for (MirrorWorldgenDefinition.Dimension dimension : MirrorWorldgenDefinition.DIMENSIONS) {
            CompoundTag settings = dimensions.getCompoundOrEmpty(dimension.dimensionType())
                                           .getCompoundOrEmpty("generator")
                                           .getCompoundOrEmpty("settings");
            NoiseGeneratorSettings.DIRECT_CODEC.parse(NbtOps.INSTANCE, settings).getOrThrow();
        }
    }

    @Test
    void reportsFailureWhenTheFinalMetadataMarkerCannotBeWritten(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));
        WorldMetadata metadata = WorldMetadata.create(
                "mirror:test", WorldGraph.Kind.MIRROR, "local:test", WorldGraph.Role.SYNC, "local:test");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        metadata.save(world);

        var result = MirrorMigrationCoordinator.migrateApproved(world, (phase, completed, total) -> {
            if (phase == MirrorMigrationCoordinator.Phase.WRITING_WORLD_DATA && completed == total) {
                try {
                    Path marker = world.resolve(WorldMetadata.FILE_NAME);
                    Files.delete(marker);
                    Files.createDirectory(marker);
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            }
        });

        assertFalse(result.success());
        assertEquals("migration_failed", result.failure());
        try (var backups = Files.list(world.resolve("backups"))) {
            assertEquals(1, backups.count());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void migrationDoesNotOverwriteAValidMetadataEdit(boolean afterBackup, @TempDir Path world)
            throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));
        WorldMetadata metadata = WorldMetadata.create(
                "mirror:test", WorldGraph.Kind.MIRROR, "local:test", WorldGraph.Role.SYNC, "local:test");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        assertTrue(metadata.save(world));
        byte[] generator = Files.readAllBytes(world.resolve("level.dat"));

        var result = MirrorMigrationCoordinator.migrateApproved(world, (phase, completed, total) -> {
            if (phase == MirrorMigrationCoordinator.Phase.BACKING_UP && (completed == total) == afterBackup) {
                try {
                    metadata.lastSyncTime = 999L;
                    Files.writeString(world.resolve(WorldMetadata.FILE_NAME),
                            io.github.billstark001.worldmirror.util.JsonSupport.toPrettyJson(metadata));
                } catch (java.io.IOException failure) {
                    throw new AssertionError(failure);
                }
            }
        });

        assertFalse(result.success());
        assertEquals(999L, WorldMetadata.loadIfPresent(world).orElseThrow().lastSyncTime);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                generator, Files.readAllBytes(world.resolve("level.dat")));
    }

    @Test
    void cannotMigrateAWorldAlreadyOpenedByVanilla(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));
        WorldMetadata metadata = WorldMetadata.create(
                "mirror:test", WorldGraph.Kind.MIRROR, "local:test", WorldGraph.Role.SYNC, "local:test");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        assertTrue(metadata.save(world));
        byte[] original = Files.readAllBytes(world.resolve("level.dat"));
        try (DirectoryLock vanilla = DirectoryLock.create(world)) {
            var result = MirrorMigrationCoordinator.migrateApproved(world);
            assertFalse(result.success());
            assertEquals("world_locked", result.failure());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    original, Files.readAllBytes(world.resolve("level.dat")));
            assertFalse(Files.exists(world.resolve("backups")));
        }
        assertTrue(MirrorMigrationCoordinator.migrateApproved(world).success());
    }
    @Test
    void progressObserverFailuresDoNotAbortTheMigration(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));
        WorldMetadata metadata = WorldMetadata.create(
                "mirror:test", WorldGraph.Kind.MIRROR, "local:test", WorldGraph.Role.SYNC, "local:test");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        assertTrue(metadata.save(world));
        var result = MirrorMigrationCoordinator.migrateApproved(world, (phase, completed, total) -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("observer failure"));
        });
        assertTrue(result.success());
        assertEquals(MirrorMigrationPlan.State.CURRENT, MirrorMigrationCoordinator.inspect(world).state());
    }
}
