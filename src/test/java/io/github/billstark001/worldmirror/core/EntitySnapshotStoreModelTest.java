package io.github.billstark001.worldmirror.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EntitySnapshotStoreModelTest {
    private record EntityValue(ChunkPos chunk, int version) { }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @Timeout(15)
    void randomMovesDespawnsPartialObservationsAndDelayedReceiptsConvergeToTheWorld() {
        List<ChunkPos> chunks = List.of(new ChunkPos(-1, 0), new ChunkPos(0, 0),
                new ChunkPos(31, 0), new ChunkPos(32, 0), new ChunkPos(0, -1), new ChunkPos(0, 32));
        Random random = new Random(20261023L);
        EntitySnapshotStore store = new EntitySnapshotStore();
        Map<UUID, EntityValue> world = new HashMap<>();
        Map<UUID, EntityValue> disk = new HashMap<>();
        ArrayDeque<Map<ChunkPos, EntityTracker.ChunkUpdate>> writes = new ArrayDeque<>();
        for (int step = 1; step <= 3_000; step++) {
            UUID uuid = new UUID(0L, random.nextInt(50));
            if (random.nextInt(5) == 0) {
                world.remove(uuid);
            } else {
                world.put(uuid, new EntityValue(chunks.get(random.nextInt(chunks.size())), step));
            }
            Set<ChunkPos> complete = new HashSet<>();
            chunks.stream().filter(chunk -> random.nextInt(3) == 0).forEach(complete::add);
            store.capture(observe(world, complete, random), complete);
            if (random.nextInt(3) == 0 && store.hasPending()) writes.addLast(store.snapshot());
            if (random.nextBoolean() && !writes.isEmpty()) applyAndAcknowledge(store, disk, writes.removeFirst());
            if (step % 25 == 0) {
                while (!writes.isEmpty()) applyAndAcknowledge(store, disk, writes.removeFirst());
                Set<ChunkPos> all = Set.copyOf(chunks);
                store.capture(observe(world, all, random), all);
                applyAndAcknowledge(store, disk, store.snapshot());
                assertEquals(world, disk, "complete synchronization after random step " + step);
            }
        }
        while (!writes.isEmpty()) applyAndAcknowledge(store, disk, writes.removeFirst());
        Set<ChunkPos> all = Set.copyOf(chunks);
        store.capture(observe(world, all, random), all);
        applyAndAcknowledge(store, disk, store.snapshot());

        assertEquals(world, disk, "the independent disk model must contain exactly the final world");
        assertEquals(world.size(), store.knownEntityCount());
        store.capture(observe(world, all, random), all);
        assertFalse(store.hasPending(), "unchanged complete observations must not rewrite acknowledged entities");
    }

    private static Map<ChunkPos, Map<UUID, EntityTracker.EntityRecord>> observe(
            Map<UUID, EntityValue> world, Set<ChunkPos> complete, Random random) {
        Map<ChunkPos, Map<UUID, EntityTracker.EntityRecord>> observed = new HashMap<>();
        world.forEach((uuid, value) -> {
            if (!complete.contains(value.chunk()) && random.nextInt(4) != 0) return;
            CompoundTag nbt = new CompoundTag();
            nbt.putString("id", "minecraft:pig");
            nbt.putIntArray("UUID", UUIDUtil.uuidToIntArray(uuid));
            nbt.putInt("version", value.version());
            observed.computeIfAbsent(value.chunk(), ignored -> new HashMap<>())
                    .put(uuid, new EntityTracker.EntityRecord(uuid, Set.of(uuid), nbt));
        });
        return observed;
    }

    /** Independent interpretation of complete replacement, local tombstones, and global UUID upserts. */
    private static void applyAndAcknowledge(EntitySnapshotStore store, Map<UUID, EntityValue> disk,
                                             Map<ChunkPos, EntityTracker.ChunkUpdate> updates) {
        updates.forEach((chunk, update) -> {
            if (update.observation() == EntityTracker.Observation.COMPLETE) {
                disk.entrySet().removeIf(entry -> entry.getValue().chunk().equals(chunk));
            }
        });
        updates.forEach((chunk, update) -> update.tombstones().forEach(uuid ->
                disk.computeIfPresent(uuid, (ignored, value) -> value.chunk().equals(chunk) ? null : value)));
        updates.forEach((chunk, update) -> update.upserts().forEach((uuid, record) ->
                disk.put(uuid, new EntityValue(chunk, record.nbt().getIntOr("version", -1)))));
        Map<ChunkPos, Long> revisions = new HashMap<>();
        updates.forEach((chunk, update) -> revisions.put(chunk, update.revision()));
        store.acknowledge(revisions);
    }
}
