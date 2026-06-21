package net.billstark001.worldmirror.io;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.serialization.Lifecycle;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;

@Environment(EnvType.CLIENT)
public class WorldStructureCreator {

    public static CompoundTag createFlatGenerator() {
        CompoundTag generator = new CompoundTag();

        CompoundTag settings = new CompoundTag();
        ListTag layers = new ListTag();
        CompoundTag airLayer = new CompoundTag();
        airLayer.putString("block", "minecraft:air");
        airLayer.putInt("height", 1);
        layers.add(airLayer);
        settings.put("layers", layers);
        settings.put("structure_overrides", new ListTag());
        settings.putString("biome", "minecraft:the_void");
        generator.put("settings", settings);
        generator.putString("type", "minecraft:flat");

        return generator;
    }

    public static CompoundTag createFlatWorldGenSettings() {
        CompoundTag worldGenSettings = new CompoundTag();
        // --- dimensions ---
        CompoundTag dimensions = new CompoundTag();

        // minecraft:overworld
        CompoundTag overworld = new CompoundTag();
        overworld.put("generator", createFlatGenerator());
        overworld.putString("type", "minecraft:overworld");
        dimensions.put("minecraft:overworld", overworld);

        // minecraft:the_end
        CompoundTag theEnd = new CompoundTag();
        theEnd.put("generator", createFlatGenerator());
        theEnd.putString("type", "minecraft:the_end");
        dimensions.put("minecraft:the_end", theEnd);

        // minecraft:the_nether
        CompoundTag theNether = new CompoundTag();
        theNether.put("generator", createFlatGenerator());
        theNether.putString("type", "minecraft:the_nether");
        dimensions.put("minecraft:the_nether", theNether);

        worldGenSettings.put("dimensions", dimensions);
        worldGenSettings.putByte("bonus_chest", (byte) 0);
        worldGenSettings.putByte("generate_structures", (byte) 0);
        worldGenSettings.putLong("seed", 0L);

        return worldGenSettings;
    }

    public static PrimaryLevelData createWorldData(String levelName) {
        String resolvedName = (levelName != null && !levelName.isEmpty())
                ? levelName
                : "Downloaded World";
        LevelSettings settings = new LevelSettings(
                resolvedName,
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true,
                WorldDataConfiguration.DEFAULT
        );
        PrimaryLevelData data = new PrimaryLevelData(
                settings,
                PrimaryLevelData.SpecialWorldProperty.FLAT,
                Lifecycle.stable()
        );
        data.setInitialized(true);
        data.setGameTime(6000L);
        data.setSpawn(LevelData.RespawnData.of(Level.OVERWORLD, new BlockPos(0, 80, 0), 0.0F, 0.0F));
        return data;
    }

    private static CompoundTag createPlayerData() {
        CompoundTag player = new CompoundTag();
        NbtUtils.addCurrentDataVersion(player);
        player.putString("Dimension", "minecraft:overworld");

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(0.0D));
        pos.add(DoubleTag.valueOf(80.0D));
        pos.add(DoubleTag.valueOf(0.0D));
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

    private static GameRules createGameRules(WorldDataConfiguration dataConfiguration) {
        GameRules gameRules = new GameRules(dataConfiguration.enabledFeatures());
        gameRules.set(GameRules.ADVANCE_TIME, false, null);
        gameRules.set(GameRules.SPAWN_MOBS, false, null);
        gameRules.set(GameRules.RANDOM_TICK_SPEED, 0, null);
        return gameRules;
    }

    private static CompoundTag createWeatherData() {
        CompoundTag weather = new CompoundTag();
        weather.putInt("clear_weather_time", 0);
        weather.putInt("rain_time", 0);
        weather.putInt("thunder_time", 0);
        weather.putBoolean("raining", false);
        weather.putBoolean("thundering", false);
        return weather;
    }

    private static CompoundTag createWorldClocksData() {
        CompoundTag clocks = new CompoundTag();
        CompoundTag clockStates = new CompoundTag();
        clockStates.put("minecraft:overworld", createClockState(6000L));
        clockStates.put("minecraft:the_end", createClockState(6000L));
        clocks.put("clocks", clockStates);
        return clocks;
    }

