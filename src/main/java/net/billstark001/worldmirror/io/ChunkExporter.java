package net.billstark001.worldmirror.io;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import io.github.ensgijs.nbt.io.BinaryNbtDeserializer;
import io.github.ensgijs.nbt.io.CompressionType;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import net.billstark001.worldmirror.conflict.ConflictContext;
import net.billstark001.worldmirror.conflict.ConflictResolver;
import net.billstark001.worldmirror.core.ChunkListener;
import net.billstark001.worldmirror.core.ContainerTracker;
import net.billstark001.worldmirror.core.EntityTracker;
import net.billstark001.worldmirror.download.ChunkDatabase;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
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
     *   <li>{@code minecraft:overworld}  → {@code <worldFolder>/region/}</li>
     *   <li>{@code minecraft:the_nether} → {@code <worldFolder>/DIM-1/region/}</li>
     *   <li>{@code minecraft:the_end}    → {@code <worldFolder>/DIM1/region/}</li>
     *   <li>any other                    → {@code <worldFolder>/dimensions/<ns>/<path>/region/}</li>
     * </ul>
     * <p>
     * Only "dirty" chunks are written — i.e. chunks whose {@link ChunkListener.CapturedChunk#capturedAtMs()}
     * is newer than their last recorded write time, and whose update source is not
     * outranked by a higher-priority source in {@code db}.
     * <p>
     * Container item data captured by {@link ContainerTracker} is merged into block
     * entity NBT at export time, ensuring items are included even when the player
     * opened containers after the initial chunk capture.
     * <p>
     * This method is safe to call from a background thread.
     *
     * @param worldFolder    Root directory of the mirror world.
     * @param snapshot       Immutable snapshot produced by {@link ChunkListener#snapshot()}.
     * @param entitySnapshot Immutable snapshot produced by {@link EntityTracker#snapshot()}.
     * @param resolver       Conflict resolver for chunks that already exist on disk.
     * @param db             Chunk database for dirty-check and priority enforcement.
     * @return Map from dimension → set of chunk positions actually written.
     */
    public static Map<ResourceKey<Level>, Set<ChunkPos>> exportChunks(
            Path worldFolder,
            Map<ResourceKey<Level>, Map<ChunkPos, ChunkListener.CapturedChunk>> snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>>> entitySnapshot,
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

            Path regionDir = regionDirForDimension(worldFolder, dimension);
            Files.createDirectories(regionDir);

            Set<ChunkPos> written = exportDimensionChunks(
                    regionDir, dimChunks, dimEntities, resolver, db, dimension);
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
            Map<ChunkPos, List<net.minecraft.nbt.CompoundTag>> dimEntities,
            ConflictResolver resolver,
            ChunkDatabase db,
            ResourceKey<Level> dimension) {

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

            // ── Dirty / priority check ────────────────────────────────────────
            String dimStr = dimension.identifier().toString();
            if (db.shouldSkipUpdate(dimStr, chunkPos.x, chunkPos.z,
                    "world_mirror", captured.capturedAtMs())) {
                WMLogger.debug("Skipping chunk [" + dimension.identifier()
                        + "] " + chunkPos + " (not dirty or higher-priority source)");
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
                    WMLogger.debug("Conflict resolver kept local chunk "
                            + chunkPos + " [" + dimension.identifier() + "]");
                    continue;
                }

                // ── Work on a copy so we don't mutate the live cache ──────────
                net.minecraft.nbt.CompoundTag chunkNbt = captured.nbt().copy();

                // ── Merge latest container data (items not present at capture) ─
                // Container items are captured lazily when the player opens a
                // container; applying them here ensures the export always uses
                // the most up-to-date inventory state regardless of when the
                // chunk was first serialised.
                mergeContainerData(dimension, chunkNbt);

                // ── Merge entities ────────────────────────────────────────────
                List<net.minecraft.nbt.CompoundTag> entities = EntityTracker.getEntitiesForChunk(dimEntities, chunkPos);
                if (!entities.isEmpty()) {
                    ListTag entitiesList = new ListTag();
                    entitiesList.addAll(entities);
                    chunkNbt.put("entities", entitiesList);
                    WMLogger.debug("Added " + entities.size() + " entities to " + chunkPos);
                }

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

    // ── Container data merging (bug fix) ─────────────────────────────────────

    /**
     * Merges container inventory data captured by {@link ContainerTracker} into the
     * {@code block_entities} list of a chunk NBT compound.
     *
     * <p>Container items are not available on the client at chunk-load time; they are
     * only captured when the player opens a container.  Applying them here at export
     * time ensures that any container opened during the session is saved with its
     * correct inventory, regardless of when the chunk was first serialised.
     */
    private static void mergeContainerData(ResourceKey<Level> dimension, net.minecraft.nbt.CompoundTag chunkNbt) {
        Optional<ListTag> _blockEntities = chunkNbt.getList("block_entities");
        if (_blockEntities.isEmpty()) {
            return;
        }
        ListTag blockEntities = _blockEntities.get();
        for (int i = 0; i < blockEntities.size(); i++) {
            Optional<net.minecraft.nbt.CompoundTag> _beNbt = blockEntities.getCompound(i);
            if (_beNbt.isEmpty()) {
                continue;
            }
            net.minecraft.nbt.CompoundTag beNbt = _beNbt.get();
            BlockPos bePos    = new BlockPos(
                    beNbt.getInt("x").orElse(0),
                    beNbt.getInt("y").orElse(0),
                    beNbt.getInt("z").orElse(0)
            );
            net.minecraft.nbt.CompoundTag containerData = ContainerTracker.getContainerData(dimension, bePos);
            if (containerData == null) continue;

            if (containerData.contains("Items")) {
                beNbt.put("Items", Objects.requireNonNull(containerData.get("Items")).copy());
            }
            if (containerData.contains("CustomName")) {
                beNbt.put("CustomName", Objects.requireNonNull(containerData.get("CustomName")).copy());
            }
        }
    }

    // ── Dimension → directory mapping ────────────────────────────────────────

    private static final Identifier OVERWORLD_ID = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier NETHER_ID    = Identifier.fromNamespaceAndPath("minecraft", "the_nether");
    private static final Identifier END_ID       = Identifier.fromNamespaceAndPath("minecraft", "the_end");

    public static Path regionDirForDimension(Path worldFolder, ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
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
            throw new RuntimeException("Failed to convert Minecraft CompoundTag to Querz CompoundTag", e);
        }
    }
}
