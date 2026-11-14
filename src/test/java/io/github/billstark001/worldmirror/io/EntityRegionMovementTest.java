package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.legacy.LegacyEntityRecords;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRegionMovementTest extends EntityRegionFixture {
    @Test
    void aCrossRegionSwapKeepsOneCopyOfEachEntity() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        ChunkPos west = new ChunkPos(0, 0), east = new ChunkPos(32, 0);
        writeInitialRegion(west, List.of(storedEntity("minecraft:pig", first)));
        McaWriteSupport.writeChunks(entitiesDir().resolve("r.1.0.mca"), Level.OVERWORLD,
                Map.of(east, entityChunk(east, List.of(storedEntity("minecraft:cow", second)))));
        var updates = Map.of(west,
                new EntityTracker.ChunkUpdate(8, EntityTracker.Observation.COMPLETE,
                        Map.of(second, record("minecraft:cow", second, Set.of(second))), Set.of()),
                east,
                new EntityTracker.ChunkUpdate(9, EntityTracker.Observation.COMPLETE,
                        Map.of(first, record("minecraft:pig", first, Set.of(first))), Set.of()));
        var written =
                EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, updates), ExportProgress.NONE);
        assertEquals(2, written.get(Level.OVERWORLD).size());
        assertEquals(Set.of(second),
                LegacyEntityRecords.collectUuids(
                        AnvilReader.readChunk(entitiesDir().resolve("r.0.0.mca"), west)
                                .getListOrEmpty("Entities")
                                .getCompoundOrEmpty(0)));
        assertEquals(Set.of(first),
                LegacyEntityRecords.collectUuids(
                        AnvilReader.readChunk(entitiesDir().resolve("r.1.0.mca"), east)
                                .getListOrEmpty("Entities")
                                .getCompoundOrEmpty(0)));
    }

    @Test
    void aFailedExternalDestinationSeedCannotDeleteEitherOriginal() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        ChunkPos west = new ChunkPos(0, 0), destination = new ChunkPos(32, 0), east = new ChunkPos(64, 0);
        writeInitialRegion(west, List.of(storedEntity("minecraft:pig", first)));
        McaWriteSupport.writeChunks(entitiesDir().resolve("r.2.0.mca"), Level.OVERWORLD,
                Map.of(east, entityChunk(east, List.of(storedEntity("minecraft:cow", second)))));
        byte[] noise = new byte[1_200_000];
        new java.util.Random(61).nextBytes(noise);
        CompoundTag large = storedEntity("minecraft:pig", first);
        large.putByteArray("opaque", noise);
        Path blocked = Files.createDirectory(entitiesDir().resolve("c.32.0.mcc"));
        Files.writeString(blocked.resolve("keep"), "unrelated contents");
        var updates = Map.of(west,
                new EntityTracker.ChunkUpdate(8, EntityTracker.Observation.COMPLETE,
                        Map.of(second, record("minecraft:cow", second, Set.of(second))), Set.of()),
                destination,
                new EntityTracker.ChunkUpdate(9, EntityTracker.Observation.COMPLETE,
                        Map.of(first, new EntityTracker.EntityRecord(first, Set.of(first), large)),
                        Set.of()));
        assertTrue(EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, updates), ExportProgress.NONE)
                        .isEmpty());
        assertTrue(AnvilReader.readChunk(entitiesDir().resolve("r.0.0.mca"), west)
                        .getListOrEmpty("Entities")
                        .stream()
                        .map(tag -> LegacyEntityRecords.collectUuids((CompoundTag) tag))
                        .anyMatch(ids -> ids.contains(first)));
        assertTrue(AnvilReader.readChunk(entitiesDir().resolve("r.2.0.mca"), east)
                        .getListOrEmpty("Entities")
                        .stream()
                        .map(tag -> LegacyEntityRecords.collectUuids((CompoundTag) tag))
                        .anyMatch(ids -> ids.contains(second)));
        assertEquals("unrelated contents", Files.readString(blocked.resolve("keep")));
    }

    @Test
    void movingUuidRemovesOldDiskRecordAndCreatesOneNewRecord() throws Exception {
        UUID uuid = UUID.randomUUID();
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos b = new ChunkPos(32, 0);
        writeInitialRegion(a, List.of(storedEntity("minecraft:pig", uuid)));

        EntityTracker.ChunkUpdate from =
                new EntityTracker.ChunkUpdate(2L, EntityTracker.Observation.PARTIAL, Map.of(), Set.of(uuid));
        EntityTracker.EntityRecord record = record("minecraft:pig", uuid, Set.of(uuid));
        EntityTracker.ChunkUpdate to = new EntityTracker.ChunkUpdate(
                3L, EntityTracker.Observation.PARTIAL, Map.of(uuid, record), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(a, from, b, to)), ExportProgress.NONE);

        assertEquals(2, result.get(Level.OVERWORLD).size());
        Map<ChunkPos, CompoundTag> oldRegion = readRegion(entitiesDir().resolve("r.0.0.mca"));
        assertTrue(oldRegion.get(new ChunkPos(0, 0)).getListOrEmpty("Entities").isEmpty());
        Map<ChunkPos, CompoundTag> newRegion = readRegion(entitiesDir().resolve("r.1.0.mca"));
        ListTag atB = newRegion.get(new ChunkPos(32, 0)).getListOrEmpty("Entities");
        assertEquals(1, atB.size());
        assertEquals(Set.of(uuid), LegacyEntityRecords.collectUuids(atB.getCompoundOrEmpty(0)));
    }

    @Test
    void aDelayedSourceTombstoneCannotDeleteTheAlreadySavedDestination() throws Exception {
        UUID uuid = new UUID(52L, 1L);
        ChunkPos source = new ChunkPos(0, 0);
        ChunkPos destination = new ChunkPos(32, 0);
        var arrival = new EntityTracker.ChunkUpdate(1L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());
        assertEquals(1L,
                EntityRegionWriter
                        .write(tempDir, Map.of(Level.OVERWORLD, Map.of(destination, arrival)),
                                ExportProgress.NONE)
                        .get(Level.OVERWORLD)
                        .get(destination));
        var cleanup =
                new EntityTracker.ChunkUpdate(2L, EntityTracker.Observation.PARTIAL, Map.of(), Set.of(uuid));

        var written = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(source, cleanup)), ExportProgress.NONE);

        assertEquals(2L, written.get(Level.OVERWORLD).get(source));
        var roots = readRegion(entitiesDir().resolve("r.1.0.mca"))
                            .get(new ChunkPos(32, 0))
                            .getListOrEmpty("Entities");
        assertEquals(1, roots.size());
        assertEquals(Set.of(uuid), LegacyEntityRecords.collectUuids(roots.getCompoundOrEmpty(0)));
    }

    @Test
    void detachingAPassengerPreservesItsUnobservedVehicle() throws Exception {
        UUID vehicle = new UUID(53L, 1L);
        UUID passenger = new UUID(53L, 2L);
        CompoundTag root = storedEntity("minecraft:boat", vehicle);
        ListTag passengers = new ListTag();
        passengers.add(storedEntity("minecraft:pig", passenger));
        root.put("Passengers", passengers);
        writeInitialRegion(new ChunkPos(0, 0), List.of(root));
        ChunkPos destination = new ChunkPos(32, 0);
        var arrival = new EntityTracker.ChunkUpdate(2L, EntityTracker.Observation.PARTIAL,
                Map.of(passenger, record("minecraft:pig", passenger, Set.of(passenger))), Set.of());

        assertEquals(2L,
                EntityRegionWriter
                        .write(tempDir, Map.of(Level.OVERWORLD, Map.of(destination, arrival)),
                                ExportProgress.NONE)
                        .get(Level.OVERWORLD)
                        .get(destination));

        var remaining = readRegion(entitiesDir().resolve("r.0.0.mca"))
                                .get(new ChunkPos(0, 0))
                                .getListOrEmpty("Entities");
        assertEquals(1, remaining.size(), "moving a rider must not erase the parked vehicle");
        assertEquals(Set.of(vehicle), LegacyEntityRecords.collectUuids(remaining.getCompoundOrEmpty(0)));
    }
}