    private static CompoundTag createClockState(long totalTicks) {
        CompoundTag state = new CompoundTag();
        state.putLong("total_ticks", totalTicks);
        state.putFloat("partial_tick", 0.0F);
        state.putFloat("rate", 1.0F);
        state.putBoolean("paused", false);
        return state;
    }

    /**
     * Creates the {@code level.dat} for a new nearby-export world, setting the
     * spawn point to the player's current block position.
     *
     * @param worldFolderPath root directory of the new world
     * @param levelName       human-readable name for the save
     * @param spawnX          spawn block X coordinate
     * @param spawnY          spawn block Y coordinate
     * @param spawnZ          spawn block Z coordinate
     */
    public static void createLoadableWorldWithSpawn(Path worldFolderPath, String levelName,
                                                    int spawnX, int spawnY, int spawnZ) {
        try {
            createLoadableWorld(worldFolderPath, levelName, null);

            PrimaryLevelData data = createWorldData(levelName);
            data.setSpawn(LevelData.RespawnData.of(
                    Level.OVERWORLD, new BlockPos(spawnX, spawnY, spawnZ), 0.0F, 0.0F));

            UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                    ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
            writeLevelDat(worldFolderPath.resolve("level.dat").toFile(), data, singleplayerUuid);
            writeCompressed(
                    worldFolderPath.resolve("players/data/" + singleplayerUuid + ".dat").toFile(),
                    createPlayerData());
            WMLogger.debug("Nearby-export world created at: " + worldFolderPath.toAbsolutePath());
        } catch (Exception e) {
            WMLogger.warn("createLoadableWorldWithSpawn failed: " + e.getMessage());
        }
    }

