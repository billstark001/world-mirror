package net.billstark001.worlddownloader.io;

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
import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import net.billstark001.worlddownloader.conflict.ConflictContext;
import net.billstark001.worlddownloader.conflict.ConflictResolver;
import net.billstark001.worlddownloader.download.WorldMetadata;
import net.billstark001.worlddownloader.core.ChunkListener;
import net.billstark001.worlddownloader.core.EntityTracker;
import net.billstark001.worlddownloader.util.WDLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;


@Environment(EnvType.CLIENT)
public class Exporter {

    /**
     * Exports all chunks in the snapshot to the given world folder.
     * <p>
     * Each dimension is exported to the appropriate subdirectory:
     * <ul>
     *   <li>{@code minecraft:overworld}  → {@code <worldFolder>/region/}</li>
     *   <li>{@code minecraft:the_nether} → {@code <worldFolder>/DIM-1/region/}</li>
     *   <li>{@code minecraft:the_end}    → {@code <worldFolder>/DIM1/region/}</li>
     *   <li>any other                    → {@code <worldFolder>/dimensions/<ns>/<path>/region/}</li>
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
    public static Map<RegistryKey<World>, Set<ChunkPos>> exportChunks(
            Path worldFolder,
            Map<RegistryKey<World>, Map<ChunkPos, ChunkListener.CapturedChunk>> snapshot,
            Map<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> entitySnapshot,
            ConflictResolver resolver,
            WorldMetadata meta) throws Exception {

        Map<RegistryKey<World>, Set<ChunkPos>> allWritten = new HashMap<>();
        int dimCount = 0;

        for (Map.Entry<RegistryKey<World>, Map<ChunkPos, ChunkListener.CapturedChunk>> dimEntry
                : snapshot.entrySet()) {
            RegistryKey<World> dimension = dimEntry.getKey();
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks = dimEntry.getValue();
            Map<ChunkPos, List<NbtCompound>> dimEntities =
                    entitySnapshot.getOrDefault(dimension, Map.of());

            Path regionDir = regionDirForDimension(worldFolder, dimension);
            Files.createDirectories(regionDir);

            Set<ChunkPos> written = exportDimensionChunks(
                    regionDir, dimChunks, dimEntities, resolver, meta, dimension);
            allWritten.put(dimension, written);
            dimCount++;

            WDLogger.info("[" + dimension.getValue() + "] "
                    + written.size() + "/" + dimChunks.size() + " chunks exported.");
        }

        WDLogger.info("Export pass complete across " + dimCount + " dimension(s).");
        return allWritten;
    }

    // ── Per-dimension export ──────────────────────────────────────────────────

    private static Set<ChunkPos> exportDimensionChunks(
            Path regionDir,
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks,
            Map<ChunkPos, List<NbtCompound>> dimEntities,
            ConflictResolver resolver,
            WorldMetadata meta,
            RegistryKey<World> dimension) {

        // ── Load / create region file handles ─────────────────────────────────
        Map<String, McaRegionFile> regionFiles = new HashMap<>();
        Set<String> preExistingRegionKeys = new HashSet<>();

        for (ChunkPos pos : dimChunks.keySet()) {
            int regionX = pos.x >> 5;
            int regionZ = pos.z >> 5;
            String key  = regionX + "," + regionZ;
            if (regionFiles.containsKey(key)) continue;

            Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
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

        // ── Process each chunk ────────────────────────────────────────────────
        Set<ChunkPos> written = new HashSet<>();

        for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : dimChunks.entrySet()) {
            ChunkPos chunkPos  = entry.getKey();
            ChunkListener.CapturedChunk captured = entry.getValue();

            // ── Dirty check: skip if not changed since last export ────────────
            long lastWriteMs = meta.getChunkWriteTime(dimension, chunkPos);
            if (captured.capturedAtMs() <= lastWriteMs) {
                WDLogger.debug("Skipping unchanged chunk [" + dimension.getValue()
                        + "] " + chunkPos);
                continue;
            }

            int chunkX      = chunkPos.x;
            int chunkZ      = chunkPos.z;
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
                    WDLogger.debug("Conflict resolver kept local chunk "
                            + chunkPos + " [" + dimension.getValue() + "]");
                    continue;
                }

                // ── Work on a copy so we don't mutate the live cache ──────────
                NbtCompound chunkNbt = captured.nbt().copy();

                // ── Merge entities ────────────────────────────────────────────
                List<NbtCompound> entities = EntityTracker.getEntitiesForChunk(dimEntities, chunkPos);
                if (!entities.isEmpty()) {
                    NbtList entitiesList = new NbtList();
                    entitiesList.addAll(entities);
                    chunkNbt.put("entities", entitiesList);
                    WDLogger.debug("Added " + entities.size() + " entities to " + chunkPos);
                }

                // ── Write ─────────────────────────────────────────────────────
                CompoundTag querzChunk = convertToQuerz(chunkNbt);
                TerrainChunk chunk = new TerrainChunk(querzChunk);
                mcaFile.setChunk(localX, localZ, chunk);
                written.add(chunkPos);

            } catch (Exception e) {
                WDLogger.warn("Failed to process chunk " + chunkPos + ": " + e.getMessage());
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
                WDLogger.debug("Wrote region file " + regionFile.getFileName());
            } catch (Exception e) {
                WDLogger.warn("Failed to write region " + regionFile.getFileName()
                        + ": " + e.getMessage());
            }
        }

        return written;
    }

    // ── Dimension → directory mapping ────────────────────────────────────────

    private static final Identifier OVERWORLD_ID = Identifier.of("minecraft", "overworld");
    private static final Identifier NETHER_ID    = Identifier.of("minecraft", "the_nether");
    private static final Identifier END_ID       = Identifier.of("minecraft", "the_end");

    public static Path regionDirForDimension(Path worldFolder, RegistryKey<World> dimension) {
        Identifier id = dimension.getValue();
        if (id.equals(OVERWORLD_ID)) {
            return worldFolder.resolve("region");
        } else if (id.equals(NETHER_ID)) {
            return worldFolder.resolve("DIM-1").resolve("region");
        } else if (id.equals(END_ID)) {
            return worldFolder.resolve("DIM1").resolve("region");
        } else {
            return worldFolder.resolve("dimensions")
                    .resolve(id.getNamespace())
                    .resolve(id.getPath())
                    .resolve("region");
        }
    }

    // ── NBT conversion ────────────────────────────────────────────────────────

    public static CompoundTag convertToQuerz(NbtCompound mcNbt) {
        try {
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
