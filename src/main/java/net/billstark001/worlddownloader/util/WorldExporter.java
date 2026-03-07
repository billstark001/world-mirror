package net.billstark001.worlddownloader.util;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;

@Environment(EnvType.CLIENT)
public class WorldExporter {
    public static void createLoadableWorld(File worldFolder) {
        try {
            if (!worldFolder.exists()) worldFolder.mkdirs();


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
            NbtCompound root = new NbtCompound();
            root.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());

            NbtCompound data = new NbtCompound();


            data.putString("LevelName", "Downloaded World");
            data.putLong("RandomSeed", (new Random()).nextLong());
            data.putInt("version", 19133);
            data.putBoolean("initialized", true);


            data.putInt("GameType", 1);
            data.putBoolean("allowCommands", true);
            data.putBoolean("hardcore", false);
            data.putInt("Difficulty", 2);
            data.putBoolean("DifficultyLocked", false);


            data.putString("generatorName", "minecraft:overworld");
            data.putInt("generatorVersion", 1);


            data.putInt("SpawnX", 0);
            data.putInt("SpawnY", 80);
            data.putInt("SpawnZ", 0);
            data.putFloat("SpawnAngle", 0.0F);


            data.putLong("Time", 1000L);
            data.putLong("DayTime", 1000L);
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
            gameRules.putString("keepInventory", "false");
            gameRules.putString("mobGriefing", "true");
            gameRules.putString("doFireTick", "true");
            gameRules.putString("doMobSpawning", "true");
            gameRules.putString("doMobLoot", "true");
            gameRules.putString("doTileDrops", "true");
            gameRules.putString("commandBlockOutput", "true");
            gameRules.putString("naturalRegeneration", "true");
            gameRules.putString("doDaylightCycle", "true");
            gameRules.putString("logAdminCommands", "true");
            gameRules.putString("showDeathMessages", "true");
            gameRules.putString("randomTickSpeed", "3");
            gameRules.putString("sendCommandFeedback", "true");
            data.put("GameRules", gameRules);


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
            WDLogger.info("World structure created at: " + worldFolder.getAbsolutePath());
        } catch (Exception e) {
            WDLogger.warn("Failed to create loadable world: " + e.getMessage());
        }
    }
}
