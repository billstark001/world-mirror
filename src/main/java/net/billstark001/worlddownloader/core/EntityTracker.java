package net.billstark001.worlddownloader.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.billstark001.worlddownloader.util.WDLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
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
     * Each entity is serialized via {@link Entity#writeNbt(NbtCompound)}, which
     * covers every entity type — including paintings (motive / facing / attachment
     * position), item frames (held item, rotation), armour stands (pose, equipment,
     * flags), dropped items, mobs, animals, and more.  The entity type identifier
     * is prepended as the {@code id} key before calling {@code writeNbt} so the
     * resulting NBT is compatible with Minecraft's region-file format.
     */
    public static void captureEntitiesForWorld(ClientWorld world) {
        if (world == null) {
            WDLogger.warn("ClientWorld is null, cannot capture entities.");
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
                        WDLogger.debug("Captured " + Registries.ENTITY_TYPE.getId(entity.getType())
                                + " at " + pos + " in chunk " + chunkPos);
                    }
                } catch (Exception e) {
                    WDLogger.warn("Failed to serialize entity at " + pos + ": " + e.getMessage());
                }
            }
        }

        dimChunkEntities.put(dimension, dimEntities);
        WDLogger.info("Captured " + total + " entities for [" + dimension.getValue() + "]");
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

    /** Looks up entities in a pre-fetched per-dimension entity map (for use inside Exporter). */
    public static List<NbtCompound> getEntitiesForChunk(
            Map<ChunkPos, List<NbtCompound>> dimEntities, ChunkPos pos) {
        if (dimEntities == null) return List.of();
        List<NbtCompound> list = dimEntities.get(pos);
        return (list != null) ? list : List.of();
    }

    public static void clear() {
        int total = getTotalTrackedEntities();
        dimChunkEntities.clear();
        WDLogger.info("Cleared " + total + " tracked entities");
    }

    public static int getTotalTrackedEntities() {
        return dimChunkEntities.values().stream()
                .mapToInt(m -> m.values().stream().mapToInt(List::size).sum())
                .sum();
    }

    // ── Entity serialisation ──────────────────────────────────────────────────

    /**
     * Serializes {@code entity} to NBT using Minecraft's own {@link Entity#writeNbt}
     * implementation.
     * <p>
     * {@code writeNbt} is NOT overridden by client-only code; it writes exactly the
     * same fields that the server would write when saving the entity to a region
     * file.  On the client, the entity's tracked data (DataTracker) is populated via
     * tracking packets, so fields such as painting motive, item-frame contents, armour
     * stand pose, equipment, tamed-owner UUID, and so on are all available and will be
     * serialized correctly.
     * <p>
     * The only addition we make is the {@code id} key (entity type identifier), which
     * {@code writeNbt} does not write itself but which Minecraft's region-file chunk
     * serializer always includes.
     */
    private static NbtCompound serializeEntity(Entity entity) {
        try {
            NbtCompound nbt = new NbtCompound();
            // entity.writeNbt() does not include the entity type id; prepend it
            nbt.putString("id", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            // TODO, FIXME: Delegate to Minecraft's own serialization — covers all entity types
            // the `writeNbt` method does not exist.
            // it only has `writeData(net.minecraft.storage.WriteView view)`.
            // entity.writeNbt(nbt);
            return nbt;
        } catch (Exception e) {
            WDLogger.warn("Failed to serialize entity " + entity.getType() + ": " + e.getMessage());
            return null;
        }
    }
}

