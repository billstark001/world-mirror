package net.billstark001.worldmirror.io;

import com.mojang.serialization.Lifecycle;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.util.Util;
import net.minecraft.util.Uuids;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.rule.GameRules;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Environment(EnvType.CLIENT)
public class WorldStructureCreator {

    private static final String DEFAULT_WORLD_NAME = "Downloaded World";
    private static final long DEFAULT_SEED = 0L;
    private static final long DEFAULT_DAYTIME = 6000L;

    public static NbtCompound createFlatGenerator() {
        NbtCompound generator = new NbtCompound();

        NbtCompound settings = new NbtCompound();
        settings.put("layers", new NbtList());
        settings.put("structure_overrides", new NbtList());
        settings.putString("biome", "minecraft:the_void");
        settings.putByte("features", (byte) 1);
        settings.putByte("lakes", (byte) 0);
        generator.put("settings", settings);
        generator.putString("type", "minecraft:flat");

        return generator;
    }

    public static NbtCompound createFlatWorldGenSettings() {
        NbtCompound worldGenSettings = new NbtCompound();

        // --- dimensions ---
        NbtCompound dimensions = new NbtCompound();

        // minecraft:overworld
        NbtCompound overworld = new NbtCompound();
        overworld.put("generator", createFlatGenerator());
        overworld.putString("type", "minecraft:overworld");
        dimensions.put("minecraft:overworld", overworld);

        // minecraft:the_end
        NbtCompound theEnd = new NbtCompound();
        theEnd.put("generator", createFlatGenerator());
        theEnd.putString("type", "minecraft:the_end");
        dimensions.put("minecraft:the_end", theEnd);

        // minecraft:the_nether
        NbtCompound theNether = new NbtCompound();
        theNether.put("generator", createFlatGenerator());
        theNether.putString("type", "minecraft:the_nether");
        dimensions.put("minecraft:the_nether", theNether);

        worldGenSettings.put("dimensions", dimensions);
        worldGenSettings.putByte("bonus_chest", (byte) 0);
        worldGenSettings.putByte("generate_features", (byte) 0);
        worldGenSettings.putLong("seed", 0L);

        return worldGenSettings;
    }

    public static NbtCompound createSpawnSettings(int x, int y, int z) {
        NbtCompound spawnSettings = new NbtCompound();
        spawnSettings.putString("dimension", "minecraft:overworld");
        spawnSettings.putInt("pitch", 0);
        spawnSettings.putInt("yaw", 0);
        NbtIntArray pos = new NbtIntArray(new int[] {x, y, z});
        spawnSettings.put("pos", pos);
        return spawnSettings;
    }

    public static NbtCompound createPlayerData() {
        NbtCompound player = new NbtCompound();
        player.putString("Dimension", "minecraft:overworld");

        NbtList pos = new NbtList();
        pos.add(NbtDouble.of(0.0D));
        pos.add(NbtDouble.of(80.0D));
        pos.add(NbtDouble.of(0.0D));
        player.put("Pos", pos);

        NbtList rotation = new NbtList();
        rotation.add(NbtFloat.of(0.0F));
        rotation.add(NbtFloat.of(0.0F));
        player.put("Rotation", rotation);

        NbtList motion = new NbtList();
        motion.add(NbtDouble.of(0.0D));
        motion.add(NbtDouble.of(0.0D));
        motion.add(NbtDouble.of(0.0D));
        player.put("Motion", motion);

        player.putFloat("Health", 20.0F);
        player.putInt("playerGameType", 1);
        player.putBoolean("OnGround", true);
        player.putInt("Score", 0);
        player.putShort("Air", (short) 300);
        player.putShort("Fire", (short) -20);

        player.put("Inventory", new NbtList());
        player.put("EnderItems", new NbtList());

        return player;
    }

    private static String normalizeLevelName(String levelName) {
        return (levelName != null && !levelName.isEmpty()) ? levelName : DEFAULT_WORLD_NAME;
    }

