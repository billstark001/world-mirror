package net.billstark001.worldmirror.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.billstark001.worldmirror.io.NbtWriteView;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;

@Environment(EnvType.CLIENT)
public class EntityTracker {

    // dimension → (chunkPos → entity list)
    private static final ConcurrentHashMap<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>>
            dimChunkEntities = new ConcurrentHashMap<>();

    /**
     * Captures all non-player entities in the given world and stores them under
     * that world's dimension key.  Must be called on the game thread.
     * <p>
     * Each entity is serialized via {@link Entity#writeData(WriteView)}}, which
     * covers every entity type — including paintings (motive / facing / attachment
     * position), item frames (held item, rotation), armour stands (pose, equipment,
     * flags), dropped items, mobs, animals, and more.  The entity type identifier
     * is prepended as the {@code id} key before calling {@code writeData} so the
     * resulting NBT is compatible with Minecraft's region-file format.
     */
    public static void captureEntitiesForWorld(ClientWorld world) {
        if (world == null) {
            WMLogger.warn("ClientWorld is null, cannot capture entities.");
            return;
        }

        RegistryKey<World> dimension = world.getRegistryKey();
        Map<ChunkPos, ChunkListener.CapturedChunk> capturedChunks =
                ChunkListener.getDimension(dimension);

        Map<ChunkPos, List<NbtCompound>> dimEntities = new ConcurrentHashMap<>();
        int total = 0;

        for (Entity entity : world.getEntities()) {
            if (entity == null || entity instanceof net.minecraft.entity.player.PlayerEntity) continue;

            Vec3d pos = entity.getEntityPos();
            int cx = (int) Math.floor(pos.x) >> 4;
            int cz = (int) Math.floor(pos.z) >> 4;
            ChunkPos chunkPos = new ChunkPos(cx, cz);

            if (capturedChunks.containsKey(chunkPos)) {
                try {
                    NbtCompound nbt = serializeEntity(entity);
                    if (nbt != null) {
                        dimEntities.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(nbt);
                        total++;
                        WMLogger.debug("Captured " + Registries.ENTITY_TYPE.getId(entity.getType())
                                + " at " + pos + " in chunk " + chunkPos);
                    }
                } catch (Exception e) {
                    WMLogger.warn("Failed to serialize entity at " + pos + ": " + e.getMessage());
                }
            }
        }

        dimChunkEntities.put(dimension, dimEntities);
        WMLogger.info("Captured " + total + " entities for [" + dimension.getValue() + "]");
    }

    /**
     * Returns an immutable snapshot of all entity data, safe to read from any thread.
     */
    public static Map<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> snapshot() {
        Map<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> result = new HashMap<>();
        for (Map.Entry<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> dimEntry
                : dimChunkEntities.entrySet()) {
            Map<ChunkPos, List<NbtCompound>> dimCopy = new HashMap<>();
            for (Map.Entry<ChunkPos, List<NbtCompound>> chunkEntry : dimEntry.getValue().entrySet()) {
                dimCopy.put(chunkEntry.getKey(), new ArrayList<>(chunkEntry.getValue()));
            }
            result.put(dimEntry.getKey(), dimCopy);
        }
        return result;
    }

    /** Looks up entities in a pre-fetched per-dimension entity map (for use inside ChunkExporter). */
    public static List<NbtCompound> getEntitiesForChunk(
            Map<ChunkPos, List<NbtCompound>> dimEntities, ChunkPos pos) {
        if (dimEntities == null) return List.of();
        List<NbtCompound> list = dimEntities.get(pos);
        return (list != null) ? list : List.of();
    }

    public static void clear() {
        int total = getTotalTrackedEntities();
        dimChunkEntities.clear();
        WMLogger.info("Cleared " + total + " tracked entities");
    }

    public static int getTotalTrackedEntities() {
        return dimChunkEntities.values().stream()
                .mapToInt(m -> m.values().stream().mapToInt(List::size).sum())
                .sum();
    }

    /**
     * Removes entity data for chunks/dimensions that no longer exist in
     * {@link ChunkListener}'s captured-chunk cache.
     */
    public static void pruneToMatchCapturedChunks() {
        int removedChunks = 0;
        int removedEntities = 0;
        List<RegistryKey<World>> emptyDims = new ArrayList<>();

        for (Map.Entry<RegistryKey<World>, Map<ChunkPos, List<NbtCompound>>> dimEntry
                : dimChunkEntities.entrySet()) {
            RegistryKey<World> dimension = dimEntry.getKey();
            Map<ChunkPos, ChunkListener.CapturedChunk> capturedChunks =
                    ChunkListener.getDimension(dimension);
            Map<ChunkPos, List<NbtCompound>> entitiesByChunk = dimEntry.getValue();

            if (capturedChunks.isEmpty()) {
                removedChunks += entitiesByChunk.size();
                removedEntities += entitiesByChunk.values().stream().mapToInt(List::size).sum();
                emptyDims.add(dimension);
                continue;
            }

            List<ChunkPos> staleChunks = new ArrayList<>();
            for (Map.Entry<ChunkPos, List<NbtCompound>> chunkEntry : entitiesByChunk.entrySet()) {
                if (!capturedChunks.containsKey(chunkEntry.getKey())) {
                    staleChunks.add(chunkEntry.getKey());
                    removedEntities += chunkEntry.getValue().size();
                }
            }
            for (ChunkPos pos : staleChunks) {
                entitiesByChunk.remove(pos);
                removedChunks++;
            }
            if (entitiesByChunk.isEmpty()) {
                emptyDims.add(dimension);
            }
        }

        for (RegistryKey<World> dimension : emptyDims) {
            dimChunkEntities.remove(dimension);
        }

        if (removedChunks > 0 || removedEntities > 0) {
            WMLogger.debug("Pruned " + removedChunks + " stale entity chunk entr"
                    + (removedChunks == 1 ? "y" : "ies") + " (" + removedEntities + " entities).");
        }
    }

    // ── Entity serialisation ──────────────────────────────────────────────────

    /**
     * Serializes {@code entity} to NBT for storage in the entities region file.
     * <p>
     * In Minecraft 1.21 the old {@code Entity.writeNbt(NbtCompound)} method was
     * replaced by {@code Entity.writeData(net.minecraft.storage.WriteView)}.
     */
    private static NbtCompound serializeEntity(Entity entity) {
        try {
            NbtWriteView view = new NbtWriteView();
            entity.writeData(view);
            return view.getCompound();
        } catch (Exception e) {
            WMLogger.warn("Failed to serialize entity " + entity.getType() + ": " + e.getMessage());
            return null;
        }
    }
}

