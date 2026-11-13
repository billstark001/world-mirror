package io.github.billstark001.worldmirror.legacy;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Reads stored entity trees, including v0 UUID-bearing records without a type ID. */
public final class LegacyEntityRecords {
    private LegacyEntityRecords() {}

    public static UUID storedUuid(CompoundTag entity) {
        int[] raw = entity.getIntArray("UUID").orElseThrow(
                () -> new IllegalArgumentException("Missing stored UUID"));
        if (raw.length != 4) throw new IllegalArgumentException("Invalid stored UUID");
        return UUIDUtil.uuidFromIntArray(raw);
    }

    /**
     * Indexes stored identities without requiring a type id. Older mirrors may
     * contain UUID-bearing nodes without id; preserve these opaque records until
     * a fresh vanilla observation can replace them. Incoming records are validated
     * separately before they can be written.
     */
    public static Set<UUID> collectUuids(CompoundTag entity) {
        Set<UUID> result = new HashSet<>();
        ArrayDeque<CompoundTag> pending = new ArrayDeque<>();
        pending.push(entity);
        while (!pending.isEmpty()) {
            CompoundTag current = pending.pop();
            if (!result.add(storedUuid(current))) {
                throw new IllegalArgumentException("Duplicate UUID within an entity passenger tree");
            }
            Tag passengers = current.get("Passengers");
            if (passengers == null) continue;
            if (!(passengers instanceof ListTag list)) throw new IllegalArgumentException("Invalid entity list: Passengers");
            for (Tag passenger : list) {
                if (!(passenger instanceof CompoundTag compound)) throw new IllegalArgumentException("Invalid entity record");
                pending.push(compound);
            }
        }
        return result;
    }
}
