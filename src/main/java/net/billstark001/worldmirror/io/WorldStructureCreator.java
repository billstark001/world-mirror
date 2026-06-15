package net.billstark001.worldmirror.io;

import com.mojang.serialization.Lifecycle;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.PrimaryLevelData;
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

    public static CompoundTag createFlatGenerator() {
        CompoundTag generator = new CompoundTag();

        CompoundTag settings = new CompoundTag();
        settings.put("layers", new ListTag());
        settings.put("structure_overrides", new ListTag());
        settings.putString("biome", "minecraft:the_void");
        settings.putByte("features", (byte) 1);
        settings.putByte("lakes", (byte) 0);
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
        worldGenSettings.putByte("generate_features", (byte) 0);
        worldGenSettings.putLong("seed", 0L);

        return worldGenSettings;
    }

    public static CompoundTag createSpawnSettings(int x, int y, int z) {
        CompoundTag spawnSettings = new CompoundTag();
        spawnSettings.putString("dimension", "minecraft:overworld");
        spawnSettings.putInt("pitch", 0);
        spawnSettings.putInt("yaw", 0);
        IntArrayTag pos = new IntArrayTag(new int[] {x, y, z});
        spawnSettings.put("pos", pos);
        return spawnSettings;
    }

    public static CompoundTag createPlayerData() {
        CompoundTag player = new CompoundTag();
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

    private static String normalizeLevelName(String levelName) {
        return (levelName != null && !levelName.isEmpty()) ? levelName : DEFAULT_WORLD_NAME;
    }

    @SuppressWarnings({"deprecated"})
    private static PrimaryLevelData instantiateLevelProperties(String levelName) {
        LevelSettings levelInfo = new LevelSettings(
                levelName,
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(FeatureFlagSet.of()),
                WorldDataConfiguration.DEFAULT
        );
        WorldOptions generatorOptions = new WorldOptions(DEFAULT_SEED, false, false);
        @SuppressWarnings("deprecation") PrimaryLevelData properties = new PrimaryLevelData(
                levelInfo,
                generatorOptions,
                PrimaryLevelData.SpecialWorldProperty.FLAT,
                Lifecycle.stable()
        );

        properties.setDifficultyLocked(false);
        properties.setInitialized(true);
        properties.setRaining(false);
        properties.setRainTime(0);
        properties.setThundering(false);
        properties.setThunderTime(0);
        properties.setClearWeatherTime(0);
        properties.setGameTime(DEFAULT_DAYTIME);
        properties.setDayTime(DEFAULT_DAYTIME);

        return properties;
    }

    private static ListTag createStringList(Set<String> strings) {
        ListTag nbtList = new ListTag();
        Stream<StringTag> var10000 = strings.stream().map(StringTag::valueOf);
        Objects.requireNonNull(nbtList);
        var10000.forEach(nbtList::add);
        return nbtList;
    }

    /**
     * Matches the implementation of the corresponding method in {@link PrimaryLevelData}
     * as of Minecraft version 1.21.11.
     * * <p>This implementation substitutes or omits world gen information, as {@code ClientWorld}
     * does not provide such data.</p>
     * * <p><b>Maintenance Note:</b> During future mod updates, it is critical to ensure this
     * implementation maintains parity with the latest upstream logic in {@code LevelProperties}.</p>
     */
    private static CompoundTag serializeLevelProperties(PrimaryLevelData properties, @Nullable CompoundTag playerNbt) {

        CompoundTag levelNbt = new CompoundTag();

        levelNbt.put("ServerBrands", createStringList(properties.getKnownServerBrands()));
        levelNbt.putBoolean("WasModded", properties.wasModded());

        CompoundTag nbtCompound = new CompoundTag();
        nbtCompound.putString("Name", SharedConstants.getCurrentVersion().name());
        nbtCompound.putInt("Id", SharedConstants.getCurrentVersion().dataVersion().version());
        nbtCompound.putBoolean("Snapshot", !SharedConstants.getCurrentVersion().stable());
        nbtCompound.putString("Series", SharedConstants.getCurrentVersion().dataVersion().series());
        levelNbt.put("Version", nbtCompound);
        NbtUtils.addCurrentDataVersion(levelNbt);

        // world gen settings removed

        levelNbt.putInt("GameType", properties.getLevelSettings().gameType().getId());
        levelNbt.store("spawn", LevelData.RespawnData.CODEC, properties.getRespawnData());
        levelNbt.putLong("Time", properties.getGameTime());
        levelNbt.putLong("DayTime", properties.getDayTime());
        levelNbt.putLong("LastPlayed", Util.getEpochMillis());
        levelNbt.putString("LevelName", properties.getLevelSettings().levelName());
        levelNbt.putInt("version", 19133);
        levelNbt.putInt("clearWeatherTime", properties.getClearWeatherTime());
        levelNbt.putInt("rainTime", properties.getRainTime());
        levelNbt.putBoolean("raining", properties.isRaining());
        levelNbt.putInt("thunderTime", properties.getThunderTime());
        levelNbt.putBoolean("thundering", properties.isThundering());
        levelNbt.putBoolean("hardcore", properties.getLevelSettings().hardcore());
        levelNbt.putBoolean("allowCommands", properties.getLevelSettings().allowCommands());
        levelNbt.putBoolean("initialized", properties.isInitialized());
        properties.getLegacyWorldBorderSettings().ifPresent((worldBorder) -> levelNbt.store("world_border", WorldBorder.Settings.CODEC, worldBorder));
        levelNbt.putByte("Difficulty", (byte) properties.getLevelSettings().difficulty().getId());
        levelNbt.putBoolean("DifficultyLocked", properties.isDifficultyLocked());
        levelNbt.store("game_rules", GameRules.codec(properties.enabledFeatures()), properties.getLevelSettings().gameRules());
        levelNbt.store("DragonFight", EndDragonFight.Data.CODEC, properties.endDragonFightData());

        if (playerNbt != null) {
            levelNbt.put("Player", playerNbt);
        }

        levelNbt.store(WorldDataConfiguration.MAP_CODEC, properties.getLevelSettings().getDataConfiguration());
        if (properties.getCustomBossEvents() != null) {
            levelNbt.put("CustomBossEvents", properties.getCustomBossEvents());
        }

        levelNbt.put("ScheduledEvents", properties.getScheduledEvents().store());
        levelNbt.putInt("WanderingTraderSpawnDelay", properties.getWanderingTraderSpawnDelay());
        levelNbt.putInt("WanderingTraderSpawnChance", properties.getWanderingTraderSpawnChance());
        levelNbt.storeNullable("WanderingTraderId", UUIDUtil.CODEC, properties.getWanderingTraderId());

        return levelNbt;
    }

    /**
     * New preferred path: build world data through LevelProperties and then patch
     * fields that must stay equivalent to our historical mirror world output.
     */
    public static CompoundTag createWorldDataFromLevelProperties(String levelName) {
        String resolvedLevelName = normalizeLevelName(levelName);

        MappedRegistry<Registry<?>> registries = new MappedRegistry<>(
                ResourceKey.createRegistryKey(Registries.ROOT_REGISTRY_NAME),
                Lifecycle.stable(),
                true
        );
        MappedRegistry<LevelStem> dimensionOptions = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable(), true);
        registries.createIntrusiveHolder(dimensionOptions);

        CompoundTag player = createPlayerData();

        PrimaryLevelData properties = instantiateLevelProperties(resolvedLevelName);
        CompoundTag data = serializeLevelProperties(properties, player);

        // Preserve the existing mirror-world generation profile and spawn shape.
        data.put("WorldGenSettings", createFlatWorldGenSettings());
        data.put("spawn", createSpawnSettings(0, 80, 0));
        data.putLong("RandomSeed", DEFAULT_SEED);

        return data;
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
                CompoundTag root = new CompoundTag();
                CompoundTag data = createWorldDataFromLevelProperties(levelName);
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
