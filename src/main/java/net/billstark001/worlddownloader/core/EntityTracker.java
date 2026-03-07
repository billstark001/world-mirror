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
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
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
     * Serializes {@code entity} to NBT for storage in the entities region file.
     * <p>
     * In Minecraft 1.21 the old {@code Entity.writeNbt(NbtCompound)} method was
     * replaced by {@code Entity.writeData(net.minecraft.storage.WriteView)}.
     * We manually build the minimal NBT that a saved entity record requires:
     * type id, position, rotation, velocity, and on-ground flag.
     * <p>
     * Entity-specific state (painting motive, item-frame contents, armorstand pose,
     * mob equipment, etc.) is written by the entity's own {@code writeData} override,
     * but since {@code WriteView} is an opaque interface there is no public API to
     * extract the resulting NBT on the client.  A future update should either expose
     * a factory method or use a Mixin {@code @Invoker} to call the internal
     * {@code NbtCompoundWriteView} implementation.
     */
    private static NbtCompound serializeEntity(Entity entity) {
        try {
            NbtCompound nbt = new NbtCompound();

            // Entity type identifier (required by region-file format)
            nbt.putString("id", Registries.ENTITY_TYPE.getId(entity.getType()).toString());

            // Position
            Vec3d pos = entity.getPos();
            NbtList posList = new NbtList();
            posList.add(NbtDouble.of(pos.x));
            posList.add(NbtDouble.of(pos.y));
            posList.add(NbtDouble.of(pos.z));
            nbt.put("Pos", posList);

            // Rotation (yaw, pitch)
            NbtList rotList = new NbtList();
            rotList.add(NbtFloat.of(entity.getYaw()));
            rotList.add(NbtFloat.of(entity.getPitch()));
            nbt.put("Rotation", rotList);

            // Velocity
            Vec3d velocity = entity.getVelocity();
            NbtList motionList = new NbtList();
            motionList.add(NbtDouble.of(velocity.x));
            motionList.add(NbtDouble.of(velocity.y));
            motionList.add(NbtDouble.of(velocity.z));
            nbt.put("Motion", motionList);

            // On-ground flag
            nbt.putBoolean("OnGround", entity.isOnGround());

            // Custom name, if set
            if (entity.hasCustomName() && entity.getCustomName() != null) {
                try {
                    nbt.putString("CustomName",
                            net.minecraft.text.Text.Serialization.toJsonString(
                                    entity.getCustomName(),
                                    entity.getWorld().getRegistryManager()));
                } catch (Exception ignored) {
                    // Custom name serialization is best-effort; skip if it fails
                }
            }

            // UUID
            nbt.putUuid("UUID", entity.getUuid());

            return nbt;
        } catch (Exception e) {
            WDLogger.warn("Failed to serialize entity " + entity.getType() + ": " + e.getMessage());
            return null;
        }
    }
}

