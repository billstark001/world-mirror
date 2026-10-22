package io.github.billstark001.worldmirror.core;

import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.function.Predicate;

/** Tracks client-known entity snapshots independently from the terrain cache. */
@Environment(EnvType.CLIENT)
public final class EntityTracker {
    private static final int COMPLETE_OBSERVATION_RADIUS_CHUNKS = 2;
    private static final long LOAD_GRACE_MS = 750L;

    public enum Observation {
        PARTIAL,
        COMPLETE
    }

    /** A top-level entity and every UUID owned by its recursively saved passenger tree. */
    public record EntityRecord(UUID rootUuid, Set<UUID> containedUuids, CompoundTag nbt) {
        public EntityRecord {
            containedUuids = Set.copyOf(containedUuids);
            nbt = nbt.copy();
        }

        /** The value is immutable, so sharing it preserves snapshot isolation without copying NBT again. */
        public EntityRecord copy() {
            return this;
        }

        @Override
        public CompoundTag nbt() {
            return nbt.copy();
        }
    }

    /**
     * Immutable, versioned intent for one entity chunk. Tombstones remove only
     * this chunk's copies; upserts replace matching UUIDs across the dimension.
     */
    public record ChunkUpdate(
            long revision,
            Observation observation,
            Map<UUID, EntityRecord> upserts,
            Set<UUID> tombstones) {
        public ChunkUpdate {
            upserts = Map.copyOf(upserts);
            tombstones = Set.copyOf(tombstones);
        }

    }

    private static final Map<ResourceKey<Level>, EntitySnapshotStore> dimensions = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<ChunkPos, Long>> loadedSinceByDimension = new HashMap<>();

    private EntityTracker() { }

    public static synchronized void onChunkLoaded(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;
        loadedSinceByDimension.computeIfAbsent(world.dimension(), ignored -> new HashMap<>())
                .put(pos, System.currentTimeMillis());
    }

    /** Ends the observation epoch without deleting saved or pending entity data. */
    public static synchronized void onChunkUnloaded(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;
        Map<ChunkPos, Long> loaded = loadedSinceByDimension.get(world.dimension());
        if (loaded != null) loaded.remove(pos);
    }

    /** Captures the complete client entity store on the game thread. */
    public static synchronized void captureEntitiesForWorld(ClientLevel world) {
        if (world == null) {
            WMLogger.debug("Entity capture skipped because the client world is unavailable");
            return;
        }

        ResourceKey<Level> dimension = world.dimension();
        EntitySnapshotStore store = dimensions.computeIfAbsent(dimension, ignored -> new EntitySnapshotStore());
        Map<ChunkPos, Map<UUID, EntityRecord>> observed = observeEntities(world, ignored -> true);
        int total = observed.values().stream().mapToInt(Map::size).sum();

        long now = System.currentTimeMillis();
        Set<ChunkPos> completeChunks = collectCompletelyObservedChunks(world, now);
        store.capture(observed, completeChunks);
        WMLogger.debug("Captured " + total + " root entities and " + completeChunks.size()
                + " complete entity chunk(s) for [" + dimension.identifier() + "]");
    }

    /**
     * Captures visible roots in selected chunks for a fresh nearby save. Call on
     * the game thread. This snapshot does not change or acknowledge live session
     * state, and includes entities even when their session copy is already durable.
     */
    public static Map<ChunkPos, ChunkUpdate> snapshotVisibleEntities(ClientLevel world, Set<ChunkPos> chunks) {
        if (world == null || chunks.isEmpty()) return Map.of();
        EntitySnapshotStore snapshot = new EntitySnapshotStore();
        snapshot.capture(observeEntities(world, chunks::contains), Set.of());
        return snapshot.snapshot();
    }

    private static Map<ChunkPos, Map<UUID, EntityRecord>> observeEntities(
            ClientLevel world, Predicate<ChunkPos> selected) {
        Map<ChunkPos, Map<UUID, EntityRecord>> observed = new HashMap<>();
        for (Entity entity : world.entitiesForRendering()) {
            if (entity == null || entity instanceof Player || entity.isPassenger()) continue;
            ChunkPos chunk = entity.chunkPosition();
            if (!selected.test(chunk)) continue;
            EntityRecord record = serializeEntity(world, entity);
            if (record != null) observed.computeIfAbsent(chunk, ignored -> new LinkedHashMap<>())
                    .put(record.rootUuid(), record);
        }
        return observed;
    }

    public static synchronized Map<ResourceKey<Level>, Map<ChunkPos, ChunkUpdate>> snapshot() {
        Map<ResourceKey<Level>, Map<ChunkPos, ChunkUpdate>> result = new HashMap<>();
        dimensions.forEach((dimension, store) -> {
            Map<ChunkPos, ChunkUpdate> updates = store.snapshot();
            if (!updates.isEmpty()) result.put(dimension, updates);
        });
        return Map.copyOf(result);
    }

