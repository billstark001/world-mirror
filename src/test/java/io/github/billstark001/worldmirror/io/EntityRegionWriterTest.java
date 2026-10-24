package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.ensgijs.nbt.mca.EntitiesChunk;
import io.github.ensgijs.nbt.mca.McaEntitiesFile;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import io.github.ensgijs.nbt.tag.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

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

class EntityRegionWriterTest {
    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void completeEmptyWritesARealEmptyEntityChunk() throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                7L, EntityTracker.Observation.COMPLETE, Map.of(), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertEquals(7L, result.get(Level.OVERWORLD).get(pos));
        EntitiesChunk written = McaFileHelpers.readEntities(
                entitiesDir().resolve("r.0.0.mca")).getChunk(0, 0);
        assertTrue(written.getHandle().getCompoundList("Entities").isEmpty());
    }

    @Test
    void unrelatedZeroLengthLegacyRegionDoesNotBlockEntityWrites() throws Exception {
        Path entities = entitiesDir();
        Files.createDirectories(entities);
        Path legacyPlaceholder = entities.resolve("r.-1.-1.mca");
        Files.createFile(legacyPlaceholder);
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                9L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertEquals(9L, result.get(Level.OVERWORLD).get(pos));
        assertEquals(0L, Files.size(legacyPlaceholder));
        assertEquals(1, McaFileHelpers.readEntities(entities.resolve("r.0.0.mca"))
                .getChunk(0, 0).getHandle().getCompoundList("Entities").size());
    }

    @Test
    void zeroLengthLegacyDestinationIsAtomicallyReplaced() throws Exception {
        Path entities = entitiesDir();
        Files.createDirectories(entities);
        Path destination = entities.resolve("r.0.0.mca");
        Files.createFile(destination);
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                10L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:cow", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertEquals(10L, result.get(Level.OVERWORLD).get(pos));
        assertTrue(Files.size(destination) >= 8_192L);
        ListTag<CompoundTag> written = McaFileHelpers.readEntities(destination)
                .getChunk(0, 0).getHandle().getCompoundList("Entities");
        assertEquals(1, written.size());
        assertEquals(Set.of(uuid), EntityRegionWriter.collectUuids(written.get(0)));
    }

    @Test
    void legacyTypeLessTreesAreIndexedAndPreservedWhileOtherChunksAreWritten() throws Exception {
        UUID vehicle = new UUID(70L, 1L);
        UUID rider = new UUID(70L, 2L);
        CompoundTag legacy = querzEntity("minecraft:boat", vehicle);
        legacy.remove("id");
        CompoundTag passenger = querzEntity("minecraft:pig", rider);
        passenger.remove("id");
        ListTag<CompoundTag> passengers = new ListTag<>(CompoundTag.class);
        passengers.add(passenger);
        legacy.put("Passengers", passengers);
        writeInitialRegion(new ChunkPos(0, 0), List.of(legacy));
        byte[] original = Files.readAllBytes(entitiesDir().resolve("r.0.0.mca"));
        UUID fresh = new UUID(70L, 3L);
        ChunkPos destination = new ChunkPos(32, 0);
        var update = new EntityTracker.ChunkUpdate(71L, EntityTracker.Observation.PARTIAL,
                Map.of(fresh, record("minecraft:cow", fresh, Set.of(fresh))), Set.of());

        var written = EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, Map.of(destination, update)), ExportProgress.NONE);

        assertEquals(71L, written.get(Level.OVERWORLD).get(destination));
        assertArrayEquals(original, Files.readAllBytes(entitiesDir().resolve("r.0.0.mca")));
        assertEquals(Set.of(vehicle, rider), EntityRegionWriter.collectUuids(legacy));
    }

    @Test
    void freshObservationReplacesTypeLessLegacyCopyByUuid() throws Exception {
        UUID uuid = new UUID(72L, 1L);
        CompoundTag legacy = querzEntity("minecraft:pig", uuid);
        legacy.remove("id");
        writeInitialRegion(new ChunkPos(0, 0), List.of(legacy));
        ChunkPos destination = new ChunkPos(32, 0);
        var update = new EntityTracker.ChunkUpdate(73L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());

        var written = EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, Map.of(destination, update)), ExportProgress.NONE);

        assertEquals(73L, written.get(Level.OVERWORLD).get(destination));
        assertTrue(McaFileHelpers.readEntities(entitiesDir().resolve("r.0.0.mca"))
                .getChunk(0, 0).getHandle().getCompoundList("Entities").isEmpty());
        assertEquals("minecraft:pig", McaFileHelpers.readEntities(entitiesDir().resolve("r.1.0.mca"))
                .getChunk(0, 0).getHandle().getCompoundList("Entities").get(0).getString("id"));
    }

    @Test
    void movingUuidRemovesOldDiskRecordAndCreatesOneNewRecord() throws Exception {
        UUID uuid = UUID.randomUUID();
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos b = new ChunkPos(32, 0);
        writeInitialRegion(a, List.of(querzEntity("minecraft:pig", uuid)));

        EntityTracker.ChunkUpdate from = new EntityTracker.ChunkUpdate(
                2L, EntityTracker.Observation.PARTIAL, Map.of(), Set.of(uuid));
        EntityTracker.EntityRecord record = record("minecraft:pig", uuid, Set.of(uuid));
        EntityTracker.ChunkUpdate to = new EntityTracker.ChunkUpdate(
                3L, EntityTracker.Observation.PARTIAL, Map.of(uuid, record), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(a, from, b, to)), ExportProgress.NONE);

        assertEquals(2, result.get(Level.OVERWORLD).size());
        McaEntitiesFile oldRegion = McaFileHelpers.readEntities(entitiesDir().resolve("r.0.0.mca"));
        assertTrue(oldRegion.getChunk(0, 0).getHandle().getCompoundList("Entities").isEmpty());
        McaEntitiesFile newRegion = McaFileHelpers.readEntities(entitiesDir().resolve("r.1.0.mca"));
        ListTag<CompoundTag> atB = newRegion.getChunk(0, 0).getHandle().getCompoundList("Entities");
        assertEquals(1, atB.size());
        assertEquals(Set.of(uuid), EntityRegionWriter.collectUuids(atB.get(0)));
    }

    @Test
    void passengerTreeIsIndexedRecursivelyAndNotDuplicatedAsTopLevel() {
        UUID vehicle = UUID.randomUUID();
        UUID passenger = UUID.randomUUID();
        CompoundTag root = querzEntity("minecraft:boat", vehicle);
        ListTag<CompoundTag> passengers = new ListTag<>(CompoundTag.class);
        passengers.add(querzEntity("minecraft:pig", passenger));
        root.put("Passengers", passengers);

        assertEquals(Set.of(vehicle, passenger), EntityRegionWriter.collectUuids(root));
    }

    @Test
    void partialUpdatePreservesUnobservedDiskEntity() throws Exception {
        UUID existingUuid = UUID.randomUUID();
        UUID incomingUuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        writeInitialRegion(pos, List.of(querzEntity("minecraft:cow", existingUuid)));
        EntityTracker.EntityRecord incoming = record(
                "minecraft:pig", incomingUuid, Set.of(incomingUuid));
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                4L, EntityTracker.Observation.PARTIAL,
                Map.of(incomingUuid, incoming), Set.of());

        EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        ListTag<CompoundTag> written = McaFileHelpers.readEntities(
                entitiesDir().resolve("r.0.0.mca")).getChunk(0, 0)
                .getHandle().getCompoundList("Entities");
        assertEquals(2, written.size());
        Set<UUID> uuids = new java.util.HashSet<>();
        written.forEach(root -> uuids.addAll(EntityRegionWriter.collectUuids(root)));
        assertEquals(Set.of(existingUuid, incomingUuid), uuids);
    }

    @Test
    void missingVanillaIdentityTagsAreRejectedBeforeWriting() {
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(0, 0);
        net.minecraft.nbt.CompoundTag invalidNbt = new net.minecraft.nbt.CompoundTag();
        invalidNbt.putIntArray("UUID", uuidToIntArray(uuid));
        EntityTracker.EntityRecord invalid = new EntityTracker.EntityRecord(
                uuid, Set.of(uuid), invalidNbt);
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
        EntityTracker.EntityRecord invalid = record(
                "minecraft:pig", serializedUuid, Set.of(indexedUuid));
        EntityTracker.EntityRecord rekeyed = new EntityTracker.EntityRecord(
                indexedUuid, invalid.containedUuids(), invalid.nbt());
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                6L, EntityTracker.Observation.PARTIAL,
                Map.of(indexedUuid, rekeyed), Set.of());

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
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                1L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:item", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(pos, update)), ExportProgress.NONE);

        assertTrue(result.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(corrupt));
    }

    @Test
    void outOfPlaceEntityChunkBlocksUnsafeCleanup() throws Exception {
        UUID uuid = UUID.randomUUID();
        writeInitialRegion(new ChunkPos(64, 0),
                List.of(querzEntity("minecraft:pig", uuid)));
        Path malformed = entitiesDir().resolve("r.0.0.mca");
        byte[] original = Files.readAllBytes(malformed);
        ChunkPos destination = new ChunkPos(1, 0);
        EntityTracker.ChunkUpdate update = new EntityTracker.ChunkUpdate(
                8L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());

        Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = EntityRegionWriter.write(
                tempDir, Map.of(Level.OVERWORLD, Map.of(destination, update)), ExportProgress.NONE);

        assertTrue(result.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(malformed));
    }

    private void writeInitialRegion(ChunkPos pos, List<CompoundTag> entities) throws Exception {
        Path dir = entitiesDir();
        Files.createDirectories(dir);
        McaEntitiesFile file = new McaEntitiesFile(pos.getRegionX(), pos.getRegionZ());
        file.setChunk(pos.getRegionLocalX(), pos.getRegionLocalZ(), entityChunk(pos, entities));
        McaFileHelpers.write(file, dir.resolve("r.0.0.mca").toFile());
    }

    private Path entitiesDir() {
        return ChunkExporter.dimensionDirForDimension(tempDir, Level.OVERWORLD).resolve("entities");
    }

    private static EntitiesChunk entityChunk(ChunkPos pos, List<CompoundTag> entities) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", 4671);
        chunk.putIntArray("Position", new int[] {
                pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4});
        ListTag<CompoundTag> list = new ListTag<>(CompoundTag.class);
        list.addAll(entities);
        chunk.put("Entities", list);
        return new EntitiesChunk(chunk);
    }

    @Test
    void rejectsDuplicatedUuidInsideASinglePassengerTree() {
        UUID uuid = new UUID(18L, 46L);
        net.minecraft.nbt.CompoundTag root = record("minecraft:pig", uuid, Set.of(uuid)).nbt();
        net.minecraft.nbt.ListTag passengers = new net.minecraft.nbt.ListTag();
        passengers.add(root.copy());
        root.put("Passengers", passengers);
        var invalid = new EntityTracker.EntityRecord(uuid, Set.of(uuid), root);
        var update = new EntityTracker.ChunkUpdate(19L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, invalid), Set.of());

        var written = EntityRegionWriter.write(tempDir,
                Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), ExportProgress.NONE);

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
    void oversizedStringsCannotBeSilentlyDroppedDuringEntityNbtConversion() {
        UUID root = new UUID(46L, 1L);
        var nbt = record("minecraft:pig", root, Set.of(root)).nbt();
        nbt.putString("mod_payload", "x".repeat(70_000));
        assertRejected(new EntityTracker.EntityRecord(root, Set.of(root), nbt));
    }

    private void assertRejected(EntityTracker.EntityRecord record) {
        var update = new EntityTracker.ChunkUpdate(19L, EntityTracker.Observation.PARTIAL,
                Map.of(record.rootUuid(), record), Set.of());
        var written = EntityRegionWriter.write(tempDir,
                Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), ExportProgress.NONE);
        assertTrue(written.isEmpty());
        assertFalse(Files.exists(entitiesDir().resolve("r.0.0.mca")));
    }

    @Test
    void storedUuidCollectionDoesNotConsumeTheCallStackForDeepPassengerTrees() {
        CompoundTag root = querzEntity("minecraft:pig", new UUID(0L, 0L));
        CompoundTag current = root;
        for (int index = 1; index < 10_000; index++) {
            CompoundTag child = querzEntity("minecraft:pig", new UUID(0L, index));
            ListTag<CompoundTag> passengers = new ListTag<>(CompoundTag.class);
            passengers.add(child);
            current.put("Passengers", passengers);
            current = child;
        }
        assertEquals(10_000, EntityRegionWriter.collectUuids(root).size());
    }

    @Test
    void aDelayedSourceTombstoneCannotDeleteTheAlreadySavedDestination() throws Exception {
        UUID uuid = new UUID(52L, 1L);
        ChunkPos source = new ChunkPos(0, 0);
        ChunkPos destination = new ChunkPos(32, 0);
        var arrival = new EntityTracker.ChunkUpdate(1L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, record("minecraft:pig", uuid, Set.of(uuid))), Set.of());
        assertEquals(1L, EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, Map.of(destination, arrival)), ExportProgress.NONE)
                .get(Level.OVERWORLD).get(destination));
        var cleanup = new EntityTracker.ChunkUpdate(2L, EntityTracker.Observation.PARTIAL, Map.of(), Set.of(uuid));

        var written = EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, Map.of(source, cleanup)), ExportProgress.NONE);

        assertEquals(2L, written.get(Level.OVERWORLD).get(source));
        var roots = McaFileHelpers.readEntities(entitiesDir().resolve("r.1.0.mca"))
                .getChunk(0, 0).getHandle().getCompoundList("Entities");
        assertEquals(1, roots.size());
        assertEquals(Set.of(uuid), EntityRegionWriter.collectUuids(roots.get(0)));
    }

    @Test
    void detachingAPassengerPreservesItsUnobservedVehicle() throws Exception {
        UUID vehicle = new UUID(53L, 1L);
        UUID passenger = new UUID(53L, 2L);
        CompoundTag root = querzEntity("minecraft:boat", vehicle);
        ListTag<CompoundTag> passengers = new ListTag<>(CompoundTag.class);
        passengers.add(querzEntity("minecraft:pig", passenger));
        root.put("Passengers", passengers);
        writeInitialRegion(new ChunkPos(0, 0), List.of(root));
        ChunkPos destination = new ChunkPos(32, 0);
        var arrival = new EntityTracker.ChunkUpdate(2L, EntityTracker.Observation.PARTIAL,
                Map.of(passenger, record("minecraft:pig", passenger, Set.of(passenger))), Set.of());

        assertEquals(2L, EntityRegionWriter.write(tempDir, Map.of(Level.OVERWORLD, Map.of(destination, arrival)), ExportProgress.NONE)
                .get(Level.OVERWORLD).get(destination));

        var remaining = McaFileHelpers.readEntities(entitiesDir().resolve("r.0.0.mca"))
                .getChunk(0, 0).getHandle().getCompoundList("Entities");
        assertEquals(1, remaining.size(), "moving a rider must not erase the parked vehicle");
        assertEquals(Set.of(vehicle), EntityRegionWriter.collectUuids(remaining.get(0)));
    }

    private static EntityTracker.EntityRecord record(
            String id, UUID uuid, Set<UUID> contained) {
        net.minecraft.nbt.CompoundTag nbt = new net.minecraft.nbt.CompoundTag();
        nbt.putString("id", id);
        nbt.putIntArray("UUID", uuidToIntArray(uuid));
        return new EntityTracker.EntityRecord(uuid, contained, nbt);
    }

    private static CompoundTag querzEntity(String id, UUID uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putIntArray("UUID", uuidToIntArray(uuid));
        return tag;
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
