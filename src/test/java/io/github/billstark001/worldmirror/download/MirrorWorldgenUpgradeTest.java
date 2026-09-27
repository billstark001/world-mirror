package io.github.billstark001.worldmirror.download;

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
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        WorldMetadata metadata = WorldMetadata.create("local:test", "singleplayer", "synchronized");
        metadata.markWorldgenCurrent(
                SharedConstants.getCurrentVersion().dataVersion().version(), 2);
        metadata.save(world);

        assertEquals(MirrorMigrationPlan.State.OUTDATED,
                MirrorMigrationCoordinator.inspect(world).state());
        MirrorMigrationCoordinator.Result result = MirrorMigrationCoordinator.migrateApproved(world);
        assertTrue(result.success());
        assertTrue(Files.isRegularFile(result.backup()));
        assertEquals(MirrorMigrationPlan.State.CURRENT,
                MirrorMigrationCoordinator.inspect(world).state());
        assertEquals(MirrorWorldgenAssets.ASSET_REVISION,
                WorldMetadata.loadIfPresent(world).orElseThrow().worldgenAssetRevision);
    }

    @Test
    void upgradesAnExisting263MirrorByRewritingWorldgenSettings(@TempDir Path world)
            throws Exception {
        assumeTrue(SharedConstants.DATA_PACK_FORMAT_MAJOR >= 121);
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));

        WorldMetadata metadata = WorldMetadata.create("local:test", "singleplayer", "synchronized");
        metadata.markWorldgenCurrent(
                SharedConstants.getCurrentVersion().dataVersion().version(),
                2);
        metadata.save(world);

        Path settingsFile = world.resolve("data/minecraft/world_gen_settings.dat");
        CompoundTag oldSettings = NbtIo.readCompressed(settingsFile, NbtAccounter.unlimitedHeap());
        oldSettings.getCompoundOrEmpty("data").putBoolean("stale_worldgen_marker", true);
        NbtIo.writeCompressed(oldSettings, settingsFile);

        assertEquals(MirrorMigrationPlan.State.OUTDATED,
                MirrorMigrationCoordinator.inspect(world).state());
        assertTrue(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertEquals(MirrorMigrationPlan.State.CURRENT,
                MirrorMigrationCoordinator.inspect(world).state());

        CompoundTag updated = NbtIo.readCompressed(settingsFile, NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("data");
        assertFalse(updated.contains("stale_worldgen_marker"));
        CompoundTag dimensions = updated.getCompoundOrEmpty("dimensions");
        for (MirrorWorldgenDefinition.Dimension dimension : MirrorWorldgenDefinition.DIMENSIONS) {
            CompoundTag settings = dimensions.getCompoundOrEmpty(dimension.dimensionType())
                    .getCompoundOrEmpty("generator").getCompoundOrEmpty("settings");
            NoiseGeneratorSettings.DIRECT_CODEC.parse(NbtOps.INSTANCE, settings).getOrThrow();
        }
    }
}
