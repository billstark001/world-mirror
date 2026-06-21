package net.billstark001.worldmirror.io;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import io.github.ensgijs.nbt.io.BinaryNbtDeserializer;
import io.github.ensgijs.nbt.io.BinaryNbtSerializer;
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
import net.billstark001.worldmirror.core.ChunkListener;
import net.billstark001.worldmirror.core.EntityTracker;
import net.billstark001.worldmirror.download.ChunkDatabase;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;


@Environment(EnvType.CLIENT)
public class ChunkExporter {

    /**
     * Exports all chunks in the snapshot to the given world folder.
     * <p>
     * Each dimension is exported to the appropriate subdirectory:
     * <ul>
     *   <li>{@code minecraft:overworld}  → {@code <worldFolder>/}</li>
     *   <li>{@code minecraft:the_nether} → {@code <worldFolder>/DIM-1/}</li>
     *   <li>{@code minecraft:the_end}    → {@code <worldFolder>/DIM1/}</li>
     *   <li>any other                    → {@code <worldFolder>/dimensions/<ns>/<path>/}</li>
     * </ul>
     * <p>
     * Only "dirty" chunks are written — i.e. chunks whose {@link ChunkListener.CapturedChunk#capturedAtMs()}
     * is newer than their last recorded write time, and whose update source is not
     * outranked by a higher-priority source in {@code db}.
     * <p>
     * Previously written block-entity data is merged back before overwrite, then
     * container overlays are applied through {@link BlockEntityNbtSupport}.
     * <p>
     * This method is safe to call from a background thread.
     *
     * @param worldFolder    Root directory of the mirror world.
     * @param snapshot       Immutable snapshot produced by {@link ChunkListener#snapshot()}.
     * @param entitySnapshot Immutable snapshot produced by {@link EntityTracker#snapshot()}.
     * @param containerSnapshot Immutable snapshot produced by {@code ContainerTracker.snapshotSavedData()}.
     * @param resolver       Conflict resolver for chunks that already exist on disk.
     * @param db             Chunk database for dirty-check and priority enforcement.
     * @return Map from dimension → set of chunk positions actually written.
     */
    public static Map<ResourceKey<Level>, Set<ChunkPos>> exportChunks(
            Path worldFolder,
            Map<ResourceKey<Level>, Map<ChunkPos, ChunkListener.CapturedChunk>> snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>>> entitySnapshot,
            Map<ResourceKey<Level>, Map<BlockPos, net.minecraft.nbt.CompoundTag>> containerSnapshot,
            ConflictResolver resolver,
            ChunkDatabase db) throws Exception {

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
                    regionDir, dimChunks, dimEntities, containerSnapshot, resolver, db, dimension, worldFolder);
            exportDimensionEntities(entitiesDir, dimEntities);
            allWritten.put(dimension, written);
            dimCount++;

