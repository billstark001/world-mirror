package net.billstark001.worlddownloader.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Random;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtElement;

@Environment(EnvType.CLIENT)
public class WorldFileWriter {
    public static void writeLevelDat(Path worldFolder) {
        try {
            NbtCompound data = new NbtCompound();
            NbtCompound dataWrapper = new NbtCompound();

            data.putString("LevelName", "Downloaded World");
            data.putLong("RandomSeed", (new Random()).nextLong());
            data.putInt("GameType", 1);
            data.putBoolean("MapFeatures", true);
            data.putLong("Time", 1L);
            data.putLong("DayTime", 1L);
            data.putInt("SpawnX", 0);
            data.putInt("SpawnY", 80);
            data.putInt("SpawnZ", 0);
            data.putString("generatorName", "minecraft:overworld");


            NbtCompound player = new NbtCompound();
            player.putString("Dimension", "minecraft:overworld");
            NbtList posList = new NbtList();
            posList.add(NbtDouble.of(0.0D));
            posList.add(NbtDouble.of(80.0D));
            posList.add(NbtDouble.of(0.0D));
            player.put("Pos", posList);


            NbtList rotationList = new NbtList();
            rotationList.add(NbtDouble.of(0.0D));
            rotationList.add(NbtDouble.of(0.0D));
            player.put("Rotation", rotationList);
            data.put("Player", player);

            dataWrapper.put("Data", data);

            File levelDat = worldFolder.resolve("level.dat").toFile();
            OutputStream out = new FileOutputStream(levelDat);
            try {
                NbtIo.writeCompressed(dataWrapper, out);
                out.close();
            } catch (Throwable throwable) {
                try {
                    out.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
                throw throwable;
            }
            WDLogger.info("level.dat created at " + levelDat.getAbsolutePath());
        } catch (Exception e) {
            WDLogger.warn("Failed to create level.dat: " + e.getMessage());
        }
    }
}
