package net.billstark001.worlddownloader.util;

import java.io.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ensgijs.nbt.io.BinaryNbtDeserializer;
import io.github.ensgijs.nbt.io.CompressionType;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import net.billstark001.worlddownloader.conflict.ConflictContext;
import net.billstark001.worlddownloader.conflict.ConflictResolver;
import net.billstark001.worlddownloader.conflict.OverwriteResolver;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.ChunkPos;


@Environment(EnvType.CLIENT)
public class Exporter {

    /**
     * Exports all cached chunks to the given world folder, respecting the
     * supplied conflict strategy.
     *
     * @param worldFolder Root directory of the mirror world (must already exist).
     * @param resolver    Conflict resolver to consult for chunks that already
     *                    exist on disk.
     * @return The set of chunk positions that were actually written to disk.
     */
    public static Set<ChunkPos> exportChunks(Path worldFolder,
                                             ConflictResolver resolver) throws Exception {
        Path regionDir = worldFolder.resolve("region");

        if (!regionDir.toFile().exists() && !regionDir.toFile().mkdirs()) {
            throw new IOException("Failed to create region directory: " + regionDir);
        }

        Map<ChunkPos, NbtCompound> allChunks = ChunkListener.getAll();
        if (allChunks.isEmpty()) {
            WDLogger.warn("No chunks to export.");
            return Set.of();
        }

        // ── Load / create McaRegionFile handles ──────────────────────────────
        Map<String, McaRegionFile> regionFiles = new HashMap<>();
        // Track which region files already existed before this export pass
        Set<String> preExistingRegionKeys = new HashSet<>();

        for (ChunkPos chunkPos : allChunks.keySet()) {
            int regionX = chunkPos.x >> 5;
            int regionZ = chunkPos.z >> 5;
            String key  = regionX + "," + regionZ;
            if (!regionFiles.containsKey(key)) {
                Path regionFile = regionDir.resolve(
                        String.format("r.%d.%d.mca", regionX, regionZ));
                McaRegionFile mcaFile;
                if (regionFile.toFile().exists()) {
                    try {
                        mcaFile = McaFileHelpers.readAuto(regionFile.toFile());
                        preExistingRegionKeys.add(key);
                    } catch (Exception e) {
                        WDLogger.warn("Could not read " + regionFile.getFileName()
                                + ", creating new: " + e.getMessage());
                        mcaFile = new McaRegionFile(regionX, regionZ);
                    }
                } else {
                    mcaFile = new McaRegionFile(regionX, regionZ);
                }
                regionFiles.put(key, mcaFile);
            }
        }

        // ── Process chunks ────────────────────────────────────────────────────
        Set<ChunkPos> written = new HashSet<>();

        for (Map.Entry<ChunkPos, NbtCompound> entry : allChunks.entrySet()) {
            ChunkPos chunkPos  = entry.getKey();
            NbtCompound chunkNbt = entry.getValue();

            int chunkX      = chunkPos.x;
            int chunkZ      = chunkPos.z;
            int regionX     = chunkX >> 5;
            int regionZ     = chunkZ >> 5;
            int localChunkX = chunkX & 0x1F;
            int localChunkZ = chunkZ & 0x1F;

            String key = regionX + "," + regionZ;
            McaRegionFile mcaFile = regionFiles.get(key);

            try {
                // ── Conflict check ────────────────────────────────────────────
                boolean existsLocally = preExistingRegionKeys.contains(key)
                        && mcaFile.getChunk(localChunkX, localChunkZ) != null;

                ConflictContext ctx = new ConflictContext(chunkPos, existsLocally);
                if (!resolver.shouldWriteChunk(ctx)) {
                    WDLogger.debug("Skipping chunk " + chunkPos
                            + " (conflict resolver kept local copy).");
                    continue;
                }

                // ── Merge entities ────────────────────────────────────────────
                List<NbtCompound> entities = EntityTracker.getEntitiesForChunk(chunkPos);
                if (!entities.isEmpty()) {
                    NbtList entitiesList = new NbtList();
                    entitiesList.addAll(entities);
                    chunkNbt.put("entities", entitiesList);
                    WDLogger.debug("Added " + entities.size()
                            + " entities to chunk " + chunkPos);
                }

                // ── Write ─────────────────────────────────────────────────────
                CompoundTag querzChunk = convertToQuerz(chunkNbt);
                TerrainChunk chunk = new TerrainChunk(querzChunk);
                mcaFile.setChunk(localChunkX, localChunkZ, chunk);
                written.add(chunkPos);

                WDLogger.debug("Prepared chunk " + chunkPos
                        + " (" + entities.size() + " entities)");

            } catch (Exception e) {
                WDLogger.warn("Failed to process chunk " + chunkPos + ": "
                        + e.getMessage());
            }
        }

        // ── Flush region files ────────────────────────────────────────────────
        for (Map.Entry<String, McaRegionFile> entry : regionFiles.entrySet()) {
            String regionKey = entry.getKey();
            McaRegionFile mcaFile = entry.getValue();
            String[] coords = regionKey.split(",");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            Path regionFile = regionDir.resolve(
                    String.format("r.%d.%d.mca", regionX, regionZ));
            try {
                McaFileHelpers.write(mcaFile, regionFile.toFile());
                WDLogger.debug("Wrote region file " + regionFile.getFileName());
            } catch (Exception e) {
                WDLogger.warn("Failed to write region " + regionFile.getFileName()
                        + ": " + e.getMessage());
            }
        }

        WDLogger.info("Exported " + written.size() + "/" + allChunks.size()
                + " chunks to " + regionFiles.size() + " region files.");
        return written;
    }

    // ── NBT conversion ────────────────────────────────────────────────────────

    public static CompoundTag convertToQuerz(NbtCompound mcNbt) {
        try {
            // Serialize via Minecraft's NbtIo, then deserialize with ensgijs
            ByteArrayOutputStream mcNbtStream = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(mcNbtStream);
            net.minecraft.nbt.NbtIo.write(mcNbt, dos);
            dos.close();

            ByteArrayInputStream bis = new ByteArrayInputStream(mcNbtStream.toByteArray());
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
