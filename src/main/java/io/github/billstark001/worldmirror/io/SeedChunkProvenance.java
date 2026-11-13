package io.github.billstark001.worldmirror.io;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Portable view of the Fabric attachment recorded in generated terrain. */
public final class SeedChunkProvenance {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    static final Codec<Entry> CODEC = CompoundTag.CODEC.comapFlatMap(data -> {
        var entry = readPayload(data);
        return entry == null ? DataResult.error(() -> "Invalid seed chunk provenance")
                             : DataResult.success(entry);
    }, SeedChunkProvenance::writePayload);

    public record Entry(UUID epoch, int generationDataVersion, String baseline, boolean edited) {
        public Entry {
            if (epoch == null || generationDataVersion <= 0 || baseline == null
                    || !SHA_256.matcher(baseline).matches()) {
                throw new IllegalArgumentException("Invalid seed chunk provenance");
            }
        }

        Entry markEdited() {
            return edited ? this : new Entry(epoch, generationDataVersion, baseline, true);
        }
    }

    private SeedChunkProvenance() {}

    public static boolean hasMarker(CompoundTag chunk) {
        return chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS).contains(SeedTerrainDigest.ATTACHMENT);
    }

    public static Entry read(CompoundTag chunk) {
        var attachments = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS);
        var data = attachments.getCompound(SeedTerrainDigest.ATTACHMENT).orElse(null);
        return readPayload(data);
    }

    private static Entry readPayload(CompoundTag data) {
        if (data == null || !(data.get(SeedTerrainDigest.SEED_SCHEMA) instanceof IntTag schema)
                || schema.intValue() != SeedTerrainDigest.SEED_SCHEMA_VERSION
                || !(data.get(SeedTerrainDigest.DATA_VERSION) instanceof IntTag version)
                || !(data.get(SeedTerrainDigest.SEED_EDITED) instanceof ByteTag edited)
                || (edited.byteValue() != 0 && edited.byteValue() != 1))
            return null;
        try {
            String rawEpoch = data.getStringOr(SeedTerrainDigest.EPOCH, "");
            UUID epoch = UUID.fromString(rawEpoch);
            return epoch.toString().equals(rawEpoch)
                    ? new Entry(epoch, version.intValue(), data.getStringOr(SeedTerrainDigest.BASELINE, ""),
                              edited.byteValue() == 1)
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static CompoundTag writePayload(Entry entry) {
        var data = new CompoundTag();
        data.putInt(SeedTerrainDigest.SEED_SCHEMA, SeedTerrainDigest.SEED_SCHEMA_VERSION);
        data.putString(SeedTerrainDigest.EPOCH, entry.epoch().toString());
        data.putInt(SeedTerrainDigest.DATA_VERSION, entry.generationDataVersion());
        data.putString(SeedTerrainDigest.BASELINE, entry.baseline());
        data.putBoolean(SeedTerrainDigest.SEED_EDITED, entry.edited());
        return data;
    }

    public static Entry baseline(CompoundTag chunk, UUID epoch, int generationDataVersion) {
        return new Entry(epoch, generationDataVersion, digest(chunk), false);
    }

    public static boolean isUnmodified(CompoundTag chunk, Entry entry) {
        if (entry == null || entry.edited() || !(chunk.get("DataVersion") instanceof IntTag version)
                || version.intValue() != entry.generationDataVersion())
            return false;
        try {
            return entry.baseline().equals(digest(chunk));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String captureDigest(CompoundTag chunk) {
        return SeedTerrainDigest.captureDigest(portableCompound(chunk));
    }

    /** Drops source ownership without removing native blending data owned by another writer. */
    public static void strip(CompoundTag chunk) {
        var attachments = chunk.getCompound(SeedTerrainDigest.ATTACHMENTS).orElse(null);
        if (attachments == null)
            return;
        attachments.remove(SeedTerrainDigest.ATTACHMENT);
        attachments.remove(SeedTerrainDigest.CAPTURE_RECEIPT);
        if (attachments.remove(SeedTerrainDigest.BLENDING_ATTACHMENT) != null)
            chunk.remove(SeedTerrainDigest.BLENDING_DATA);
        if (attachments.isEmpty())
            chunk.remove(SeedTerrainDigest.ATTACHMENTS);
    }

    private static String digest(CompoundTag chunk) {
        Map<String, Object> view = new HashMap<>();
        var sections = chunk.get("sections");
        if (sections != null)
            view.put("sections", portable(sections));
        var blockEntities = chunk.get("block_entities");
        if (blockEntities != null)
            view.put("block_entities", portable(blockEntities));
        return SeedTerrainDigest.digest(view);
    }

    private static Object portable(Tag tag) {
        return switch (tag) {
            case CompoundTag compound -> portableCompound(compound);
            case ListTag list -> list.stream().map(SeedChunkProvenance::portable).toList();
            case NumericTag number -> number.asNumber().orElseThrow();
            case StringTag string -> string.asString().orElseThrow();
            case ByteArrayTag array -> array.getAsByteArray();
            case IntArrayTag array -> array.getAsIntArray();
            case LongArrayTag array -> array.getAsLongArray();
            default ->
                throw new IllegalArgumentException("Unsupported seed baseline NBT tag: " + tag.getId());
        };
    }

    private static Map<String, Object> portableCompound(CompoundTag compound) {
        Map<String, Object> entries = new HashMap<>();
        compound.entrySet().forEach(entry -> entries.put(entry.getKey(), portable(entry.getValue())));
        return entries;
    }
}