    @SuppressWarnings({"deprecated"})
    private static LevelProperties instantiateLevelProperties(String levelName) {
        LevelInfo levelInfo = new LevelInfo(
                levelName,
                GameMode.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(FeatureSet.empty()),
                DataConfiguration.SAFE_MODE
        );
        GeneratorOptions generatorOptions = new GeneratorOptions(DEFAULT_SEED, false, false);
        @SuppressWarnings("deprecation") LevelProperties properties = new LevelProperties(
                levelInfo,
                generatorOptions,
                LevelProperties.SpecialProperty.FLAT,
                Lifecycle.stable()
        );

        properties.setDifficultyLocked(false);
        properties.setInitialized(true);
        properties.setRaining(false);
        properties.setRainTime(0);
        properties.setThundering(false);
        properties.setThunderTime(0);
        properties.setClearWeatherTime(0);
        properties.setTime(DEFAULT_DAYTIME);
        properties.setTimeOfDay(DEFAULT_DAYTIME);

        return properties;
    }

    private static NbtList createStringList(Set<String> strings) {
        NbtList nbtList = new NbtList();
        Stream<NbtString> var10000 = strings.stream().map(NbtString::of);
        Objects.requireNonNull(nbtList);
        var10000.forEach(nbtList::add);
        return nbtList;
    }

    /**
     * Matches the implementation of the corresponding method in {@link LevelProperties}
     * as of Minecraft version 1.21.11.
     * * <p>This implementation substitutes or omits world gen information, as {@code ClientWorld}
     * does not provide such data.</p>
     * * <p><b>Maintenance Note:</b> During future mod updates, it is critical to ensure this
     * implementation maintains parity with the latest upstream logic in {@code LevelProperties}.</p>
     */
    private static NbtCompound serializeLevelProperties(LevelProperties properties, @Nullable NbtCompound playerNbt) {

        NbtCompound levelNbt = new NbtCompound();

        levelNbt.put("ServerBrands", createStringList(properties.getServerBrands()));
        levelNbt.putBoolean("WasModded", properties.isModded());

        NbtCompound nbtCompound = new NbtCompound();
        nbtCompound.putString("Name", SharedConstants.getGameVersion().name());
        nbtCompound.putInt("Id", SharedConstants.getGameVersion().dataVersion().id());
        nbtCompound.putBoolean("Snapshot", !SharedConstants.getGameVersion().stable());
        nbtCompound.putString("Series", SharedConstants.getGameVersion().dataVersion().series());
        levelNbt.put("Version", nbtCompound);
        NbtHelper.putDataVersion(levelNbt);

        // world gen settings removed

        levelNbt.putInt("GameType", properties.getLevelInfo().getGameMode().getIndex());
        levelNbt.put("spawn", WorldProperties.SpawnPoint.CODEC, properties.getSpawnPoint());
        levelNbt.putLong("Time", properties.getTime());
        levelNbt.putLong("DayTime", properties.getTimeOfDay());
        levelNbt.putLong("LastPlayed", Util.getEpochTimeMs());
        levelNbt.putString("LevelName", properties.getLevelInfo().getLevelName());
        levelNbt.putInt("version", 19133);
        levelNbt.putInt("clearWeatherTime", properties.getClearWeatherTime());
        levelNbt.putInt("rainTime", properties.getRainTime());
        levelNbt.putBoolean("raining", properties.isRaining());
        levelNbt.putInt("thunderTime", properties.getThunderTime());
        levelNbt.putBoolean("thundering", properties.isThundering());
        levelNbt.putBoolean("hardcore", properties.getLevelInfo().isHardcore());
        levelNbt.putBoolean("allowCommands", properties.getLevelInfo().areCommandsAllowed());
        levelNbt.putBoolean("initialized", properties.isInitialized());
        properties.getWorldBorder().ifPresent((worldBorder) -> levelNbt.put("world_border", WorldBorder.Properties.CODEC, worldBorder));
        levelNbt.putByte("Difficulty", (byte) properties.getLevelInfo().getDifficulty().getId());
        levelNbt.putBoolean("DifficultyLocked", properties.isDifficultyLocked());
        levelNbt.put("game_rules", GameRules.createCodec(properties.getEnabledFeatures()), properties.getLevelInfo().getGameRules());
        levelNbt.put("DragonFight", EnderDragonFight.Data.CODEC, properties.getDragonFight());

        if (playerNbt != null) {
            levelNbt.put("Player", playerNbt);
        }

        levelNbt.copyFromCodec(DataConfiguration.MAP_CODEC, properties.getLevelInfo().getDataConfiguration());
        if (properties.getCustomBossEvents() != null) {
            levelNbt.put("CustomBossEvents", properties.getCustomBossEvents());
        }

        levelNbt.put("ScheduledEvents", properties.getScheduledEvents().toNbt());
        levelNbt.putInt("WanderingTraderSpawnDelay", properties.getWanderingTraderSpawnDelay());
        levelNbt.putInt("WanderingTraderSpawnChance", properties.getWanderingTraderSpawnChance());
        levelNbt.putNullable("WanderingTraderId", Uuids.INT_STREAM_CODEC, properties.getWanderingTraderId());

        return levelNbt;
    }

