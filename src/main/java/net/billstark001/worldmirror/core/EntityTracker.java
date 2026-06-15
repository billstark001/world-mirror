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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

@Environment(EnvType.CLIENT)
public class EntityTracker {

    // dimension → (chunkPos → entity list)
    private static final ConcurrentHashMap<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>>
            dimChunkEntities = new ConcurrentHashMap<>();

    /**
     * Captures all non-player entities in the given world and stores them under
     * that world's dimension key.  Must be called on the game thread.
     * <p>
     * Each entity is serialized via {@link Entity#saveWithoutId(ValueOutput)}}, which
     * covers every entity type — including paintings (motive / facing / attachment
     * position), item frames (held item, rotation), armour stands (pose, equipment,
     * flags), dropped items, mobs, animals, and more.  The entity type identifier
     * is prepended as the {@code id} key before calling {@code writeData} so the
     * resulting NBT is compatible with Minecraft's region-file format.
     */
    public static void captureEntitiesForWorld(ClientLevel world) {
        if (world == null) {
            WMLogger.warn("ClientWorld is null, cannot capture entities.");
            return;
        }

        ResourceKey<Level> dimension = world.dimension();
        Map<ChunkPos, ChunkListener.CapturedChunk> capturedChunks =
                ChunkListener.getDimension(dimension);

        Map<ChunkPos, List<CompoundTag>> dimEntities = new ConcurrentHashMap<>();
        int total = 0;

        for (Entity entity : world.entitiesForRendering()) {
            if (entity == null || entity instanceof net.minecraft.world.entity.player.Player) continue;

            Vec3 pos = entity.position();
            int cx = (int) Math.floor(pos.x) >> 4;
            int cz = (int) Math.floor(pos.z) >> 4;
            ChunkPos chunkPos = new ChunkPos(cx, cz);

            if (capturedChunks.containsKey(chunkPos)) {
                try {
                    CompoundTag nbt = serializeEntity(entity);
                    if (nbt != null) {
                        dimEntities.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(nbt);
                        total++;
                        WMLogger.debug("Captured " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                                + " at " + pos + " in chunk " + chunkPos);
                    }
                } catch (Exception e) {
                    WMLogger.warn("Failed to serialize entity at " + pos + ": " + e.getMessage());
                }
            }
        }

        dimChunkEntities.put(dimension, dimEntities);
        WMLogger.info("Captured " + total + " entities for [" + dimension.identifier() + "]");
    }

    /**
     * Returns an immutable snapshot of all entity data, safe to read from any thread.
     */
    public static Map<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> snapshot() {
        Map<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> result = new HashMap<>();
        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> dimEntry
                : dimChunkEntities.entrySet()) {
            Map<ChunkPos, List<CompoundTag>> dimCopy = new HashMap<>();
            for (Map.Entry<ChunkPos, List<CompoundTag>> chunkEntry : dimEntry.getValue().entrySet()) {
                dimCopy.put(chunkEntry.getKey(), new ArrayList<>(chunkEntry.getValue()));
            }
            result.put(dimEntry.getKey(), dimCopy);
        }
        return result;
    }

    /** Looks up entities in a pre-fetched per-dimension entity map (for use inside ChunkExporter). */
    public static List<CompoundTag> getEntitiesForChunk(
            Map<ChunkPos, List<CompoundTag>> dimEntities, ChunkPos pos) {
        if (dimEntities == null) return List.of();
        List<CompoundTag> list = dimEntities.get(pos);
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
        List<ResourceKey<Level>> emptyDims = new ArrayList<>();

        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> dimEntry
                : dimChunkEntities.entrySet()) {
            ResourceKey<Level> dimension = dimEntry.getKey();
            Map<ChunkPos, ChunkListener.CapturedChunk> capturedChunks =
                    ChunkListener.getDimension(dimension);
            Map<ChunkPos, List<CompoundTag>> entitiesByChunk = dimEntry.getValue();

            if (capturedChunks.isEmpty()) {
                removedChunks += entitiesByChunk.size();
                removedEntities += entitiesByChunk.values().stream().mapToInt(List::size).sum();
                emptyDims.add(dimension);
                continue;
            }

            List<ChunkPos> staleChunks = new ArrayList<>();
            for (Map.Entry<ChunkPos, List<CompoundTag>> chunkEntry : entitiesByChunk.entrySet()) {
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

        for (ResourceKey<Level> dimension : emptyDims) {
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
     * In Minecraft 1.21 the old {@code Entity.writeNbt(CompoundTag)} method was
     * replaced by {@code Entity.writeData(net.minecraft.world.level.storage.ValueOutput)}.
     */
    private static CompoundTag serializeEntity(Entity entity) {
        try {
            NbtWriteView view = new NbtWriteView();
            entity.saveWithoutId(view);
            return view.getCompound();
        } catch (Exception e) {
            WMLogger.warn("Failed to serialize entity " + entity.getType() + ": " + e.getMessage());
            return null;
        }
    }
}

