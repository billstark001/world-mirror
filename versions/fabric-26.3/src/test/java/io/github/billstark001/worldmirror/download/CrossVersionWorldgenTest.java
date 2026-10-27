package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.MirrorWorldgenCheck;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CrossVersionWorldgenTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertEquals("26.3", SharedConstants.getCurrentVersion().name());
    }

    @Test
    void real262WorldMigratesAndLoadsWithNative263Registries(@TempDir Path world) throws Exception {
        copyFixture(world);
        assertFalse(MirrorMigrationCoordinator.inspect(world).repairWorldgen());
        upgradeAndLoad(world);
    }

    @Test
    void currentMetadataCannotHideReal262Generators(@TempDir Path world) throws Exception {
        copyFixture(world);
        WorldMetadata metadata = WorldMetadata.loadIfPresent(world).orElseThrow();
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), MirrorWorldgenAssets.ASSET_REVISION);
        assertTrue(metadata.save(world));
        assertTrue(MirrorMigrationCoordinator.inspect(world).repairWorldgen());
        upgradeAndLoad(world);
    }

    private static void upgradeAndLoad(Path world) throws Exception {
        assertEquals(MirrorWorldgenCheck.State.LEGACY, MirrorWorldgenCheck.inspect(world).state());
        assertEquals(MirrorMigrationPlan.State.OUTDATED, MirrorMigrationCoordinator.inspect(world).state());
        var migrated = MirrorMigrationCoordinator.migrateApproved(world);
        assertTrue(migrated.success(), migrated.failure());
        assertNotNull(migrated.backup());
        assertEquals(MirrorMigrationPlan.State.CURRENT, MirrorMigrationCoordinator.inspect(world).state());
        var storage = LevelStorageSource.createDefault(world.getParent());
        try (var access = storage.validateAndCreateAccess(world.getFileName().toString())) {
            var packs = ServerPacksSource.createPackRepository(access);
            var data = access.getUnfixedDataTag(false);
            var config = new WorldLoader.InitConfig(LevelStorageSource.getPackConfig(data, packs, false),
                    Commands.CommandSelection.INTEGRATED, LevelBasedPermissionSet.GAMEMASTER);
            var loaded = WorldLoader.load(config, context -> {
                var dimensions = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
                var decoded = LevelStorageSource.getLevelDataAndDimensions(access, data,
                        context.dataConfiguration(), dimensions, context.datapackWorldRegistries());
                return new WorldLoader.DataLoadOutput<>(decoded.worldDataAndGenSettings(),
                        decoded.dimensions().dimensionsRegistryAccess());
            }, WorldStem::new, ForkJoinPool.commonPool(), Runnable::run);
            try (var stem = loaded.get(60, TimeUnit.SECONDS)) {
                assertEquals(3, stem.registries().compositeAccess().lookupOrThrow(Registries.LEVEL_STEM).size());
            }
        }
        assertFalse(MirrorMigrationCoordinator.migrateApproved(world).changed());
    }

    private static void copyFixture(Path world) throws Exception {
        String pointer = System.getProperty("worldmirror.test.262Fixture");
        assertNotNull(pointer, "Run through Gradle so the real 26.2 fixture is generated first");
        Path source = Path.of(Files.readString(Path.of(pointer)).trim());
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = world.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        int fixtureVersion = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("Data").getIntOr("DataVersion", -1);
        assertEquals(4903, fixtureVersion); // Published 26.2 data version, independently checked at generation.
        // Mirror the file-layout upgrade that vanilla performs before our world-open hook.
        var storage = LevelStorageSource.createDefault(world.getParent());
        try (var access = storage.validateAndCreateAccess(world.getFileName().toString())) {
            var fixer = net.minecraft.util.datafix.DataFixers.getFileFixer();
            if (fixer.requiresFileFixing(fixtureVersion)) {
                fixer.fix(access, access.getUnfixedDataTag(false), new net.minecraft.util.worldupdate.UpgradeProgress());
            }
        }
    }
}
