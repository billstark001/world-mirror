package net.billstark001.worlddownloader.util;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.ensgijs.nbt.io.BinaryNbtDeserializer;
import io.github.ensgijs.nbt.io.BinaryNbtSerializer;
import io.github.ensgijs.nbt.io.CompressionType;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.ChunkPos;


@Environment(EnvType.CLIENT)
public class Exporter {
    public static void exportChunks() throws Exception {
        Path regionDir = Paths.get("downloaded_world", "region");


        if (!regionDir.toFile().exists() && !regionDir.toFile().mkdirs()) {
            throw new IOException("Failed to create region directory: " + regionDir);
        }

        Map<ChunkPos, NbtCompound> allChunks = ChunkListener.getAll();
        if (allChunks.isEmpty()) {
            System.out.println("⚠ No chunks to export!");

            return;
        }

        Map<String, McaRegionFile> regionFiles = new HashMap<>();

        for (Map.Entry<ChunkPos, NbtCompound> entry : allChunks.entrySet()) {
            ChunkPos chunkPos = entry.getKey();
            NbtCompound chunkNbt = entry.getValue();

            int chunkX = chunkPos.x;
            int chunkZ = chunkPos.z;
            int regionX = chunkX >> 5;
            int regionZ = chunkZ >> 5;
            int localChunkX = chunkX & 0x1F;
            int localChunkZ = chunkZ & 0x1F;

            String regionKey = regionX + "," + regionZ;
            Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));


            McaRegionFile mcaFile = regionFiles.get(regionKey);
            if (mcaFile == null) {
                if (regionFile.toFile().exists()) {
                    try {
                        mcaFile = McaFileHelpers.readAuto(regionFile.toFile());
                    } catch (Exception e) {
                        System.err.println("Failed to read existing region file " + regionFile + ", creating new one: " + e.getMessage());
                        mcaFile = new McaRegionFile(regionX, regionZ);
                    }
                } else {
                    mcaFile = new McaRegionFile(regionX, regionZ);
                }
                regionFiles.put(regionKey, mcaFile);
            }


            try {
                List<NbtCompound> entities = EntityTracker.getEntitiesForChunk(chunkPos);
                if (!entities.isEmpty()) {
                    NbtList entitiesList = new NbtList();
                    entitiesList.addAll(entities);

                    chunkNbt.put("entities", entitiesList);
                    System.out.println("✅ Added " + entities.size() + " entities to chunk " + chunkPos);
                }

                CompoundTag querzChunk = convertToQuerz(chunkNbt);
                TerrainChunk chunk = new TerrainChunk(querzChunk);
                mcaFile.setChunk(localChunkX, localChunkZ, chunk);

                System.out.printf("✅ Prepared chunk %d,%d with %d entities%n",
                        chunkX, chunkZ, entities.size());
            } catch (Exception e) {
                System.err.println("❌ Failed to process chunk " + chunkPos + ": " + e.getMessage());
                e.printStackTrace();
            }
        }


        for (Map.Entry<String, McaRegionFile> entry : regionFiles.entrySet()) {
            String regionKey = entry.getKey();
            McaRegionFile mcaFile = entry.getValue();
            String[] coords = regionKey.split(",");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));

            try {
                McaFileHelpers.write(mcaFile, regionFile.toFile());
                System.out.println("✅ Successfully wrote region file: " + regionFile.getFileName());
            } catch (Exception e) {
                System.err.println("❌ Failed to write region file " + regionFile + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        int totalEntities = EntityTracker.getTotalTrackedEntities();
        int totalContainers = ContainerTracker.getTotalSavedContainers();
        System.out.println("✅ Exported " + allChunks.size() + " chunks with " + totalEntities + " entities and " + totalContainers + " containers to " + regionFiles.size() + " region files.");
    }


    public static CompoundTag convertToQuerz(NbtCompound mcNbt) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BinaryNbtSerializer serializer = new BinaryNbtSerializer(CompressionType.NONE);

            // Convert Minecraft NbtCompound to ensgijs CompoundTag
            // We need to serialize from Minecraft's format and deserialize to ensgijs format
            ByteArrayOutputStream mcNbtStream = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(mcNbtStream);
            net.minecraft.nbt.NbtIo.write(mcNbt, dos);
            dos.close();
            byte[] nbtData = mcNbtStream.toByteArray();

            // Deserialize using ensgijs library
            ByteArrayInputStream bis = new ByteArrayInputStream(nbtData);
            DataInputStream dis = new DataInputStream(bis);
            BinaryNbtDeserializer deserializer = new BinaryNbtDeserializer(CompressionType.NONE);
            NamedTag namedTag = deserializer.fromStream(dis);
            dis.close();

            return (CompoundTag) namedTag.getTag();
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert NbtCompound to Querz CompoundTag", e);
        }
    }
}
