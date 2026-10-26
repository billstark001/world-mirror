package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read/merge/write support for authoritative and partial entity chunk updates. */
final class EntityRegionWriter {
    private EntityRegionWriter() { }

    private static final class MutableUpdate {
        EntityTracker.Observation observation;
        final Map<UUID, EntityTracker.EntityRecord> upserts = new LinkedHashMap<>();
        final Set<UUID> tombstones = new HashSet<>();

        MutableUpdate(EntityTracker.ChunkUpdate source) {
            observation = source.observation();
            upserts.putAll(source.upserts());
            tombstones.addAll(source.tombstones());
        }

        MutableUpdate() {
            observation = EntityTracker.Observation.PARTIAL;
        }
    }

    private record RegionKey(int x, int z) { }
    private record DiskIndex(Map<RegionKey, Map<UUID, Integer>> counts, Set<RegionKey> seedRegions) { }

    static Map<ResourceKey<Level>, Map<ChunkPos, Long>> write(
            Path worldFolder,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> snapshot, ExportProgress progress) {
        Map<ResourceKey<Level>, Map<ChunkPos, Long>> written = new HashMap<>();
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            snapshot.forEach((dimension, updates) -> {
                progress.start(ExportProgress.Part.ENTITIES, dimension, updates.keySet());
                try {
                    Path entitiesDir = ChunkExporter.dimensionDirForDimension(worldFolder, dimension).resolve("entities");
                    Files.createDirectories(entitiesDir);
                    if (writeDimension(entitiesDir, dimension, updates)) {
                        Map<ChunkPos, Long> revisions = new HashMap<>();
                        updates.forEach((chunk, update) -> revisions.put(chunk, update.revision()));
                        written.put(dimension, Map.copyOf(revisions));
                    }
                } catch (Exception e) {
                    WMLogger.warn("Failed to write entity updates for [" + dimension.identifier()
                            + "]; keeping revisions dirty for retry", e);
                }
            });
        } catch (IOException unavailable) {
            WMLogger.warn("Cannot lease entity output; revisions retained: " + worldFolder, unavailable);
        }
        return Map.copyOf(written);
    }

    private static boolean writeDimension(
            Path entitiesDir, ResourceKey<Level> dimension,
            Map<ChunkPos, EntityTracker.ChunkUpdate> sourceUpdates) throws IOException {
        if (sourceUpdates.isEmpty()) return true;
        Map<ChunkPos, MutableUpdate> updates = new HashMap<>();
        sourceUpdates.forEach((chunk, update) -> updates.put(chunk, new MutableUpdate(update)));
        Map<UUID, ChunkPos> destinations = indexIncomingUpserts(sourceUpdates);
        Set<UUID> affectedUuids = destinations.keySet();

        // Rebuild the relevant part of the UUID index from disk on every pass.
        // It makes a restart self-healing and also removes duplicates produced by
        // older World Mirror versions without a second source of truth.
        DiskIndex diskIndex = addOnDiskDuplicateRemovals(entitiesDir, destinations, updates);
        Map<RegionKey, Map<UUID, Integer>> initialCounts = diskIndex.counts();

        Map<RegionKey, Map<ChunkPos, MutableUpdate>> byRegion = new HashMap<>();
        updates.forEach((chunk, update) -> byRegion
                .computeIfAbsent(new RegionKey(chunk.getRegionX(), chunk.getRegionZ()), ignored -> new HashMap<>())
                .put(chunk, update));

        // Only cross-region moves need a destination seed before deleting an old
        // copy. This also covers swaps: interruption can leave a retryable duplicate,
        // never delete the only durable copy. Fresh captures and moves within one
        // atomic region avoid a redundant whole-region rewrite.
        Map<RegionKey, Map<ChunkPos, MutableUpdate>> seedsByRegion = new HashMap<>();
        if (!diskIndex.seedRegions().isEmpty()) {
            sourceUpdates.forEach((chunk, source) -> {
                RegionKey destination = new RegionKey(chunk.getRegionX(), chunk.getRegionZ());
                if (source.upserts().isEmpty() || !diskIndex.seedRegions().contains(destination)) return;
                MutableUpdate seed = new MutableUpdate();
                seed.upserts.putAll(source.upserts());
                seedsByRegion
                        .computeIfAbsent(new RegionKey(chunk.getRegionX(), chunk.getRegionZ()),
                                ignored -> new HashMap<>())
                        .put(chunk, seed);
            });
            for (Map.Entry<RegionKey, Map<ChunkPos, MutableUpdate>> seed : seedsByRegion.entrySet()) {
                if (writeRegion(entitiesDir, dimension, seed.getKey(), seed.getValue(), Set.of()) == null) return false;
            }
        }

        boolean allSucceeded = true;
        for (Map.Entry<RegionKey, Map<ChunkPos, MutableUpdate>> entry : byRegion.entrySet()) {
            Map<UUID, Integer> counts = writeRegion(entitiesDir, dimension, entry.getKey(), entry.getValue(), affectedUuids);
            if (counts == null) allSucceeded = false;
            else initialCounts.put(entry.getKey(), counts);
        }
        if (!allSucceeded) return false;
        Map<UUID, Integer> finalCounts = new HashMap<>();
        initialCounts.values().forEach(counts -> counts.forEach((uuid, count) -> finalCounts.merge(uuid, count, Integer::sum)));
        for (UUID uuid : affectedUuids) {
            if (finalCounts.getOrDefault(uuid, 0) != 1) throw new IOException("Entity UUID occurrence count is not one: " + uuid);
        }
        return true;
    }

    private static Map<UUID, ChunkPos> indexIncomingUpserts(
            Map<ChunkPos, EntityTracker.ChunkUpdate> updates) throws IOException {
        Map<UUID, ChunkPos> upserted = new HashMap<>();
        for (var chunk : updates.entrySet()) {
            EntityTracker.ChunkUpdate update = chunk.getValue();
            for (Map.Entry<UUID, EntityTracker.EntityRecord> entry : update.upserts().entrySet()) {
                EntityTracker.EntityRecord record = entry.getValue();
                if (!entry.getKey().equals(record.rootUuid())
                        || !record.containedUuids().contains(record.rootUuid())) {
                    throw new IOException("Inconsistent root UUID in incoming entity snapshot: "
                            + record.rootUuid());
                }
                net.minecraft.nbt.CompoundTag serializedNbt = record.nbt();
                Set<UUID> serializedUuids = EntityTracker.serializedUuids(serializedNbt);
                UUID serializedRoot = serializedNbt.getIntArray("UUID")
                        .map(UUIDUtil::uuidFromIntArray).orElseThrow();
                if (!serializedRoot.equals(record.rootUuid()) || !serializedUuids.equals(record.containedUuids())) {
                    throw new IOException("Entity UUID index does not match serialized NBT: "
                            + record.rootUuid());
                }
                for (UUID uuid : record.containedUuids()) {
                    if (upserted.putIfAbsent(uuid, chunk.getKey()) != null) {
                        throw new IOException("Duplicate UUID in incoming entity snapshot: " + uuid);
                    }
                }
            }
        }
        return upserted;
    }

    private static DiskIndex addOnDiskDuplicateRemovals(
            Path entitiesDir, Map<UUID, ChunkPos> destinations, Map<ChunkPos, MutableUpdate> updates) throws IOException {
        Map<RegionKey, Map<UUID, Integer>> countsByRegion = new HashMap<>();
        Set<RegionKey> seedRegions = new HashSet<>();
        if (destinations.isEmpty()) return new DiskIndex(countsByRegion, seedRegions);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(entitiesDir, "r.*.*.mca")) {
            for (Path file : files) {
                var key = AnvilReader.coordinates(file);
                Map<UUID, Integer> counts = new HashMap<>();
                synchronized (McaWriteSupport.lockFor(file)) {
                    AnvilReader.visitRegion(file, key.x(), key.z(), (pos, chunk) -> {
                        validateStoredChunk(file, pos, chunk);
                        for (Tag value : entityList(chunk)) {
                            Set<UUID> contained = collectUuids(requireCompound(value));
                            for (UUID uuid : contained) {
                                ChunkPos destination = destinations.get(uuid);
                                if (destination == null) continue;
                                counts.merge(uuid, 1, Integer::sum);
                                updates.computeIfAbsent(pos, ignored -> new MutableUpdate()).tombstones.add(uuid);
                                if (destination.getRegionX() != key.x() || destination.getRegionZ() != key.z()) {
                                    seedRegions.add(new RegionKey(destination.getRegionX(), destination.getRegionZ()));
                                }
                            }
                        }
                    });
                }
                if (!counts.isEmpty()) countsByRegion.put(new RegionKey(key.x(), key.z()), counts);
            }
        }
        return new DiskIndex(countsByRegion, seedRegions);
    }

    /** The native lease keeps untouched regions stable; rewritten regions return verified replacement counts. */
    private static Map<UUID, Integer> writeRegion(
            Path entitiesDir, ResourceKey<Level> dimension, RegionKey key,
            Map<ChunkPos, MutableUpdate> updates, Set<UUID> affected) {
        Path target = entitiesDir.resolve("r." + key.x() + "." + key.z() + ".mca");
        synchronized (McaWriteSupport.lockFor(target)) {
            try {
                Map<UUID, Integer> counts = new HashMap<>();
                RegionWriteTransaction.rewrite(target, dimension, key.x(), key.z(), updates.keySet(), (pos, previous) -> {
                    if (previous != null) validateStoredChunk(target, pos, previous);
                    MutableUpdate update = updates.get(pos);
                    CompoundTag chunk = update == null ? previous : createChunk(previous, pos, mergeChunk(previous, update));
                    if (update != null) verifyChunkUpdate(pos, chunk, update);
                    if (!affected.isEmpty()) mergeUuidCounts(counts, entityList(chunk), affected);
                    return chunk;
                });
                return counts;
            } catch (Exception failure) {
                WMLogger.warn("Entity region update failed; preserving dirty revisions file=" + target, failure);
                return null;
            }
        }
    }

    static List<CompoundTag> mergeChunk(CompoundTag existing, MutableUpdate update) {
        List<CompoundTag> result = new ArrayList<>();
        if (update.observation == EntityTracker.Observation.PARTIAL && existing != null) {
            ListTag old = entityList(existing);
            if (old != null) old.forEach(tag -> result.add(requireCompound(tag)));
        }

        Set<UUID> replaced = new HashSet<>(update.tombstones);
        update.upserts.values().forEach(record -> replaced.addAll(record.containedUuids()));
        result.removeIf(root -> pruneReplacedEntities(root, replaced));

        update.upserts.values().forEach(record ->
                result.add(record.nbt().copy()));
        return result;
    }

    /** A moved passenger is removed from its branch without deleting its unobserved vehicle. */
    private static boolean pruneReplacedEntities(CompoundTag root, Set<UUID> replaced) {
        if (replaced.contains(storedUuid(root))) return true;
        ArrayDeque<CompoundTag> parents = new ArrayDeque<>();
        parents.push(root);
        while (!parents.isEmpty()) {
            ListTag passengers = compoundList(parents.pop(), "Passengers");
            if (passengers == null) continue;
            var iterator = passengers.iterator();
            while (iterator.hasNext()) {
                CompoundTag child = requireCompound(iterator.next());
                if (replaced.contains(storedUuid(child))) iterator.remove();
                else parents.push(child);
            }
        }
        return false;
    }

    private static CompoundTag createChunk(CompoundTag previous, ChunkPos pos, List<CompoundTag> entities) {
        CompoundTag chunk = previous == null ? new CompoundTag() : previous;
        chunk.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.put("Position", new IntArrayTag(new int[] {
                pos.getRegionX() * 32 + pos.getRegionLocalX(),
                pos.getRegionZ() * 32 + pos.getRegionLocalZ()}));
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        entities.forEach(list::add);
        chunk.put("Entities", list);
        return chunk;
    }

    private static ListTag entityList(CompoundTag chunk) {
        return chunk == null ? null : compoundList(chunk, "Entities");
    }

    private static ListTag compoundList(CompoundTag parent, String key) {
        Tag value = parent.get(key);
        if (value == null) return null;
        if (!(value instanceof ListTag list)) throw new IllegalArgumentException("Invalid entity list: " + key);
        list.forEach(EntityRegionWriter::requireCompound);
        return list;
    }

    private static CompoundTag requireCompound(Tag value) {
        if (!(value instanceof CompoundTag compound)) throw new IllegalArgumentException("Invalid entity record");
        return compound;
    }

    private static UUID storedUuid(CompoundTag entity) {
        int[] raw = entity.getIntArray("UUID").orElseThrow(
                () -> new IllegalArgumentException("Missing stored UUID"));
        if (raw.length != 4) throw new IllegalArgumentException("Invalid stored UUID");
        return UUIDUtil.uuidFromIntArray(raw);
    }

    private static void validateStoredChunk(Path file, ChunkPos pos, CompoundTag chunk) throws IOException {
        int[] position = chunk.getIntArray("Position").orElseThrow(() -> new IOException("Missing entity Position: " + file));
        if (position.length != 2 || !new ChunkPos(position[0], position[1]).equals(pos) || entityList(chunk) == null) {
            throw new IOException("Missing or misplaced entity chunk: " + file + " " + pos);
        }
    }

    /**
     * Indexes stored identities without requiring a type id. Older mirrors may
     * contain UUID-bearing nodes without id; preserve these opaque records until
     * a fresh vanilla observation can replace them. Incoming records are validated
     * separately by EntityTracker.serializedUuids before any write is attempted.
     */
    static Set<UUID> collectUuids(CompoundTag entity) {
        Set<UUID> result = new HashSet<>();
        ArrayDeque<CompoundTag> pending = new ArrayDeque<>();
        pending.push(entity);
        while (!pending.isEmpty()) {
            CompoundTag current = pending.pop();
            if (!result.add(storedUuid(current))) {
                throw new IllegalArgumentException("Duplicate UUID within an entity passenger tree");
            }
            ListTag passengers = compoundList(current, "Passengers");
            if (passengers != null) passengers.forEach(value -> pending.push(requireCompound(value)));
        }
        return result;
    }

    /** Business validation runs while staging, before a recovery transaction can touch live files. */
    private static void verifyChunkUpdate(ChunkPos pos, CompoundTag chunk, MutableUpdate update) throws IOException {
        ListTag roots = entityList(chunk);
        if (roots == null) throw new IOException("Entity list missing: " + pos);
        Set<UUID> present = new HashSet<>();
        update.upserts.values().forEach(record -> present.addAll(record.containedUuids()));
        Set<UUID> absent = new HashSet<>(update.tombstones);
        absent.removeAll(present);
        Map<UUID, Integer> counts = countUuids(roots, present, absent);
        for (UUID uuid : present) if (counts.getOrDefault(uuid, 0) != 1) throw new IOException("Entity not written exactly once: " + uuid);
        for (UUID uuid : absent) if (counts.getOrDefault(uuid, 0) != 0) throw new IOException("Entity tombstone remains: " + uuid);
        if (update.observation == EntityTracker.Observation.COMPLETE && roots.size() != update.upserts.size()) {
            throw new IOException("Complete entity chunk retained unexpected roots: " + pos);
        }
    }

    private static Map<UUID, Integer> countUuids(
            ListTag roots,
            Set<UUID> first,
            Set<UUID> second) {
        Set<UUID> selected = new HashSet<>(first);
        selected.addAll(second);
        Map<UUID, Integer> counts = new HashMap<>();
        mergeUuidCounts(counts, roots, selected);
        return counts;
    }

    private static void mergeUuidCounts(
            Map<UUID, Integer> counts,
            ListTag roots,
            Set<UUID> selected) {
        for (Tag value : roots) {
            CompoundTag root = requireCompound(value);
            for (UUID uuid : collectUuids(root)) {
                if (selected.contains(uuid)) counts.merge(uuid, 1, Integer::sum);
            }
        }
    }

}
