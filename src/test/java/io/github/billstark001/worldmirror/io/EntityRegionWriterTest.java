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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRegionWriterTest extends EntityRegionFixture {
    @Test
    void completeEmptyWritesARealEmptyEntityChunk() throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        Path region = Files.createFile(Files.createDirectories(entitiesDir()).resolve("r.0.0.mca"));
        EntityTracker.ChunkUpdate update =
                new EntityTracker.ChunkUpdate(7L, EntityTracker.Observation.COMPLETE, Map.of(), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertEquals(7L, result.get(Level.OVERWORLD).get(pos));
        CompoundTag written = readRegion(region).get(pos);
        assertTrue(written.getListOrEmpty("Entities").isEmpty());
    }

    @Test
    void chunkCoordinatesAreNotTruncatedThroughBlockCoordinates() throws Exception {
        ChunkPos pos = new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE);
        var update = new EntityTracker.ChunkUpdate(4, EntityTracker.Observation.COMPLETE, Map.of(), Set.of());
        var result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);
        assertEquals(4L, result.get(Level.OVERWORLD).get(pos));
        Path file = entitiesDir().resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        assertArrayEquals(new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE},
                AnvilReader.readChunk(file, pos).getIntArray("Position").orElseThrow());
    }

    @Test
    void unrelatedZeroLengthLegacyRegionDoesNotBlockEntityWrites() throws Exception {
        Path entities = entitiesDir();
        Files.createDirectories(entities);
        Path legacyPlaceholder = entities.resolve("r.-1.-1.mca");
        Files.createFile(legacyPlaceholder);
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        EntityTracker.ChunkUpdate update =
                new EntityTracker.ChunkUpdate(9L, EntityTracker.Observation.PARTIAL,
                        Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertEquals(9L, result.get(Level.OVERWORLD).get(pos));
        assertEquals(0L, Files.size(legacyPlaceholder));
        assertEquals(1,
                readRegion(entities.resolve("r.0.0.mca"))
                        .get(new ChunkPos(0, 0))
                        .getListOrEmpty("Entities")
                        .size());
    }

    @Test
    void zeroLengthLegacyDestinationIsAtomicallyReplaced() throws Exception {
        Path entities = entitiesDir();
        Files.createDirectories(entities);
        Path destination = entities.resolve("r.0.0.mca");
        Files.createFile(destination);
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        EntityTracker.ChunkUpdate update =
                new EntityTracker.ChunkUpdate(10L, EntityTracker.Observation.PARTIAL,
                        Map.of(uuid, record("minecraft:cow", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertEquals(10L, result.get(Level.OVERWORLD).get(pos));
        assertTrue(Files.size(destination) >= 8_192L);
        ListTag written = readRegion(destination).get(new ChunkPos(0, 0)).getListOrEmpty("Entities");
        assertEquals(1, written.size());
        assertEquals(Set.of(uuid), LegacyEntityRecords.collectUuids(written.getCompoundOrEmpty(0)));
    }

    @Test
    void legacyTypeLessTreesAreIndexedAndPreservedWhileOtherChunksAreWritten() throws Exception {
        UUID vehicle = new UUID(70L, 1L);
        UUID rider = new UUID(70L, 2L);
        CompoundTag legacy = storedEntity("minecraft:boat", vehicle);
        legacy.remove("id");
        CompoundTag passenger = storedEntity("minecraft:pig", rider);
        passenger.remove("id");
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        legacy.put("Passengers", passengers);
        writeInitialRegion(new ChunkPos(0, 0), List.of(legacy));
        byte[] original = Files.readAllBytes(entitiesDir().resolve("r.0.0.mca"));
        UUID fresh = new UUID(70L, 3L);
        ChunkPos destination = new ChunkPos(32, 0);
        var update = new EntityTracker.ChunkUpdate(71L, EntityTracker.Observation.PARTIAL,
                Map.of(fresh, record("minecraft:cow", fresh, Set.of(fresh))), Set.of());

        var written = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(destination, update)), ExportProgress.NONE);

        assertEquals(71L, written.get(Level.OVERWORLD).get(destination));
        assertArrayEquals(original, Files.readAllBytes(entitiesDir().resolve("r.0.0.mca")));
        assertEquals(Set.of(vehicle, rider), LegacyEntityRecords.collectUuids(legacy));
    }

    @Test
    void freshObservationReplacesTypeLessLegacyCopyByUuid() throws Exception {
        UUID uuid = new UUID(72L, 1L);
        CompoundTag legacy = storedEntity("minecraft:pig", uuid);
        legacy.remove("id");
        writeInitialRegion(new ChunkPos(0, 0), List.of(legacy));
        ChunkPos destination = new ChunkPos(32, 0);
        var update = new EntityTracker.ChunkUpdate(73L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());

        var written = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(destination, update)), ExportProgress.NONE);

        assertEquals(73L, written.get(Level.OVERWORLD).get(destination));
        assertTrue(readRegion(entitiesDir().resolve("r.0.0.mca"))
                        .get(new ChunkPos(0, 0))
                        .getListOrEmpty("Entities")
                        .isEmpty());
        assertEquals("minecraft:pig",
                readRegion(entitiesDir().resolve("r.1.0.mca"))
                        .get(new ChunkPos(32, 0))
                        .getListOrEmpty("Entities")
                        .getCompoundOrEmpty(0)
                        .getString("id")
                        .orElseThrow());
    }

    @Test
    void passengerTreeIsIndexedRecursivelyAndNotDuplicatedAsTopLevel() {
        UUID vehicle = UUID.randomUUID();
        UUID passenger = UUID.randomUUID();
        CompoundTag root = storedEntity("minecraft:boat", vehicle);
        ListTag passengers = new ListTag();
        passengers.add(storedEntity("minecraft:pig", passenger));
        root.put("Passengers", passengers);

        assertEquals(Set.of(vehicle, passenger), LegacyEntityRecords.collectUuids((CompoundTag) root));
    }

    @Test
    void partialUpdatePreservesUnobservedDiskEntity() throws Exception {
        UUID existingUuid = UUID.randomUUID();
        UUID incomingUuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        writeInitialRegion(pos, List.of(storedEntity("minecraft:cow", existingUuid)));
        EntityTracker.EntityRecord incoming = record("minecraft:pig", incomingUuid, Set.of(incomingUuid));
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                4L, EntityTracker.Observation.PARTIAL, Map.of(incomingUuid, incoming), Set.of());

        EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        ListTag written = readRegion(entitiesDir().resolve("r.0.0.mca"))
                                  .get(new ChunkPos(0, 0))
                                  .getListOrEmpty("Entities");
        assertEquals(2, written.size());
        Set<UUID> uuids = new java.util.HashSet<>();
        written.forEach(root -> uuids.addAll(LegacyEntityRecords.collectUuids((CompoundTag) root)));
        assertEquals(Set.of(existingUuid, incomingUuid), uuids);
    }

    @Test
    void missingVanillaIdentityTagsAreRejectedBeforeWriting() {
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        net.minecraft.nbt.CompoundTag invalidNbt = new net.minecraft.nbt.CompoundTag();
        invalidNbt.putIntArray("UUID", uuidToIntArray(uuid));
        EntityTracker.EntityRecord invalid = new EntityTracker.EntityRecord(uuid, Set.of(uuid), invalidNbt);
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                5L, EntityTracker.Observation.PARTIAL, Map.of(uuid, invalid), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertTrue(result.isEmpty());
        assertFalse(Files.exists(entitiesDir().resolve("r.0.0.mca")));
    }

    @Test
    void mismatchedSerializedUuidIsRejectedBeforeWriting() {
        UUID indexedUuid = UUID.randomUUID();
        UUID serializedUuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        EntityTracker.EntityRecord invalid = record("minecraft:pig", serializedUuid, Set.of(indexedUuid));
        EntityTracker.EntityRecord rekeyed =
                new EntityTracker.EntityRecord(indexedUuid, invalid.containedUuids(), invalid.nbt());
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                6L, EntityTracker.Observation.PARTIAL, Map.of(indexedUuid, rekeyed), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertTrue(result.isEmpty());
        assertFalse(Files.exists(entitiesDir().resolve("r.0.0.mca")));
    }

    @Test
    void unreadableExistingRegionIsNeverOverwritten() throws Exception {
        Path entities = entitiesDir();
        Files.createDirectories(entities);
        Path corrupt = entities.resolve("r.0.0.mca");
        byte[] original = new byte[] {1, 2, 3, 4, 5};
        Files.write(corrupt, original);
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        EntityTracker.ChunkUpdate update =
                new EntityTracker.ChunkUpdate(1L, EntityTracker.Observation.PARTIAL,
                        Map.of(uuid, record("minecraft:item", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertTrue(result.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(corrupt));
    }

    @Test
    void outOfPlaceEntityChunkBlocksUnsafeCleanup() throws Exception {
        UUID uuid = UUID.randomUUID();
        writeInitialRegion(new ChunkPos(64, 0), List.of(storedEntity("minecraft:pig", uuid)));
        Path malformed = entitiesDir().resolve("r.0.0.mca");
        byte[] original = Files.readAllBytes(malformed);
        ChunkPos destination = new ChunkPos(1, 0);
        EntityTracker.ChunkUpdate update =
                new EntityTracker.ChunkUpdate(8L, EntityTracker.Observation.PARTIAL,
                        Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(destination, update)), ExportProgress.NONE);

        assertTrue(result.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(malformed));
    }

    @Test
    void rejectsDuplicatedUuidInsideASinglePassengerTree() {
        UUID uuid = new UUID(18L, 46L);
        net.minecraft.nbt.CompoundTag root = record("minecraft:pig", uuid, Set.of(uuid)).nbt();
        net.minecraft.nbt.ListTag passengers = new net.minecraft.nbt.ListTag();
        passengers.add(root.copy());
        root.put("Passengers", passengers);
        var invalid = new EntityTracker.EntityRecord(uuid, Set.of(uuid), root);
        var update = new EntityTracker.ChunkUpdate(
                19L, EntityTracker.Observation.PARTIAL, Map.of(uuid, invalid), Set.of());

        var written = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), ExportProgress.NONE);

        assertTrue(written.isEmpty());
        assertFalse(Files.exists(entitiesDir().resolve("r.0.0.mca")));
    }

    @Test
    void rejectsARecordWhoseAdvertisedRootIsActuallyAPassenger() {
        UUID root = new UUID(44L, 1L);
        UUID rider = new UUID(44L, 2L);
        var nbt = record("minecraft:boat", root, Set.of(root)).nbt();
        net.minecraft.nbt.ListTag passengers = new net.minecraft.nbt.ListTag();
        passengers.add(record("minecraft:pig", rider, Set.of(rider)).nbt());
        nbt.put("Passengers", passengers);
        var invalid = new EntityTracker.EntityRecord(rider, Set.of(root, rider), nbt);
        assertRejected(invalid);
    }

    @Test
    void rejectsAPassengerWithoutAUuidInsteadOfAcknowledgingItsLoss() {
        UUID root = new UUID(45L, 1L);
        var nbt = record("minecraft:boat", root, Set.of(root)).nbt();
        net.minecraft.nbt.CompoundTag missingUuid = new net.minecraft.nbt.CompoundTag();
        missingUuid.putString("id", "minecraft:pig");
        net.minecraft.nbt.ListTag passengers = new net.minecraft.nbt.ListTag();
        passengers.add(missingUuid);
        nbt.put("Passengers", passengers);
        assertRejected(new EntityTracker.EntityRecord(root, Set.of(root), nbt));
    }

    @Test
    void oversizedStringsCannotBeSilentlyDroppedDuringEntitySerialization() {
        UUID root = new UUID(46L, 1L);
        var nbt = record("minecraft:pig", root, Set.of(root)).nbt();
        nbt.putString("mod_payload", "x".repeat(70_000));
        assertRejected(new EntityTracker.EntityRecord(root, Set.of(root), nbt));
    }

    private void assertRejected(EntityTracker.EntityRecord record) {
        var update = new EntityTracker.ChunkUpdate(
                19L, EntityTracker.Observation.PARTIAL, Map.of(record.rootUuid(), record), Set.of());
        var written = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), ExportProgress.NONE);
        assertTrue(written.isEmpty());
        assertFalse(Files.exists(entitiesDir().resolve("r.0.0.mca")));
    }

    @Test
    void storedUuidCollectionDoesNotConsumeTheCallStackForDeepPassengerTrees() {
        CompoundTag root = storedEntity("minecraft:pig", new UUID(0L, 0L));
        CompoundTag current = root;
        for (int index = 1; index < 10_000; index++) {
            CompoundTag child = storedEntity("minecraft:pig", new UUID(0L, index));
            ListTag passengers = new ListTag();
            passengers.add(child);
            current.put("Passengers", passengers);
            current = child;
        }
        assertEquals(10_000, LegacyEntityRecords.collectUuids((CompoundTag) root).size());
    }

    @Test
    void nativeNoneLz4AndExternalEntityRegionsRemainWritable() throws Exception {
        var selected = net.minecraft.world.level.chunk.storage.RegionFileVersion.getSelected();
        try {
            for (String compression : List.of("none", "lz4", "deflate")) {
                net.minecraft.world.level.chunk.storage.RegionFileVersion.configure(compression);
                Path world = tempDir.resolve(compression);
                Path file = WorldSaveLayout.dimensionDirectory(world, Level.OVERWORLD)
                                    .resolve("entities/r.0.0.mca");
                UUID old = UUID.randomUUID(), fresh = UUID.randomUUID();
                CompoundTag original =
                        entityChunk(new ChunkPos(0, 0), List.of(storedEntity("minecraft:pig", old)));
                original.putString("mod_marker", "preserve");
                if (compression.equals("deflate")) {
                    byte[] noise = new byte[1_200_000];
                    new java.util.Random(31).nextBytes(noise);
                    original.putByteArray("mod_payload", noise);
                }
                McaWriteSupport.writeChunks(file, Level.OVERWORLD, Map.of(new ChunkPos(0, 0), original));
                assertEquals(original, AnvilReader.readChunk(file, new ChunkPos(0, 0)));
                var update = new EntityTracker.ChunkUpdate(3, EntityTracker.Observation.PARTIAL,
                        Map.of(fresh, record("minecraft:cow", fresh, Set.of(fresh))), Set.of());
                var result = EntityRegionWriter.write(world,
                        Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), ExportProgress.NONE);
                assertEquals(3L, result.get(Level.OVERWORLD).get(new ChunkPos(0, 0)));
                CompoundTag saved = AnvilReader.readChunk(file, new ChunkPos(0, 0));
                assertEquals("preserve", saved.getString("mod_marker").orElseThrow());
                assertEquals(2, saved.getListOrEmpty("Entities").size());
                assertArrayEquals(original.getByteArray("mod_payload").orElse(new byte[0]),
                        saved.getByteArray("mod_payload").orElse(new byte[0]));
            }
        } finally {
            net.minecraft.world.level.chunk.storage.RegionFileVersion.configure(switch (selected.getId()) {
                case 3 -> "none";
                case 4 -> "lz4";
                default -> "deflate";
            });
        }
    }
}
