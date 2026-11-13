package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.download.MirrorMigrationPlan;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SeededWorldgenTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nativeNormalPresetCanBeSetVerifiedAndRemoved(@TempDir Path world) throws Exception {
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        assertTrue(
                WorldStructureCreator.createVoidWorld(world, "seed test", WorldSettingsSnapshot.defaults()));
        var metadata = WorldMetadata.create("mirror:seeded", WorldGraph.Kind.MIRROR, "remote:source",
                WorldGraph.Role.SYNC, "physical namespace");
        metadata.markWorldgenCurrent(dataVersion, MirrorWorldgenAssets.ASSET_REVISION);
        assertTrue(metadata.save(world));
        assertTrue(WorldStructureApi.hasMirrorDataPack(world));
        assertEquals(0L, WorldStructureCreator.readSeed(world));
        for (Path path : WorldStructureCreator.generationFiles(world)) {
            assertTrue(Files.isRegularFile(world.resolve(path)));
        }

        long seed = Long.MIN_VALUE + 345;
        var epoch = UUID.fromString("16c8222f-1952-4d13-9adb-16527b883b6c");
        String digest = WorldStructureCreator.stageSeededGeneration(world, seed);
        metadata.markSeededGeneration(dataVersion, digest, epoch, true);
        assertTrue(metadata.save(world));
        assertEquals(seed, WorldStructureCreator.readSeed(world));
        assertFalse(WorldStructureApi.hasMirrorDataPack(world));
        var saved = WorldMetadata.loadIfPresent(world).orElseThrow();
        assertEquals(epoch, saved.generation.seedEpoch());
        assertEquals(WorldMetadata.NORMAL_GENERATION_PRESET, saved.generation.preset());
        assertTrue(saved.generation.blendTerrain());
        assertFalse(Files.readString(world.resolve(WorldMetadata.FILE_NAME)).contains(Long.toString(seed)));
        assertEquals(
                MirrorMigrationPlan.State.CURRENT, MirrorMigrationPlan.inspect(world, dataVersion).state());
        assertEquals("physical namespace", saved.sourceId);
        var settings = SeededWorldgen.readSettings(world);
        assertTrue(settings.getBooleanOr(WorldStructureApi.structureGenerationKey(), false));
        assertEquals(3, settings.getCompoundOrEmpty("dimensions").size());

        assertTrue(WorldStructureCreator.maintainWorld(world, "seed test"));
        assertFalse(
                WorldStructureCreator.createVoidWorld(world, "seed test", WorldSettingsSnapshot.defaults()));
        assertTrue(WorldStructureCreator.matchesSeededGeneration(world, digest));

        Path generationFile = WorldStructureApi.worldgenDataPath(world);
        CompoundTag changedRoot = NbtReadSupport.readWorldData(generationFile);
        WorldStructureApi.worldgenData(changedRoot).putLong("seed", seed + 1);
        WorldStructureCreator.writeVerifiedWorldData(generationFile, changedRoot);
        var changed = MirrorMigrationPlan.inspect(world, dataVersion);
        assertEquals(MirrorMigrationPlan.State.UNREADABLE, changed.state());
        assertEquals("seeded_generation_changed", changed.problem());
        assertFalse(changed.mayCreateOrWriteWithoutMigration());

        WorldStructureCreator.stageVoidGeneration(world);
        metadata.markWorldgenCurrent(dataVersion, MirrorWorldgenAssets.ASSET_REVISION);
        assertTrue(metadata.save(world));
        assertEquals(0L, WorldStructureCreator.readSeed(world));
        assertTrue(WorldStructureApi.hasMirrorDataPack(world));
        assertEquals(
                MirrorMigrationPlan.State.CURRENT, MirrorMigrationPlan.inspect(world, dataVersion).state());
    }

    @Test
    void rejectsCustomVoidSettingsWithoutReplacingThem(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createVoidWorld(
                world, "custom test", WorldSettingsSnapshot.defaults()));
        Path generationFile = WorldStructureApi.worldgenDataPath(world);
        CompoundTag root = NbtReadSupport.readWorldData(generationFile);
        WorldStructureApi.worldgenData(root).putString("custom_option", "keep");
        WorldStructureCreator.writeVerifiedWorldData(generationFile, root);
        byte[] original = Files.readAllBytes(generationFile);
        assertThrows(java.io.IOException.class, () -> WorldStructureCreator.stageSeededGeneration(world, 1));
        assertArrayEquals(original, Files.readAllBytes(generationFile));
        assertTrue(WorldStructureApi.hasMirrorDataPack(world));
    }

    @Test
    void acceptsVanillaSerializedVoidDensityConstants(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createVoidWorld(
                world, "reopened mirror", WorldSettingsSnapshot.defaults()));
        Path generationFile = WorldStructureApi.worldgenDataPath(world);
        CompoundTag root = NbtReadSupport.readWorldData(generationFile);
        var dimensions = WorldStructureApi.worldgenData(root).getCompoundOrEmpty("dimensions");
        for (String id : dimensions.keySet()) {
            var router = dimensions.getCompoundOrEmpty(id)
                                 .getCompoundOrEmpty("generator")
                                 .getCompoundOrEmpty("settings")
                                 .getCompoundOrEmpty("noise_router");
            for (String field : router.keySet()) {
                var density = (DoubleTag) router.get(field);
                router.putFloat(field, (float) density.value());
            }
        }
        WorldStructureCreator.writeVerifiedWorldData(generationFile, root);
        assertTrue(WorldStructureCreator.hasCanonicalVoidGeneration(world));
        assertNotNull(WorldStructureCreator.stageSeededGeneration(world, 1));
    }

    @Test
    void reseedingRechecksOwnershipAndChangesOnlyTheNativeSeed(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createVoidWorld(world, "reseed", WorldSettingsSnapshot.defaults()));
        String originalDigest = WorldStructureCreator.stageSeededGeneration(world, 123);
        var original = SeededWorldgen.readSettings(world);
        String digest = WorldStructureCreator.stageReseededGeneration(world, originalDigest, -456);
        var changed = SeededWorldgen.readSettings(world);
        assertEquals(-456L, WorldStructureCreator.readSeed(world));
        assertNotEquals(originalDigest, digest);
        assertTrue(WorldStructureCreator.matchesSeededGeneration(world, digest));
        assertFalse(WorldStructureApi.hasMirrorDataPack(world));
        original.remove("seed");
        changed.remove("seed");
        assertEquals(original, changed);
        assertThrows(java.io.IOException.class,
                () -> WorldStructureCreator.stageReseededGeneration(world, originalDigest, 789));
        assertEquals(-456L, WorldStructureCreator.readSeed(world));
    }

    @Test
    void surfaceSamplerUsesTheNativeSeed() {
        var first = WorldStructureCreator.seedSurface(123);
        var same = WorldStructureCreator.seedSurface(123);
        var different = WorldStructureCreator.seedSurface(456);
        boolean differs = false;
        for (int x = -800; x <= 800; x += 400) {
            int height = first.height(x, x + 159);
            assertEquals(height, same.height(x, x + 159));
            assertTrue(height > -64 && height < 320);
            differs |= height != different.height(x, x + 159);
        }
        assertTrue(differs);
    }

    @Test
    void digestIgnoresOnlyCompoundKeyInsertionOrder() {
        var first = new CompoundTag();
        first.putString("a", "first");
        first.putInt("z", 7);
        var second = new CompoundTag();
        second.putInt("z", 7);
        second.putString("a", "first");
        assertEquals(SeededWorldgen.digest(first), SeededWorldgen.digest(second));
        second.putLong("z", 7L);
        assertNotEquals(SeededWorldgen.digest(first), SeededWorldgen.digest(second));
    }

    @Test
    void nativeNormalPresetDigestIsStableAcrossSupportedLayouts() {
        var settings = SeededWorldgen.settings(-7803394712492L);
        assertEquals("ae9134e08cfae9572591bfb9378df0c1192784139c61a3d3358c505a5d55451d",
                SeededWorldgen.digest(settings));
    }
}
