package net.billstark001.worldmirror.io;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;

@Environment(EnvType.CLIENT)
public class WorldStructureCreator {

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

    public static NbtCompound createWorldData(String levelName) {

        NbtCompound data = new NbtCompound();

        data.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());

        data.putString("LevelName", (levelName != null && !levelName.isEmpty())
                ? levelName : "Downloaded World");
        data.putLong("RandomSeed", 0L); // seed is irrelevant for void superflat; 0 keeps it deterministic
        data.putInt("version", 19133);
        data.putBoolean("initialized", true);

        data.putInt("GameType", 1);
        data.putBoolean("allowCommands", true);
        data.putBoolean("hardcore", false);
        data.putInt("Difficulty", 0);
        data.putBoolean("DifficultyLocked", false);

        // Void superflat — prevents the game from generating any terrain for
        // chunks that have not been downloaded.  The generator settings string
        // encodes a superflat world with a single air layer and no structures.
        data.put("WorldGenSettings", createFlatWorldGenSettings());

        NbtCompound spawnSettings = createSpawnSettings(0, 80, 0);
        data.put("spawn", spawnSettings);

        data.putLong("Time", 6000L);
        data.putLong("DayTime", 6000L);
        data.putLong("LastPlayed", System.currentTimeMillis());

        NbtCompound worldBorder = new NbtCompound();
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

        NbtCompound gameRules = new NbtCompound();
//                gameRules.putString("keepInventory", "false");
//                gameRules.putString("mobGriefing", "false");
//                gameRules.putString("doFireTick", "false");
//                gameRules.putString("doMobSpawning", "false");
//                gameRules.putString("doMobLoot", "true");
//                gameRules.putString("doTileDrops", "true");
//                gameRules.putString("commandBlockOutput", "true");
//                gameRules.putString("naturalRegeneration", "true");
//                gameRules.putString("doDaylightCycle", "false");
//                gameRules.putString("logAdminCommands", "true");
//                gameRules.putString("showDeathMessages", "true");
//                gameRules.putString("randomTickSpeed", "0");
//                gameRules.putString("sendCommandFeedback", "true");
        data.put("game_rules", gameRules);

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

        data.put("Player", player);

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
     * @param worldFolder  root directory of the mirror world
     * @param levelName    human-readable name to embed in {@code level.dat}
     */
    public static void createLoadableWorld(File worldFolder, String levelName) {
        try {
            boolean firstTime = !(new File(worldFolder, "level.dat")).exists();

            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }

            (new File(worldFolder, "region")).mkdirs();
            (new File(worldFolder, "playerdata")).mkdirs();
            (new File(worldFolder, "advancements")).mkdirs();
            (new File(worldFolder, "stats")).mkdirs();
            (new File(worldFolder, "data")).mkdirs();
            (new File(worldFolder, "poi")).mkdirs();
            (new File(worldFolder, "entities")).mkdirs();


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
                NbtCompound root = new NbtCompound();
                NbtCompound data = createWorldData(levelName);
                root.put("Data", data);

                File levelDat = new File(worldFolder, "level.dat");
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
                WMLogger.info("World structure created at: " + worldFolder.getAbsolutePath()
                        + " (name: " + data.getString("LevelName") + ")");
            } else {
                WMLogger.info("World structure updated (incremental sync): " + worldFolder.getAbsolutePath());
            }
        } catch (Exception e) {
            WMLogger.warn("Failed to create loadable world: " + e.getMessage());
        }
    }
}