            WMLogger.debug("[" + dimension.identifier() + "] "
                    + written.size() + "/" + dimChunks.size() + " chunks exported.");
        }

        WMLogger.debug("Export pass complete across " + dimCount + " dimension(s).");
        return allWritten;
    }

    // ── Per-dimension export ──────────────────────────────────────────────────

    private static Set<ChunkPos> exportDimensionChunks(
            Path regionDir,
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks,
            Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>> dimEntities,
            Map<ResourceKey<Level>, Map<BlockPos, net.minecraft.nbt.CompoundTag>> containerSnapshot,
            ConflictResolver resolver,
            ChunkDatabase db,
            ResourceKey<Level> dimension,
            Path worldFolder) {

        Map<String, List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>>> chunksByRegion =
                new HashMap<>();
        for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : dimChunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            chunksByRegion.computeIfAbsent(regionKey(pos.x >> 5, pos.z >> 5),
                    ignored -> new ArrayList<>()).add(entry);
        }

        Set<ChunkPos> written = new HashSet<>();
        String dimStr = dimension.identifier().toString();

        for (Map.Entry<String, List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>>> regionEntry
                : chunksByRegion.entrySet()) {
            String regionKey = regionEntry.getKey();
            String[] coords = regionKey.split(",");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));

            synchronized (McaWriteSupport.lockFor(regionFile)) {
                McaRegionFile mcaFile;
                boolean preExisting = regionFile.toFile().exists();
                if (preExisting) {
                    try {
                        mcaFile = McaFileHelpers.readAuto(regionFile.toFile());
                    } catch (Exception e) {
                        WMLogger.warn("Could not read " + regionFile.getFileName()
                                + ", creating new: " + e.getMessage());
                        mcaFile = new McaRegionFile(regionX, regionZ);
                        preExisting = false;
                    }
                } else {
                    mcaFile = new McaRegionFile(regionX, regionZ);
                }

                Set<ChunkPos> staged = new HashSet<>();
                for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : regionEntry.getValue()) {
                    ChunkPos chunkPos = entry.getKey();
                    ChunkListener.CapturedChunk captured = entry.getValue();

                    if (db.shouldSkipUpdate(dimStr, chunkPos.x, chunkPos.z,
                            "world_mirror", captured.capturedAtMs())) {
                        WMLogger.debug("Skipping chunk [" + dimension.identifier()
                                + "] " + chunkPos + " (not dirty or higher-priority source)");
                        continue;
                    }

                    int localX = chunkPos.x & 0x1F;
                    int localZ = chunkPos.z & 0x1F;
                    try {
                        net.minecraft.nbt.CompoundTag chunkNbt = captured.nbt().copy();
                        TerrainChunk localChunk = preExisting ? mcaFile.getChunk(localX, localZ) : null;
                        boolean existsLocally = localChunk != null;
                        if (!resolver.shouldWriteChunk(new ConflictContext(
                                chunkPos, existsLocally, chunkNbt, dimension, worldFolder))) {
                            WMLogger.debug("Conflict resolver kept local chunk "
                                    + chunkPos + " [" + dimension.identifier() + "]");
                            continue;
                        }

                        if (localChunk != null) {
                            mergeLocalBlockEntities(chunkNbt, localChunk, chunkPos);
                        }
                        BlockEntityNbtSupport.applyContainerOverlays(dimension, chunkNbt, containerSnapshot);
                        CompoundTag querzChunk = convertToQuerz(chunkNbt);
                        mcaFile.setChunk(localX, localZ, new TerrainChunk(querzChunk));
                        staged.add(chunkPos);
                    } catch (Exception e) {
                        WMLogger.warn("Failed to process chunk " + chunkPos + ": " + e.getMessage());
                    }
                }

                if (staged.isEmpty()) {
                    continue;
                }

                try {
                    int chunksFlushed = McaWriteSupport.writeAtomicallyLocked(mcaFile, regionFile);
                    if (chunksFlushed > 0) {
                        written.addAll(staged);
                        WMLogger.debug("Wrote region file " + regionFile.getFileName()
                                + " with " + staged.size() + " staged chunk(s).");
                    } else {
                        WMLogger.warn("Region " + regionFile.getFileName()
                                + " wrote no chunks; keeping staged chunks cached for retry.");
                    }
                } catch (Exception e) {
                    WMLogger.warn("Failed to write region " + regionFile.getFileName()
                            + "; keeping " + staged.size() + " chunk(s) cached for retry: "
                            + e.getMessage());
                }
            }
        }

        return written;
    }

    private static void mergeLocalBlockEntities(
            net.minecraft.nbt.CompoundTag targetChunk,
            TerrainChunk localChunk,
            ChunkPos chunkPos) {
        try {
            net.minecraft.nbt.CompoundTag localNbt = convertToMinecraft(localChunk.getHandle());
            BlockEntityNbtSupport.mergeChunkBlockEntities(targetChunk, localNbt);
        } catch (Exception e) {
            WMLogger.warn("Failed to merge local block entities for " + chunkPos
                    + ": " + e.getMessage());
        }
    }

    // ── Dimension → directory mapping ────────────────────────────────────────

    public static Path dimensionDirForDimension(Path worldFolder, ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
        if (id.equals(Level.OVERWORLD.identifier())) {
            return worldFolder;
        }
        if (id.equals(Level.NETHER.identifier())) {
            return worldFolder.resolve("DIM-1");
        }
        if (id.equals(Level.END.identifier())) {
            return worldFolder.resolve("DIM1");
        }
        return worldFolder.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath());
    }

    public static Path regionDirForDimension(Path worldFolder, ResourceKey<Level> dimension) {
        return dimensionDirForDimension(worldFolder, dimension).resolve("region");
    }

    private static void exportDimensionEntities(
            Path entitiesDir,
            Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>> dimEntities) {
        if (dimEntities.isEmpty()) return;

        Map<String, McaEntitiesFile> entityFiles = new HashMap<>();

        for (Map.Entry<ChunkPos, List<net.minecraft.nbt.CompoundTag>> entry : dimEntities.entrySet()) {
            List<net.minecraft.nbt.CompoundTag> entities = entry.getValue();
            if (entities == null || entities.isEmpty()) continue;

            ChunkPos chunkPos = entry.getKey();
            int chunkX = chunkPos.x;
            int chunkZ = chunkPos.z;
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
                McaWriteSupport.writeAtomically(entry.getValue(), entityFile);
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
            throw new RuntimeException("Failed to convert CompoundTag to Querz CompoundTag", e);
        }
    }

    public static net.minecraft.nbt.CompoundTag convertToMinecraft(CompoundTag querzNbt) {
        try {
            ByteArrayOutputStream querzNbtStream = new ByteArrayOutputStream();
            BinaryNbtSerializer serializer = new BinaryNbtSerializer(CompressionType.NONE);
            serializer.toStream(new NamedTag(null, querzNbt), querzNbtStream);

            ByteArrayInputStream bis = new ByteArrayInputStream(querzNbtStream.toByteArray());
            DataInputStream dis = new DataInputStream(bis);
            net.minecraft.nbt.CompoundTag mcNbt = net.minecraft.nbt.NbtIo.read(dis);
            dis.close();
            return mcNbt;
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert Querz CompoundTag to Minecraft CompoundTag", e);
        }
    }

    private static String regionKey(int regionX, int regionZ) {
        return regionX + "," + regionZ;
    }
}
