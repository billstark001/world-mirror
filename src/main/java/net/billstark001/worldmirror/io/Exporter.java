package net.billstark001.worldmirror.io;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ensgijs.nbt.io.BinaryNbtDeserializer;
import io.github.ensgijs.nbt.io.CompressionType;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.mca.EntitiesChunk;
import io.github.ensgijs.nbt.mca.McaEntitiesFile;
import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import net.billstark001.worldmirror.conflict.ConflictContext;
import net.billstark001.worldmirror.conflict.ConflictResolver;
import net.billstark001.worldmirror.download.WorldMetadata;
import net.billstark001.worldmirror.core.ChunkListener;
import net.billstark001.worldmirror.core.EntityTracker;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;


@Environment(EnvType.CLIENT)
public class Exporter {

    /**
     * Exports all chunks in the snapshot to the given world folder.
     * <p>
     * Each dimension is exported to the appropriate subdirectory:
     * <ul>
     *   <li>{@code minecraft:overworld}  → {@code <worldFolder>/dimensions/minecraft/overworld/}</li>
     *   <li>{@code minecraft:the_nether} → {@code <worldFolder>/dimensions/minecraft/the_nether/}</li>
     *   <li>{@code minecraft:the_end}    → {@code <worldFolder>/dimensions/minecraft/the_end/}</li>
     *   <li>any other                    → {@code <worldFolder>/dimensions/<ns>/<path>/}</li>
     * </ul>
     * <p>
     * Only "dirty" chunks are written — i.e. chunks whose {@link ChunkListener.CapturedChunk#capturedAtMs()}
     * is newer than their last recorded write time in {@code meta}.
     * <p>
     * This method is safe to call from a background thread.  All Minecraft
     * state has already been serialised before the snapshot was taken.
     *
     * @param worldFolder  Root directory of the mirror world.
     * @param snapshot     Immutable snapshot produced by {@link ChunkListener#snapshot()}.
     * @param entitySnapshot Immutable snapshot produced by {@link EntityTracker#snapshot()}.
     * @param resolver     Conflict resolver for chunks that already exist on disk.
     * @param meta         Metadata for dirty-check and time-stamping.
     * @return Map from dimension → set of chunk positions actually written.
     */
    public static Map<ResourceKey<Level>, Set<ChunkPos>> exportChunks(
            Path worldFolder,
            Map<ResourceKey<Level>, Map<ChunkPos, ChunkListener.CapturedChunk>> snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>>> entitySnapshot,
            ConflictResolver resolver,
            WorldMetadata meta) throws Exception {

        Map<ResourceKey<Level>, Set<ChunkPos>> allWritten = new HashMap<>();
        int dimCount = 0;

        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkListener.CapturedChunk>> dimEntry
                : snapshot.entrySet()) {
            ResourceKey<Level> dimension = dimEntry.getKey();
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks = dimEntry.getValue();
            Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>> dimEntities =
                    entitySnapshot.getOrDefault(dimension, Map.of());

            Path dimensionDir = dimensionDirForDimension(worldFolder, dimension);
            Path regionDir = dimensionDir.resolve("region");
            Path entitiesDir = dimensionDir.resolve("entities");
            Files.createDirectories(regionDir);
            Files.createDirectories(entitiesDir);

            Set<ChunkPos> written = exportDimensionChunks(
                    regionDir, dimChunks, resolver, meta, dimension);
            exportDimensionEntities(entitiesDir, dimEntities);
            allWritten.put(dimension, written);
            dimCount++;

            WMLogger.info("[" + dimension.identifier() + "] "
                    + written.size() + "/" + dimChunks.size() + " chunks exported.");
        }