    /**
     * New preferred path: build world data through LevelProperties and then patch
     * fields that must stay equivalent to our historical mirror world output.
     */
    public static NbtCompound createWorldDataFromLevelProperties(String levelName) {
        String resolvedLevelName = normalizeLevelName(levelName);

        SimpleRegistry<Registry<?>> registries = new SimpleRegistry<>(
                RegistryKey.ofRegistry(RegistryKeys.ROOT),
                Lifecycle.stable(),
                true
        );
        SimpleRegistry<DimensionOptions> dimensionOptions = new SimpleRegistry<>(RegistryKeys.DIMENSION, Lifecycle.stable(), true);
        registries.createEntry(dimensionOptions);

        NbtCompound player = createPlayerData();

        LevelProperties properties = instantiateLevelProperties(resolvedLevelName);
        NbtCompound data = serializeLevelProperties(properties, player);

        // Preserve the existing mirror-world generation profile and spawn shape.
        data.put("WorldGenSettings", createFlatWorldGenSettings());
        data.put("spawn", createSpawnSettings(0, 80, 0));
        data.putLong("RandomSeed", DEFAULT_SEED);

        return data;
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
            DataOutputStream out = getOutputStream(worldFolderPath);
            out.writeLong(System.currentTimeMillis());
            out.close();

            NbtCompound data = createWorldDataFromLevelProperties(levelName);
            data.put("WorldGenSettings", createFlatWorldGenSettings());
            data.put("spawn", createSpawnSettings(spawnX, spawnY, spawnZ));
            data.putLong("RandomSeed", DEFAULT_SEED);

            NbtCompound root = new NbtCompound();
            root.put("Data", data);
            File levelDat = worldFolderPath.resolve("level.dat").toFile();
            FileOutputStream fos = new FileOutputStream(levelDat);
            NbtIo.writeCompressed(root, fos);
            fos.close();
            WMLogger.info("Nearby-export world created at: " + worldFolderPath.toAbsolutePath());
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
     * @param worldFolderPath  root directory of the mirror world
     * @param levelName    human-readable name to embed in {@code level.dat}
     */
    public static void createLoadableWorld(Path worldFolderPath, String levelName) {
        try {
            boolean firstTime = !(worldFolderPath.resolve("level.dat").toFile()).exists();

            DataOutputStream out = getOutputStream(worldFolderPath);
            try {
                out.writeLong(System.currentTimeMillis());
                out.close();
            } catch (Throwable throwable) {
                try {
                    out.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
                throw throwable;
            }
            // Only write level.dat on first creation; on subsequent syncs just refresh session.lock.
            if (firstTime) {
                NbtCompound root = new NbtCompound();
                NbtCompound data = createWorldDataFromLevelProperties(levelName);
                root.put("Data", data);

                File levelDat = worldFolderPath.resolve("level.dat").toFile();
                FileOutputStream fos = new FileOutputStream(levelDat);
                try {
                    NbtIo.writeCompressed(root, fos);
                    fos.close();
                } catch (Throwable throwable) {
                    try {
                        fos.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                    throw throwable;
                }
                WMLogger.info("World structure created at: " + worldFolderPath.toAbsolutePath()
                        + " (name: " + data.getString("LevelName") + ")");
            } else {
                WMLogger.info("World structure updated (incremental sync): " + worldFolderPath.toAbsolutePath());
            }
        } catch (Exception e) {
            WMLogger.warn("Failed to create loadable world: " + e.getMessage());
        }
    }

    private static final String[] worldFolderSubPaths;
    static {
        worldFolderSubPaths = new String[] {
                "region",
                "playerdata",
                "advancements",
                "stats",
                "data",
                "poi",
                "entities"
        };
    }

    private static @NonNull DataOutputStream getOutputStream(Path worldFolderPath) throws IOException {
        Files.createDirectories(worldFolderPath);

        for (String subPath : worldFolderSubPaths) {
            Files.createDirectories(worldFolderPath.resolve(subPath));
        }

        File sessionLock = worldFolderPath.resolve("session.lock").toFile();
        return new DataOutputStream(new FileOutputStream(sessionLock));
    }
}