    public static synchronized void acknowledge(
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> writtenRevisions) {
        writtenRevisions.forEach((dimension, revisions) -> {
            EntitySnapshotStore store = dimensions.get(dimension);
            if (store != null) store.acknowledge(revisions);
        });
    }

    public static synchronized boolean hasDirtyUpdates() {
        return dimensions.values().stream().anyMatch(EntitySnapshotStore::hasPending);
    }

    /** Forces already-loaded chunks to satisfy the load grace period again. */
    public static synchronized void resetObservationEpochs() {
        loadedSinceByDimension.clear();
    }

    public static synchronized void clear() {
        int total = getTotalTrackedEntities();
        dimensions.clear();
        loadedSinceByDimension.clear();
        WMLogger.debug("Cleared " + total + " tracked entities");
    }

    public static synchronized int getTotalTrackedEntities() {
        return dimensions.values().stream().mapToInt(EntitySnapshotStore::knownEntityCount).sum();
    }

    private static EntityRecord serializeEntity(ClientLevel world, Entity entity) {
        try {
            ProblemReporter.Collector problems = new ProblemReporter.Collector();
            TagValueOutput output = TagValueOutput.createWithContext(problems, world.registryAccess());
            if (!entity.save(output)) return null;

            CompoundTag nbt = output.buildResult();
            if (!nbt.contains("id") || !nbt.contains("UUID")) {
                WMLogger.warnRateLimited("entity-identity", 30_000L,
                        "Vanilla entity serialization omitted id or UUID type=" + entity.getType());
                return null;
            }
            if (!problems.isEmpty()) {
                WMLogger.warnRateLimited("entity-codec", 30_000L,
                        "Entity serialization reported codec problems type=" + entity.getType()
                                + " report=" + problems.getReport());
            }

            // Vanilla deliberately omits passengers that cannot be saved, such
            // as players. Index the serialized tree rather than live riders.
            return new EntityRecord(entity.getUUID(), serializedUuids(nbt), nbt);
        } catch (Exception e) {
            WMLogger.warnRateLimited("entity-serialize", 30_000L,
                    "Entity serialization failed type=" + entity.getType(), e);
            return null;
        }
    }

    /** Returns every saved UUID, rejecting missing identities and malformed or duplicate passengers. */
    public static Set<UUID> serializedUuids(CompoundTag root) {
        Set<UUID> result = new HashSet<>();
        ArrayDeque<CompoundTag> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            CompoundTag entity = pending.pop();
            if (entity.getStringOr("id", "").isBlank()) {
                throw new IllegalArgumentException("Missing id in serialized entity tree");
            }
            UUID uuid = entity.getIntArray("UUID").filter(raw -> raw.length == 4)
                    .map(UUIDUtil::uuidFromIntArray)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid UUID in serialized entity tree"));
            if (!result.add(uuid)) throw new IllegalArgumentException("Duplicate UUID in serialized entity tree");
            Tag passengerTag = entity.get("Passengers");
            if (passengerTag == null) continue;
            if (!(passengerTag instanceof ListTag passengers)) {
                throw new IllegalArgumentException("Invalid passenger list in serialized entity tree");
            }
            for (int index = 0; index < passengers.size(); index++) {
                pending.push(passengers.getCompound(index).orElseThrow(() ->
                        new IllegalArgumentException("Invalid passenger in serialized entity tree")));
            }
        }
        return Set.copyOf(result);
    }

    private static Set<ChunkPos> collectCompletelyObservedChunks(ClientLevel world, long now) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.level() != world) return Set.of();

        int centerX = minecraft.player.getBlockX() >> 4;
        int centerZ = minecraft.player.getBlockZ() >> 4;
        Map<ChunkPos, Long> loaded = loadedSinceByDimension.computeIfAbsent(
                world.dimension(), ignored -> new HashMap<>());
        Set<ChunkPos> complete = new HashSet<>();

        for (int x = centerX - COMPLETE_OBSERVATION_RADIUS_CHUNKS;
                x <= centerX + COMPLETE_OBSERVATION_RADIUS_CHUNKS; x++) {
            for (int z = centerZ - COMPLETE_OBSERVATION_RADIUS_CHUNKS;
                    z <= centerZ + COMPLETE_OBSERVATION_RADIUS_CHUNKS; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
                    loaded.remove(pos);
                    continue;
                }
                long since = loaded.computeIfAbsent(pos, ignored -> now);
                if (now - since >= LOAD_GRACE_MS) complete.add(pos);
            }
        }
        return complete;
    }

}