        WMLogger.info("Export pass complete across " + dimCount + " dimension(s).");
        return allWritten;
    }

    // ── Per-dimension export ──────────────────────────────────────────────────

    private static Set<ChunkPos> exportDimensionChunks(
            Path regionDir,
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks,
            ConflictResolver resolver,
            WorldMetadata meta,
            ResourceKey<Level> dimension) {

        // ── Load / create region file handles ─────────────────────────────────
        Map<String, McaRegionFile> regionFiles = new HashMap<>();
        Set<String> preExistingRegionKeys = new HashSet<>();

        for (ChunkPos pos : dimChunks.keySet()) {
            int regionX = pos.x() >> 5;
            int regionZ = pos.z() >> 5;
            String key  = regionX + "," + regionZ;
            if (regionFiles.containsKey(key)) continue;

            Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
            McaRegionFile mcaFile;
            if (regionFile.toFile().exists()) {
                try {
                    mcaFile = McaFileHelpers.readAuto(regionFile.toFile());
                    preExistingRegionKeys.add(key);
                } catch (Exception e) {
                    WMLogger.warn("Could not read " + regionFile.getFileName()
                            + ", creating new: " + e.getMessage());
                    mcaFile = new McaRegionFile(regionX, regionZ);
                }
            } else {
                mcaFile = new McaRegionFile(regionX, regionZ);
            }
            regionFiles.put(key, mcaFile);
        }

        // ── Process each chunk ────────────────────────────────────────────────
        Set<ChunkPos> written = new HashSet<>();

        for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : dimChunks.entrySet()) {
            ChunkPos chunkPos  = entry.getKey();
            ChunkListener.CapturedChunk captured = entry.getValue();

            // ── Dirty check: skip if not changed since last export ────────────
            long lastWriteMs = meta.getChunkWriteTime(dimension, chunkPos);
            if (captured.capturedAtMs() <= lastWriteMs) {
                WMLogger.debug("Skipping unchanged chunk [" + dimension.identifier()
                        + "] " + chunkPos);
                continue;
            }

            int chunkX      = chunkPos.x();
            int chunkZ      = chunkPos.z();
            int regionX     = chunkX >> 5;
            int regionZ     = chunkZ >> 5;
            int localX      = chunkX & 0x1F;
            int localZ      = chunkZ & 0x1F;
            String key      = regionX + "," + regionZ;
            McaRegionFile mcaFile = regionFiles.get(key);

            try {
                // ── Conflict check ────────────────────────────────────────────
                boolean existsLocally = preExistingRegionKeys.contains(key)
                        && mcaFile.getChunk(localX, localZ) != null;
                if (!resolver.shouldWriteChunk(new ConflictContext(chunkPos, existsLocally))) {
                    WMLogger.debug("Conflict resolver kept local chunk "
                            + chunkPos + " [" + dimension.identifier() + "]");
                    continue;
                }

                // ── Work on a copy so we don't mutate the live cache ──────────
                net.minecraft.nbt.CompoundTag chunkNbt = captured.nbt().copy();

                // ── Write ─────────────────────────────────────────────────────
                CompoundTag querzChunk = convertToQuerz(chunkNbt);
                TerrainChunk chunk = new TerrainChunk(querzChunk);
                mcaFile.setChunk(localX, localZ, chunk);
                written.add(chunkPos);

            } catch (Exception e) {
                WMLogger.warn("Failed to process chunk " + chunkPos + ": " + e.getMessage());
            }
        }

        // ── Flush region files ────────────────────────────────────────────────
        for (Map.Entry<String, McaRegionFile> regionEntry : regionFiles.entrySet()) {
            String regionKey = regionEntry.getKey();
            McaRegionFile mcaFile = regionEntry.getValue();
            String[] coords = regionKey.split(",");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
            try {
                McaFileHelpers.write(mcaFile, regionFile.toFile());
                WMLogger.debug("Wrote region file " + regionFile.getFileName());
            } catch (Exception e) {
                WMLogger.warn("Failed to write region " + regionFile.getFileName()
                        + ": " + e.getMessage());
            }
        }

        return written;
    }

    // ── Dimension → directory mapping ────────────────────────────────────────

    public static Path dimensionDirForDimension(Path worldFolder, ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
        return worldFolder.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath());
    }

    public static Path regionDirForDimension(Path worldFolder, ResourceKey<Level> dimension) {
        return dimensionDirForDimension(worldFolder, dimension).resolve("region");
    }

    // ── Entity MCA export ────────────────────────────────────────────────────

    private static void exportDimensionEntities(
            Path entitiesDir,
            Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>> dimEntities) {
        if (dimEntities.isEmpty()) return;

        Map<String, McaEntitiesFile> entityFiles = new HashMap<>();

        for (Map.Entry<ChunkPos, List<net.minecraft.nbt.CompoundTag>> entry : dimEntities.entrySet()) {
            List<net.minecraft.nbt.CompoundTag> entities = entry.getValue();
            if (entities == null || entities.isEmpty()) continue;

            ChunkPos chunkPos = entry.getKey();
            int chunkX = chunkPos.x();
            int chunkZ = chunkPos.z();
            int regionX = chunkX >> 5;
            int regionZ = chunkZ >> 5;
            int localX = chunkX & 0x1F;
            int localZ = chunkZ & 0x1F;
            String key = regionX + "," + regionZ;

            McaEntitiesFile mcaFile = entityFiles.computeIfAbsent(key, ignored -> {
                Path entityFile = entitiesDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
                if (entityFile.toFile().exists()) {
                    try {
                        return McaFileHelpers.readEntities(entityFile);
                    } catch (Exception e) {
                        WMLogger.warn("Could not read " + entityFile.getFileName()
                                + ", creating new entities region: " + e.getMessage());
                    }
                }
                return new McaEntitiesFile(regionX, regionZ);
            });

            try {
                net.minecraft.nbt.CompoundTag entityChunkNbt = new net.minecraft.nbt.CompoundTag();
                entityChunkNbt.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
                entityChunkNbt.put("Position", new IntArrayTag(new int[] {chunkX, chunkZ}));
                ListTag entityList = new ListTag();
                entityList.addAll(entities);
                entityChunkNbt.put("Entities", entityList);

                EntitiesChunk entityChunk = new EntitiesChunk(convertToQuerz(entityChunkNbt));
                mcaFile.setChunk(localX, localZ, entityChunk);
                WMLogger.debug("Wrote " + entities.size() + " entities to " + chunkPos);
            } catch (Exception e) {
                WMLogger.warn("Failed to process entities for " + chunkPos + ": " + e.getMessage());
            }
        }

        for (Map.Entry<String, McaEntitiesFile> entry : entityFiles.entrySet()) {
            String[] coords = entry.getKey().split(",");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            Path entityFile = entitiesDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
            try {
                McaFileHelpers.write(entry.getValue(), entityFile.toFile());
                WMLogger.debug("Wrote entities file " + entityFile.getFileName());
            } catch (Exception e) {
                WMLogger.warn("Failed to write entities region " + entityFile.getFileName()
                        + ": " + e.getMessage());
            }
        }
    }

    // ── NBT conversion ────────────────────────────────────────────────────────

    public static CompoundTag convertToQuerz(net.minecraft.nbt.CompoundTag mcNbt) {
        try {
            ByteArrayOutputStream mcNbtStream = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(mcNbtStream);
            net.minecraft.nbt.NbtIo.writeUnnamedTagWithFallback(mcNbt, dos);
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
