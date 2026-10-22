package io.github.billstark001.worldmirror.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntitySerializationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void indexesOnlyThePassengersActuallyPresentInTheSerializedTree() {
        UUID vehicle = new UUID(1, 1);
        UUID passenger = new UUID(2, 2);
        UUID nested = new UUID(3, 3);
        CompoundTag root = entity(vehicle);
        CompoundTag rider = entity(passenger);
        passengers(rider, entity(nested));
        passengers(root, rider);

        assertEquals(Set.of(vehicle, passenger, nested), EntityTracker.serializedUuids(root));
        root.remove("Passengers");
        assertEquals(Set.of(vehicle), EntityTracker.serializedUuids(root));
    }

    @Test
    void rejectsMalformedAndDuplicateUuidTreesBeforeCachingThem() {
        CompoundTag missing = new CompoundTag();
        assertThrows(IllegalArgumentException.class, () -> EntityTracker.serializedUuids(missing));
        missing.putIntArray("UUID", new int[3]);
        assertThrows(IllegalArgumentException.class, () -> EntityTracker.serializedUuids(missing));
        UUID uuid = new UUID(1, 1);
        CompoundTag duplicated = entity(uuid);
        passengers(duplicated, entity(uuid));
        assertThrows(IllegalArgumentException.class, () -> EntityTracker.serializedUuids(duplicated));
    }

    @Test
    void sharedImmutableRecordsKeepTheirInputAndAccessorIsolation() {
        UUID uuid = new UUID(4, 4);
        CompoundTag original = entity(uuid);
        var record = new EntityTracker.EntityRecord(uuid, Set.of(uuid), original);
        var copy = record.copy();
        original.putInt("ChangedInput", 1);
        copy.nbt().putInt("ChangedAccessor", 1);

        assertFalse(record.nbt().contains("ChangedInput"));
        assertFalse(record.nbt().contains("ChangedAccessor"));
        assertEquals(record, copy);
    }

    private static CompoundTag entity(UUID uuid) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        entity.putIntArray("UUID", UUIDUtil.uuidToIntArray(uuid));
        return entity;
    }

    private static void passengers(CompoundTag parent, CompoundTag child) {
        ListTag passengers = new ListTag();
        passengers.add(child);
        parent.put("Passengers", passengers);
    }
}
