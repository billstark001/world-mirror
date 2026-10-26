package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Version-neutral orchestration for creating and updating playable mirror saves. */
@Environment(EnvType.CLIENT)
public final class WorldStructureCreator {
    public enum Setting { TIME, WEATHER, DIFFICULTY }

    private WorldStructureCreator() { }

    public static WorldSettingsSnapshot captureWorldSettings(ClientLevel world) {
        if (world == null) return WorldSettingsSnapshot.defaults();
        try {
            return WorldStructureApi.captureWorldSettings(world);
        } catch (Exception e) {
            WMLogger.warnRateLimited("world-settings-capture", 30_000L,
                    "Could not capture source world settings; using defaults", e);
            return WorldSettingsSnapshot.defaults();
        }
    }

    public static WorldSettingsSnapshot resolveNewWorldSettings(
            WorldSettingsSnapshot current) {
        WorldSettingsSnapshot defaults = WorldSettingsSnapshot.defaults();
        ModConfig config = ModConfig.get();
        return new WorldSettingsSnapshot(
                config.newWorldTime == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        ? current.gameTime() : defaults.gameTime(),
                config.newWorldTime == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        ? current.dayTime() : defaults.dayTime(),
                config.newWorldWeather == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        && current.raining(),
                config.newWorldWeather == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        && current.thundering(),
                config.newWorldDifficulty == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        ? current.difficultyId() : defaults.difficultyId());
    }

    public static CompoundTag createMirrorWorldGenSettings() {
        CompoundTag worldGenSettings = new CompoundTag();
        CompoundTag dimensions = new CompoundTag();
        for (MirrorWorldgenDefinition.Dimension definition : MirrorWorldgenDefinition.DIMENSIONS) {
            CompoundTag dimension = new CompoundTag();
            dimension.put("generator", createVoidNoiseGenerator(definition.biome(),
                    definition.minY(), definition.height(), definition.seaLevel(),
                    definition.horizontalSize(), definition.verticalSize()));
            dimension.putString("type", definition.dimensionType());
            dimensions.put(definition.dimensionType(), dimension);
        }
        worldGenSettings.put("dimensions", dimensions);
        worldGenSettings.putByte("bonus_chest", (byte) 0);
        worldGenSettings.putByte("generate_structures", (byte) 0);
        worldGenSettings.putLong("seed", 0L);
        return worldGenSettings;
    }

    public static boolean createLoadableWorldWithSpawn(Path worldFolder, String levelName,
                                                        int spawnX, int spawnY, int spawnZ,
                                                        WorldSettingsSnapshot settings) {
        return createLoadableWorldWithSpawn(worldFolder, levelName, Level.OVERWORLD,
                spawnX, spawnY, spawnZ, settings);
    }

