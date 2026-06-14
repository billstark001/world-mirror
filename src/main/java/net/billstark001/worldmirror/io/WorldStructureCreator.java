package net.billstark001.worldmirror.io;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;

@Environment(EnvType.CLIENT)
public class WorldStructureCreator {

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
        worldGenSettings.putByte("generate_structures", (byte) 0);
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

    public static CompoundTag createWorldData(String levelName, UUID singleplayerUuid) {

        CompoundTag data = new CompoundTag();

        data.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());

        data.putString("LevelName", (levelName != null && !levelName.isEmpty())
                ? levelName : "Downloaded World");
        data.putLong("RandomSeed", 0L); // seed is irrelevant for void superflat; 0 keeps it deterministic
        data.putInt("version", 19133);
        data.putBoolean("initialized", true);

        data.putInt("GameType", 1);
        data.putBoolean("allowCommands", true);
        data.putBoolean("hardcore", false);

        CompoundTag difficultySettings = new CompoundTag();
        difficultySettings.putString("difficulty", "peaceful");
        difficultySettings.putBoolean("locked", false);
        data.put("difficulty_settings", difficultySettings);

        data.putString("singleplayer_uuid", singleplayerUuid.toString());

        CompoundTag spawnSettings = createSpawnSettings(0, 80, 0);
        data.put("spawn", spawnSettings);

        data.putLong("LastPlayed", System.currentTimeMillis());

        CompoundTag worldBorder = new CompoundTag();
        worldBorder.putDouble("BorderCenterX", 0.0D);
        worldBorder.putDouble("BorderCenterZ", 0.0D);
        worldBorder.putDouble("BorderSize", 5.9999968E7D);
        worldBorder.putDouble("BorderSizeLerpTarget", 5.9999968E7D);
        worldBorder.putLong("BorderSizeLerpTime", 0L);
        worldBorder.putDouble("BorderSafeZone", 5.0D);
        worldBorder.putDouble("BorderDamagePerBlock", 0.2D);
        worldBorder.putInt("BorderWarningBlocks", 5);
        worldBorder.putInt("BorderWarningTime", 15);
        data.put("WorldBorder", worldBorder);

        return data;
    }

    private static CompoundTag createPlayerData() {
        CompoundTag player = new CompoundTag();
        player.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
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

    private static CompoundTag createGameRules() {
        CompoundTag gameRules = new CompoundTag();
        gameRules.putString("doDaylightCycle", "false");
        gameRules.putString("doMobSpawning", "false");
        gameRules.putString("keepInventory", "false");
        gameRules.putString("randomTickSpeed", "0");
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
    public static void createLoadableWorld(java.nio.file.Path worldFolderPath, String levelName) {
        File worldFolder = worldFolderPath.toFile();
        try {
            boolean firstTime = !(new File(worldFolder, "level.dat")).exists();

            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }

            mkdirs(worldFolder, "dimensions/minecraft/overworld/region");
            mkdirs(worldFolder, "dimensions/minecraft/overworld/entities");
            mkdirs(worldFolder, "dimensions/minecraft/overworld/poi");
            mkdirs(worldFolder, "dimensions/minecraft/overworld/data/minecraft");
            mkdirs(worldFolder, "dimensions/minecraft/the_nether/region");
            mkdirs(worldFolder, "dimensions/minecraft/the_nether/entities");
            mkdirs(worldFolder, "dimensions/minecraft/the_nether/poi");
            mkdirs(worldFolder, "dimensions/minecraft/the_nether/data/minecraft");
            mkdirs(worldFolder, "dimensions/minecraft/the_end/region");
            mkdirs(worldFolder, "dimensions/minecraft/the_end/entities");
            mkdirs(worldFolder, "dimensions/minecraft/the_end/poi");
            mkdirs(worldFolder, "dimensions/minecraft/the_end/data/minecraft");
            mkdirs(worldFolder, "players/advancements");
            mkdirs(worldFolder, "players/data");
            mkdirs(worldFolder, "players/stats");
            mkdirs(worldFolder, "data/minecraft");
            mkdirs(worldFolder, "resourcepacks");


            File sessionLock = new File(worldFolder, "session.lock");
            DataOutputStream out = new DataOutputStream(new FileOutputStream(sessionLock));
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
                UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                        ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
                CompoundTag root = new CompoundTag();
                CompoundTag data = createWorldData(levelName, singleplayerUuid);
                root.put("Data", data);

                writeCompressed(new File(worldFolder, "level.dat"), root);
                writeCompressed(new File(worldFolder, "players/data/" + singleplayerUuid + ".dat"), createPlayerData());
                writeCompressed(new File(worldFolder, "data/minecraft/world_gen_settings.dat"), createFlatWorldGenSettings());
                writeCompressed(new File(worldFolder, "data/minecraft/game_rules.dat"), createGameRules());
                writeCompressed(new File(worldFolder, "data/minecraft/weather.dat"), createWeatherData());
                writeCompressed(new File(worldFolder, "data/minecraft/world_clocks.dat"), createWorldClocksData());
                WMLogger.info("World structure created at: " + worldFolder.getAbsolutePath()
                        + " (name: " + data.getString("LevelName") + ")");
            } else {
                WMLogger.info("World structure updated (incremental sync): " + worldFolder.getAbsolutePath());
            }
        } catch (Exception e) {
            WMLogger.warn("Failed to create loadable world: " + e.getMessage());
        }
    }

    private static void mkdirs(File worldFolder, String relativePath) {
        (new File(worldFolder, relativePath)).mkdirs();
    }

    private static void writeCompressed(File file, CompoundTag tag) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            NbtIo.writeCompressed(tag, fos);
        }
    }
}
