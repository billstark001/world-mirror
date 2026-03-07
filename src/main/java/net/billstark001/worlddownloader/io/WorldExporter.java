package net.billstark001.worlddownloader.io;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import net.billstark001.worlddownloader.util.WDLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;

@Environment(EnvType.CLIENT)
public class WorldExporter {

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
            // Only write level.dat on first creation; on subsequent syncs just refresh session.lock.
            if (firstTime) {
                NbtCompound root = new NbtCompound();
                root.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());

                NbtCompound data = new NbtCompound();

                data.putString("LevelName", (levelName != null && !levelName.isEmpty())
                        ? levelName : "Downloaded World");
                data.putLong("RandomSeed", 0L); // seed is irrelevant for void superflat; 0 keeps it deterministic
                data.putInt("version", 19133);
                data.putBoolean("initialized", true);

                data.putInt("GameType", 1);
                data.putBoolean("allowCommands", true);
                data.putBoolean("hardcore", false);
                data.putInt("Difficulty", 0);
                data.putBoolean("DifficultyLocked", true);

                // Void superflat — prevents the game from generating any terrain for
                // chunks that have not been downloaded.  The generator settings string
                // encodes a superflat world with a single air layer and no structures.
                data.putString("generatorName", "flat");
                data.putInt("generatorVersion", 0);
                NbtCompound generatorOptions = new NbtCompound();
                NbtCompound flatSettings = new NbtCompound();
                flatSettings.putString("biome", "minecraft:the_void");
                flatSettings.putByte("features", (byte) 0);
                flatSettings.putByte("lakes", (byte) 0);
                flatSettings.put("layers", new NbtList());  // zero layers → void
                NbtCompound structures = new NbtCompound();
                structures.put("structures", new NbtCompound());
                flatSettings.put("structures", structures);
                generatorOptions.put("settings", flatSettings);
                data.put("generatorOptions", generatorOptions);

                data.putInt("SpawnX", 0);
                data.putInt("SpawnY", 80);
                data.putInt("SpawnZ", 0);
                data.putFloat("SpawnAngle", 0.0F);

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
                gameRules.putString("keepInventory", "false");
                gameRules.putString("mobGriefing", "false");
                gameRules.putString("doFireTick", "false");
                gameRules.putString("doMobSpawning", "false");
                gameRules.putString("doMobLoot", "true");
                gameRules.putString("doTileDrops", "true");
                gameRules.putString("commandBlockOutput", "true");
                gameRules.putString("naturalRegeneration", "true");
                gameRules.putString("doDaylightCycle", "false");
                gameRules.putString("logAdminCommands", "true");
                gameRules.putString("showDeathMessages", "true");
                gameRules.putString("randomTickSpeed", "0");
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
                WDLogger.info("World structure created at: " + worldFolder.getAbsolutePath()
                        + " (name: " + data.getString("LevelName") + ")");
            } else {
                WDLogger.info("World structure updated (incremental sync): " + worldFolder.getAbsolutePath());
            }
        } catch (Exception e) {
            WDLogger.warn("Failed to create loadable world: " + e.getMessage());
        }
    }
}