    /**
     * Creates or updates the {@code level.dat} and supporting directory structure
     * for a mirror world.
     *
     * <p>On the <em>first</em> call (no {@code level.dat} yet), a full {@code level.dat}
     * is written and "World structure created" is logged.  On subsequent calls the
     * {@code session.lock} timestamp is refreshed and "World structure updated" is logged
     * to distinguish incremental sync from initial creation.
     *
     * @param worldFolder  root directory of the mirror world

    private static CompoundTag createWeatherData() {
        CompoundTag weather = new CompoundTag();
        weather.putInt("clear_weather_time", 0);
        weather.putInt("rain_time", 0);
        weather.putInt("thunder_time", 0);
        weather.putBoolean("raining", false);
        weather.putBoolean("thundering", false);
        return weather;
    }

    private static CompoundTag createWorldClocksData() {
        CompoundTag clocks = new CompoundTag();
        CompoundTag clockStates = new CompoundTag();
        clockStates.put("minecraft:overworld", createClockState(6000L));
        clockStates.put("minecraft:the_end", createClockState(6000L));
        clocks.put("clocks", clockStates);
        return clocks;
    }

    private static CompoundTag createClockState(long totalTicks) {
        CompoundTag state = new CompoundTag();
        state.putLong("total_ticks", totalTicks);
        state.putFloat("partial_tick", 0.0F);
        state.putFloat("rate", 1.0F);
        state.putBoolean("paused", false);
        return state;
    }

    /**
     * Creates or updates the {@code level.dat} and supporting directory structure
     * for a mirror world.
     *
     * <p>On the <em>first</em> call (no {@code level.dat} yet), a full {@code level.dat}
     * is written and "World structure created" is logged.  On subsequent calls the
     * {@code session.lock} timestamp is refreshed and "World structure updated" is logged
     * to distinguish incremental sync from initial creation.
     *
     * @param worldFolder  root directory of the mirror world
     * @param levelName    human-readable name to embed in {@code level.dat}
     */
    public static void createLoadableWorld(java.nio.file.Path worldFolderPath, String levelName, RegistryAccess registryAccess) {
        File worldFolder = worldFolderPath.toFile();
        try {
            boolean firstTime = !(new File(worldFolder, "level.dat")).exists();

            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }

            String[] subDirs = worldSubDirs();
            for (String dir : subDirs) {
                mkdirs(worldFolder, dir);
            }

            Files.writeString(worldFolderPath.resolve("session.lock"), "\u2603", StandardCharsets.UTF_8);

            if (firstTime) {
                UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                        ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
                PrimaryLevelData data = createWorldData(levelName);
                writeLevelDat(new File(worldFolder, "level.dat"), data, singleplayerUuid);
                writeCompressed(new File(worldFolder, "players/data/" + singleplayerUuid + ".dat"), createPlayerData());
                writeWorldGenSettings(worldFolderPath, registryAccess);
                LevelStorageSource.writeGameRules(data, worldFolderPath, createGameRules(data.getDataConfiguration()));
                writeSavedData(new File(worldFolder, "data/minecraft/weather.dat"), createWeatherData());
                writeSavedData(new File(worldFolder, "data/minecraft/world_clocks.dat"), createWorldClocksData());
                WMLogger.debug("World structure created at: " + worldFolder.getAbsolutePath()
                        + " (name: " + data.getLevelName() + ")");
            } else {
                WMLogger.debug("World structure updated (incremental sync): " + worldFolder.getAbsolutePath());
            }
        } catch (Exception e) {
            WMLogger.warn("Failed to create loadable world: " + e.getMessage());
        }
    }

    private static void mkdirs(File worldFolder, String relativePath) {
        (new File(worldFolder, relativePath)).mkdirs();
    }

    private static String[] worldSubDirs() {
        String[] common = {
                "players/advancements",
                "players/data",
                "players/stats",
                "data/minecraft",
                "datapacks",
                "resourcepacks"
        };
        String[] dimensionDirs = usesLegacyVanillaDimensionLayout()
                ? new String[] {
                    "region",
                    "entities",
                    "poi",
                    "DIM-1/region",
                    "DIM-1/entities",
                    "DIM-1/poi",
                    "DIM1/region",
                    "DIM1/entities",
                    "DIM1/poi"
                }
                : new String[] {
                    "dimensions/minecraft/overworld/region",
                    "dimensions/minecraft/overworld/entities",
                    "dimensions/minecraft/overworld/poi",
                    "dimensions/minecraft/overworld/data/minecraft",
                    "dimensions/minecraft/the_nether/region",
                    "dimensions/minecraft/the_nether/entities",
                    "dimensions/minecraft/the_nether/poi",
                    "dimensions/minecraft/the_nether/data/minecraft",
                    "dimensions/minecraft/the_end/region",
                    "dimensions/minecraft/the_end/entities",
                    "dimensions/minecraft/the_end/poi",
                    "dimensions/minecraft/the_end/data/minecraft"
                };
        String[] subDirs = new String[dimensionDirs.length + common.length];
        System.arraycopy(dimensionDirs, 0, subDirs, 0, dimensionDirs.length);
        System.arraycopy(common, 0, subDirs, dimensionDirs.length, common.length);
        return subDirs;
    }

    private static boolean usesLegacyVanillaDimensionLayout() {
        return "1.21.11".equals(SharedConstants.getCurrentVersion().id());
    }

    private static void writeLevelDat(File file, PrimaryLevelData data, UUID singleplayerUuid) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("Data", data.createTag(singleplayerUuid));
        writeCompressed(file, root);
    }

    private static void writeWorldGenSettings(java.nio.file.Path worldFolderPath, RegistryAccess registryAccess) throws Exception {
        if (registryAccess != null) {
            try {
                WorldGenSettings settings = new WorldGenSettings(
                        new WorldOptions(0L, false, false),
                        registryAccess.lookupOrThrow(Registries.WORLD_PRESET)
                                .getOrThrow(WorldPresets.FLAT_ALL_DIMENSIONS)
                                .value()
                                .createWorldDimensions()
                );
                LevelStorageSource.writeWorldGenSettings(registryAccess, worldFolderPath, settings);
                return;
            } catch (Exception e) {
                WMLogger.warn("Failed to write WorldGenSettings with registry: " + e.getMessage() + ". Falling back to manual NBT.");
            }
        }
        writeSavedData(worldFolderPath.resolve("data/minecraft/world_gen_settings.dat").toFile(),
                createFlatWorldGenSettings());
    }

    private static void writeSavedData(File file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        writeCompressed(file, root);
    }

    private static void writeCompressed(File file, CompoundTag tag) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            NbtIo.writeCompressed(tag, fos);
        }
    }
}