    /** Creates a nearby save whose initial player and world spawn use the captured dimension. */
    public static boolean createLoadableWorldWithSpawn(Path worldFolder, String levelName,
                                                        ResourceKey<Level> spawnDimension,
                                                        int spawnX, int spawnY, int spawnZ,
                                                        WorldSettingsSnapshot settings) {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            if (!createLoadableWorld(worldFolder, levelName, true, true, settings)) return false;
            UUID playerId = singleplayerUuid(levelName);
            WorldStructureApi.writeSpawnedLevelData(
                    worldFolder, levelName, playerId, spawnDimension, spawnX, spawnY, spawnZ,
                    createMirrorWorldGenSettings(), settings);
            writeVerifiedWorldData(WorldStructureApi.playerDataPath(worldFolder, playerId),
                    createPlayerData(spawnDimension, spawnX, spawnY, spawnZ));
            WMLogger.debug("Nearby-export world created at: " + worldFolder.toAbsolutePath());
            return true;
        } catch (Exception e) {
            WMLogger.warn("Nearby export world structure creation failed path=" + worldFolder, e);
            return false;
        }
    }

    public static boolean createLoadableWorld(Path worldFolder, String levelName,
                                              boolean migrateWorldgen, boolean refreshAssets,
                                              WorldSettingsSnapshot settings) {
        File folder = worldFolder.toFile();
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            boolean firstTime = Files.notExists(worldFolder.resolve("level.dat"));
            if (!folder.exists()) folder.mkdirs();
            for (String directory : WorldStructureApi.worldSubdirectories()) {
                new File(folder, directory).mkdirs();
            }

            if (firstTime) {
                MirrorWorldgenAssets.install(worldFolder, WorldStructureApi.dataPackFormat());
                UUID playerId = singleplayerUuid(levelName);
                WorldStructureApi.createInitialWorld(worldFolder, levelName, playerId,
                        createMirrorWorldGenSettings(), settings);
                writeVerifiedWorldData(WorldStructureApi.playerDataPath(worldFolder, playerId),
                        createPlayerData(0, 80, 0));
                WMLogger.debug("World structure created at: " + folder.getAbsolutePath()
                        + " (name: " + resolvedLevelName(levelName) + ")");
            } else {
                try {
                    WorldStructureApi.repairOwnedSavedData(worldFolder);
                    io.github.billstark001.worldmirror.download.OperationFailures.recovered(levelName, "optional_saved_data");
                } catch (Exception repairFailure) {
                    // Optional maintenance is not proof that terrain cannot be persisted.
                    // Leave the damaged file untouched; world loadability remains unverified.
                    io.github.billstark001.worldmirror.download.OperationFailures.report(
                            levelName, "optional_saved_data", worldFolder, repairFailure);
                }
                if (migrateWorldgen || refreshAssets) {
                    if (!Files.isRegularFile(worldFolder.resolve("level.dat"))) {
                        throw new IOException("Required world data is unavailable: " + worldFolder.resolve("level.dat"));
                    }
                    MirrorWorldgenAssets.install(worldFolder, WorldStructureApi.dataPackFormat());
                    WorldStructureApi.updateOwnedLevelData(worldFolder,
                            migrateWorldgen || refreshAssets,
                            createMirrorWorldGenSettings());
                }
                WMLogger.debug("World structure updated (incremental sync): "
                        + folder.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror world structure update failed path=" + folder, e);
            return false;
        }
    }

    public static boolean syncWorldSetting(MirrorTarget target,
                                           WorldSettingsSnapshot settings,
                                           Setting setting) {
        Path worldFolder = target.folder();
        if (!Files.isRegularFile(worldFolder.resolve("level.dat"))) return false;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            target.requireCurrent();
            WorldStructureApi.syncWorldSetting(worldFolder, settings, setting);
            return true;
        } catch (Exception e) {
            WMLogger.warn("Could not sync mirror world setting=" + setting
                    + " path=" + worldFolder, e);
            return false;
        }
    }

    static String resolvedLevelName(String levelName) {
        return levelName != null && !levelName.isEmpty() ? levelName : "Downloaded World";
    }

    /** Replaces a world-data file only after its compressed NBT round-trips without loss. */
    static void writeVerifiedWorldData(Path file, CompoundTag tag) throws Exception {
        file = file.toAbsolutePath().normalize();
        try (var transaction = new io.github.billstark001.worldmirror.util.FileTransaction(file.getParent())) {
            Path staged = transaction.stagingDirectory().resolve(file.getFileName());
            try (var raw = Files.newOutputStream(staged);
                 var compressed = net.minecraft.world.level.chunk.storage.RegionFileVersion.VERSION_GZIP.wrap(raw);
                 var output = new java.io.DataOutputStream(new java.io.BufferedOutputStream(compressed))) {
                NbtIo.writeUnnamedTag(tag, output);
            }
            if (!tag.equals(NbtReadSupport.readWorldData(staged))) {
                throw new IOException("World-data staging verification failed: " + file);
            }
            transaction.commit(java.util.Map.of(file.getFileName(), staged));
        }
    }

    static CompoundTag createPlayerData(int x, int y, int z) {
        return createPlayerData(Level.OVERWORLD, x, y, z);
    }

    static CompoundTag createPlayerData(ResourceKey<Level> dimension, int x, int y, int z) {
        CompoundTag player = new CompoundTag();
        NbtUtils.addCurrentDataVersion(player);
        player.putString("Dimension", dimension.identifier().toString());

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x + 0.5D));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z + 0.5D));
        player.put("Pos", pos);

        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(0.0F));
        rotation.add(FloatTag.valueOf(0.0F));
        player.put("Rotation", rotation);

        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        player.put("Motion", motion);

        player.putFloat("Health", 20.0F);
        player.putInt("playerGameType", 1);
        player.putBoolean("OnGround", true);
        player.putInt("Score", 0);
        player.putShort("Air", (short) 300);
        player.putShort("Fire", (short) -20);
        player.put("Inventory", new ListTag());
        player.put("EnderItems", new ListTag());
        return player;
    }

    private static UUID singleplayerUuid(String levelName) {
        return UUID.nameUUIDFromBytes(
                ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
    }

    private static CompoundTag createVoidNoiseGenerator(String biome, int minY, int height,
                                                        int seaLevel, int horizontalSize,
                                                        int verticalSize) {
        CompoundTag generator = new CompoundTag();
        CompoundTag settings = new CompoundTag();
        CompoundTag noise = new CompoundTag();
        noise.putInt("min_y", minY);
        noise.putInt("height", height);
        boolean modern = WorldStructureApi.dataPackFormat()
                >= MirrorWorldgenAssets.ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT;
        if (!modern) {
            noise.putInt("size_horizontal", horizontalSize);
            noise.putInt("size_vertical", verticalSize);
        }
        settings.put("noise", noise);
        settings.put("default_block", blockState("minecraft:air", modern));
        settings.put("default_fluid", blockState("minecraft:air", modern));
        settings.putInt("sea_level", seaLevel);
        settings.putBoolean("disable_mob_generation", true);
        if (!modern) {
            settings.putBoolean("aquifers_enabled", false);
            settings.putBoolean("ore_veins_enabled", false);
        }
        settings.putBoolean("legacy_random_source", false);
        settings.put("spawn_target", new ListTag());
        CompoundTag router = new CompoundTag();
        for (String field : modern
                ? MirrorWorldgenDefinition.MODERN_ZERO_NOISE_ROUTER_FIELDS
                : MirrorWorldgenDefinition.ZERO_NOISE_ROUTER_FIELDS) {
            router.putDouble(field, 0.0D);
        }
        router.putDouble("final_density", MirrorWorldgenDefinition.VOID_FINAL_DENSITY);
        settings.put("noise_router", router);
        CompoundTag rule = new CompoundTag();
        rule.putString("type", "minecraft:block");
        rule.put("result_state", blockState("minecraft:air", modern));
        settings.put(modern ? "material_rule" : "surface_rule", rule);
        generator.put("settings", settings);
        CompoundTag biomeSource = new CompoundTag();
        biomeSource.putString("type", "minecraft:fixed");
        biomeSource.putString("biome", biome);
        generator.put("biome_source", biomeSource);
        generator.putString("type", "minecraft:noise");
        return generator;
    }

    private static Tag blockState(String block, boolean modern) {
        if (modern) return StringTag.valueOf(block);
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        return state;
    }
}
