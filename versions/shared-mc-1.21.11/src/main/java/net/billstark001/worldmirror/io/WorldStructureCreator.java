package net.billstark001.worldmirror.io;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

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
        CompoundTag dimensions = new CompoundTag();

        CompoundTag overworld = new CompoundTag();
        overworld.put("generator", createFlatGenerator());
        overworld.putString("type", "minecraft:overworld");
        dimensions.put("minecraft:overworld", overworld);

        CompoundTag theEnd = new CompoundTag();
        theEnd.put("generator", createFlatGenerator());
        theEnd.putString("type", "minecraft:the_end");
        dimensions.put("minecraft:the_end", theEnd);

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

    public static CompoundTag createWorldData(String levelName) {
        return createWorldData(levelName, 0, 80, 0);
    }

    public static CompoundTag createWorldData(String levelName, int spawnX, int spawnY, int spawnZ) {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        data.putString("LevelName", (levelName != null && !levelName.isEmpty())
                ? levelName
                : "Downloaded World");
        data.putLong("RandomSeed", 0L);
        data.putInt("version", 19133);
        data.putBoolean("initialized", true);
        data.putInt("GameType", 1);
        data.putBoolean("allowCommands", true);
        data.putBoolean("hardcore", false);
        data.putInt("Difficulty", 0);
        data.putBoolean("DifficultyLocked", false);
        data.put("WorldGenSettings", createFlatWorldGenSettings());
        data.put("spawn", createSpawnSettings(spawnX, spawnY, spawnZ));
        data.putLong("Time", 6000L);
        data.putLong("DayTime", 6000L);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.put("WorldBorder", createWorldBorder());
        data.put("game_rules", createGameRules());
        data.put("Player", createPlayerData(spawnX, spawnY, spawnZ));
        return data;
    }

    private static CompoundTag createSpawnSettings(int x, int y, int z) {
        CompoundTag spawn = new CompoundTag();
        spawn.putString("dimension", "minecraft:overworld");
        spawn.putFloat("pitch", 0.0F);
        spawn.putFloat("yaw", 0.0F);
        spawn.put("pos", new IntArrayTag(new int[] {x, y, z}));
        return spawn;
    }

    private static CompoundTag createWorldBorder() {
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
        return worldBorder;
    }

    private static CompoundTag createGameRules() {
        CompoundTag gameRules = new CompoundTag();
        gameRules.putString("doDaylightCycle", "false");
        gameRules.putString("doMobSpawning", "false");
        gameRules.putString("randomTickSpeed", "0");
        return gameRules;
    }

    private static CompoundTag createPlayerData() {
        return createPlayerData(0, 80, 0);
    }

    private static CompoundTag createPlayerData(int x, int y, int z) {
        CompoundTag player = new CompoundTag();
        NbtUtils.addCurrentDataVersion(player);
        player.putString("Dimension", "minecraft:overworld");

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

    public static void createLoadableWorldWithSpawn(Path worldFolderPath, String levelName,
                                                    int spawnX, int spawnY, int spawnZ) {
        try {
            createLoadableWorld(worldFolderPath, levelName, null);
            CompoundTag data = createWorldData(levelName, spawnX, spawnY, spawnZ);
            UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                    ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
            writeLevelDat(worldFolderPath.resolve("level.dat").toFile(), data);
            writeCompressed(
                    worldFolderPath.resolve("playerdata/" + singleplayerUuid + ".dat").toFile(),
                    createPlayerData(spawnX, spawnY, spawnZ));
            WMLogger.debug("Nearby-export world created at: " + worldFolderPath.toAbsolutePath());
        } catch (Exception e) {
            WMLogger.warn("createLoadableWorldWithSpawn failed: " + e.getMessage());
        }
    }

    public static void createLoadableWorld(Path worldFolderPath, String levelName, RegistryAccess registryAccess) {
        File worldFolder = worldFolderPath.toFile();
        try {
            boolean firstTime = !(new File(worldFolder, "level.dat")).exists();

            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }

            for (String dir : worldSubDirs()) {
                mkdirs(worldFolder, dir);
            }

            writeSessionLock(new File(worldFolder, "session.lock"));

            if (firstTime) {
                CompoundTag data = createWorldData(levelName);
                UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                        ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
                writeLevelDat(new File(worldFolder, "level.dat"), data);
                writeCompressed(new File(worldFolder, "playerdata/" + singleplayerUuid + ".dat"), createPlayerData());
                WMLogger.debug("World structure created at: " + worldFolder.getAbsolutePath()
                        + " (name: " + data.getString("LevelName").orElse("Downloaded World") + ")");
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
        return new String[] {
                "region",
                "entities",
                "poi",
                "DIM-1/region",
                "DIM-1/entities",
                "DIM-1/poi",
                "DIM1/region",
                "DIM1/entities",
                "DIM1/poi",
                "playerdata",
                "advancements",
                "stats",
                "data",
                "datapacks",
                "resourcepacks"
        };
    }

    private static void writeSessionLock(File file) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeLong(System.currentTimeMillis());
        }
    }

    private static void writeLevelDat(File file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        writeCompressed(file, root);
    }

    private static void writeCompressed(File file, CompoundTag tag) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            NbtIo.writeCompressed(tag, fos);
        }
    }
}
