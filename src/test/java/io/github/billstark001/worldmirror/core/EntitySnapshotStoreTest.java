package io.github.billstark001.worldmirror.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySnapshotStoreTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void completeEmptyIsRetainedAsAnAuthoritativeRevision() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        ChunkPos chunk = new ChunkPos(3, -2);

        store.capture(Map.of(), Set.of(chunk));

        EntityTracker.ChunkUpdate update = store.snapshot().get(chunk);
        assertEquals(EntityTracker.Observation.COMPLETE, update.observation());
        assertTrue(update.upserts().isEmpty());
        assertTrue(update.tombstones().isEmpty());
    }

    @Test
    void revertingToPersistedEmptyStateRetainsACorrectionForAnOlderInFlightSnapshot() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        ChunkPos chunk = new ChunkPos(0, 0);
        store.capture(Map.of(), Set.of(chunk));
        store.acknowledge(Map.of(chunk, store.snapshot().get(chunk).revision()));
        UUID uuid = new UUID(50L, 1L);
        store.capture(observed(chunk, record(uuid)), Set.of(chunk));
        long inFlight = store.snapshot().get(chunk).revision();

        store.capture(Map.of(), Set.of(chunk));

        assertTrue(store.hasPending(), "a previous snapshot may still write the now-despawned entity");
        var correction = store.snapshot().get(chunk);
        assertEquals(EntityTracker.Observation.COMPLETE, correction.observation());
        assertTrue(correction.upserts().isEmpty());
        assertTrue(correction.revision() > inFlight);
        store.acknowledge(Map.of(chunk, inFlight));
        assertTrue(store.hasPending());
        store.acknowledge(Map.of(chunk, correction.revision()));
        store.capture(Map.of(), Set.of(chunk));
        assertFalse(store.hasPending());
    }

    @Test
    void partialAbsencePreservesKnownEntityButPositiveMoveClearsOldChunk() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        ChunkPos a = new ChunkPos(1, 1);
        ChunkPos b = new ChunkPos(2, 1);
        UUID uuid = UUID.randomUUID();

        store.capture(observed(a, record(uuid)), Set.of());
        Map<ChunkPos, EntityTracker.ChunkUpdate> first = store.snapshot();
        store.acknowledge(Map.of(a, first.get(a).revision()));
        store.capture(Map.of(), Set.of());
        assertFalse(store.hasPending());

        store.capture(observed(b, record(uuid)), Set.of());
        Map<ChunkPos, EntityTracker.ChunkUpdate> moved = store.snapshot();
        assertTrue(moved.get(a).tombstones().contains(uuid));
        assertTrue(moved.get(b).upserts().containsKey(uuid));

        // Destination-first acknowledgement must not let the source tombstone
        // erase the persisted destination bookkeeping.
        store.acknowledge(Map.of(b, moved.get(b).revision()));
        store.acknowledge(Map.of(a, moved.get(a).revision()));
        store.capture(observed(b, record(uuid)), Set.of());
        assertFalse(store.hasPending());
    }

    @Test
    void staleAcknowledgementCannotDiscardANewerRevision() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        ChunkPos chunk = new ChunkPos(0, 0);
        UUID uuid = UUID.randomUUID();

        store.capture(observed(chunk, record(uuid)), Set.of());
        long oldRevision = store.snapshot().get(chunk).revision();
        store.capture(observed(chunk, record(uuid, 2)), Set.of());
        long newRevision = store.snapshot().get(chunk).revision();

        store.acknowledge(Map.of(chunk, oldRevision));
        assertEquals(newRevision, store.snapshot().get(chunk).revision());
    }

    @Test
    void revisionsRemainUniqueAcrossClearedSessionStores() {
        ChunkPos chunk = new ChunkPos(0, 0);
        UUID uuid = UUID.randomUUID();
        EntitySnapshotStore oldSession = new EntitySnapshotStore();
        EntitySnapshotStore newSession = new EntitySnapshotStore();

        oldSession.capture(observed(chunk, record(uuid)), Set.of());
        newSession.capture(observed(chunk, record(uuid)), Set.of());

        assertNotEquals(oldSession.snapshot().get(chunk).revision(),
                newSession.snapshot().get(chunk).revision());
    }

    @Test
    void identicalPartialObservationDoesNotCreateAnotherWriteAfterAck() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        ChunkPos chunk = new ChunkPos(4, 5);
        EntityTracker.EntityRecord record = record(UUID.randomUUID());

        store.capture(observed(chunk, record), Set.of());
        store.acknowledge(Map.of(chunk, store.snapshot().get(chunk).revision()));
        store.capture(observed(chunk, record), Set.of());

        assertFalse(store.hasPending());
    }

    @Test
    void identicalCompleteObservationKeepsRevisionUntilAckThenStaysClean() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        ChunkPos chunk = new ChunkPos(-3, 7);

        store.capture(Map.of(), Set.of(chunk));
        long revision = store.snapshot().get(chunk).revision();
        store.capture(Map.of(), Set.of(chunk));
        assertEquals(revision, store.snapshot().get(chunk).revision());

        store.acknowledge(Map.of(chunk, revision));
        store.capture(Map.of(), Set.of(chunk));
        assertFalse(store.hasPending());
    }

    @Test
    void entityRecordDoesNotExposeMutableNbt() {
        EntityTracker.EntityRecord record = record(UUID.randomUUID());

        record.nbt().putInt("MutatedOutsideRecord", 1);

        assertFalse(record.nbt().contains("MutatedOutsideRecord"));
    }

    @Test
    void smallCompleteObservationsStayCleanWithLargeHistoricalEntityCoverage() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        Map<ChunkPos, Map<UUID, EntityTracker.EntityRecord>> history = new HashMap<>();
        for (int x = 0; x < 5_000; x++) {
            Map<UUID, EntityTracker.EntityRecord> records = new HashMap<>();
            for (int entity = 0; entity < 4; entity++) {
                UUID uuid = new UUID(x, entity);
                records.put(uuid, record(uuid));
            }
            history.put(new ChunkPos(x, 0), records);
        }
        store.capture(history, history.keySet());
        Map<ChunkPos, Long> revisions = new HashMap<>();
        store.snapshot().forEach((chunk, update) -> revisions.put(chunk, update.revision()));
        store.acknowledge(revisions);
        Map<ChunkPos, Map<UUID, EntityTracker.EntityRecord>> nearby = new HashMap<>();
        history.forEach((chunk, records) -> {
            if ((chunk.getMinBlockX() >> 4) < 25) nearby.put(chunk, records);
        });

        for (int observation = 0; observation < 100; observation++) {
            store.capture(nearby, nearby.keySet());
            assertFalse(store.hasPending());
        }
        assertEquals(20_000, store.knownEntityCount());

        store.capture(Map.of(), nearby.keySet());
        assertEquals(19_900, store.knownEntityCount());
        assertEquals(25, store.snapshot().size());
    }

    @Test
    void completeMoveAndDespawnDoNotLeaveHistoricalIndexEntries() {
        EntitySnapshotStore store = new EntitySnapshotStore();
        ChunkPos source = new ChunkPos(0, 0);
        ChunkPos target = new ChunkPos(1, 0);
        UUID uuid = new UUID(14, 32);
        store.capture(observed(source, record(uuid)), Set.of(source));
        store.acknowledge(Map.of(source, store.snapshot().get(source).revision()));

        store.capture(observed(target, record(uuid)), Set.of(source, target));
        var moved = store.snapshot();
        store.acknowledge(Map.of(target, moved.get(target).revision()));
        store.acknowledge(Map.of(source, moved.get(source).revision()));
        store.capture(observed(target, record(uuid)), Set.of(source, target));
        assertFalse(store.hasPending());
        assertEquals(1, store.knownEntityCount());

        store.capture(Map.of(), Set.of(target));
        store.acknowledge(Map.of(target, store.snapshot().get(target).revision()));
        store.capture(Map.of(), Set.of(source, target));
        assertFalse(store.hasPending());
        assertEquals(0, store.knownEntityCount());
        store.capture(observed(source, record(uuid)), Set.of());
        assertTrue(store.hasPending());
    }

    private static Map<ChunkPos, Map<UUID, EntityTracker.EntityRecord>> observed(
            ChunkPos chunk,
            EntityTracker.EntityRecord record) {
        return Map.of(chunk, Map.of(record.rootUuid(), record));
    }

    private static EntityTracker.EntityRecord record(UUID uuid) {
        return record(uuid, 1);
    }

    private static EntityTracker.EntityRecord record(UUID uuid, int marker) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:pig");
        nbt.putIntArray("UUID", uuidToIntArray(uuid));
        nbt.putInt("WorldMirrorTestMarker", marker);
        return new EntityTracker.EntityRecord(uuid, Set.of(uuid), nbt);
    }

    private static int[] uuidToIntArray(UUID uuid) {
        return new int[] {
                (int) (uuid.getMostSignificantBits() >> 32),
                (int) uuid.getMostSignificantBits(),
                (int) (uuid.getLeastSignificantBits() >> 32),
                (int) uuid.getLeastSignificantBits()
        };
    }
}
