package io.github.billstark001.worldmirror.format;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical seed terrain fingerprint shared by Minecraft and standalone NBT readers. */
public final class SeedTerrainDigest {
    public static final String ATTACHMENTS = "fabric:attachments";
    public static final String ATTACHMENT = "worldmirror:seed_generated";
    public static final String SEED_SCHEMA = "schema";
    public static final int SEED_SCHEMA_VERSION = 1;
    public static final String SEED_EDITED = "edited";
    public static final String EPOCH = "epoch";
    public static final String DATA_VERSION = "data_version";
    public static final String BASELINE = "baseline";
    public static final String BLENDING_ATTACHMENT = "worldmirror:seed_blending";
    public static final String BLENDING_DATA = "blending_data";
    public static final int BLENDING_MIN_SECTION = -4;
    public static final int BLENDING_MAX_SECTION = 20;
    public static final String CAPTURE_RECEIPT = "worldmirror:capture_receipt";
    public static final String CAPTURE_SOURCE = "source";
    public static final String CAPTURE_NODE = "node";
    public static final String CAPTURE_TIME = "time";
    public static final String CAPTURE_DIGEST = "digest";

    private static final int SCHEMA = 1;
    private static final int MAX_DEPTH = 512;

    private SeedTerrainDigest() {}

    /** Proves the complete captured chunk, excluding only the receipt being verified. */
    public static String captureDigest(Map<String, Object> chunk) {
        Map<String, Object> fields = new TreeMap<>(chunk);
        if (fields.get(ATTACHMENTS) instanceof Map<?, ?> attachments && attachments.containsKey(CAPTURE_RECEIPT)) {
            var others = new java.util.HashMap<>(attachments);
            others.remove(CAPTURE_RECEIPT);
            if (others.isEmpty())
                fields.remove(ATTACHMENTS);
            else
                fields.put(ATTACHMENTS, others);
        }
        return HexFormat.of().formatHex(hash(out -> {
            out.writeInt(SCHEMA);
            writeTag(out, fields, 0);
        }));
    }

    /** Numeric wrappers and primitive arrays retain their corresponding NBT tag types. */
    public static String digest(Map<String, Object> chunk) {
        Map<Byte, Map<String, Object>> sections = new TreeMap<>();
        var heights = new HashSet<Byte>();
        for (Object item : list(chunk.get("sections"))) {
            if (!(item instanceof Map<?, ?> section) || !(section.get("Y") instanceof Byte y))
                throw new IllegalArgumentException("Invalid chunk section height");
            if (!heights.add(y))
                throw new IllegalArgumentException("Duplicate chunk section height");
            var terrain = new TreeMap<String, Object>();
            terrain.put("Y", y);
            for (String key : List.of("block_states", "biomes")) {
                if (section.containsKey(key)) {
                    Object value = section.get(key);
                    if (!(value instanceof Map<?, ?>))
                        throw new IllegalArgumentException("Invalid section tag " + key);
                    terrain.put(key, value);
                }
            }
            if (terrain.size() > 1)
                sections.put(y, terrain);
        }

        List<byte[]> blockEntities = new ArrayList<>();
        for (Object entity : list(chunk.getOrDefault("block_entities", List.of()))) {
            if (!(entity instanceof Map<?, ?>))
                throw new IllegalArgumentException("Invalid block entity");
            blockEntities.add(hash(out -> writeTag(out, entity, 0)));
        }
        blockEntities.sort(Arrays::compareUnsigned);
        byte[] digest = hash(out -> {
            out.writeInt(SCHEMA);
            out.writeInt(sections.size());
            for (Map<String, Object> section : sections.values()) writeTag(out, section, 0);
            out.writeInt(blockEntities.size());
            for (byte[] entity : blockEntities) out.write(entity);
        });
        return HexFormat.of().formatHex(digest);
    }

    private static List<?> list(Object tag) {
        if (tag instanceof List<?> list)
            return list;
        throw new IllegalArgumentException("Expected an NBT list");
    }

    private static void writeTag(DataOutputStream out, Object tag, int depth) throws IOException {
        if (depth > MAX_DEPTH)
            throw new IllegalArgumentException("NBT exceeds the terrain fingerprint depth limit");
        switch (tag) {
            case Byte value -> {
                out.writeByte(1);
                out.writeByte(value);
            }
            case Short value -> {
                out.writeByte(2);
                out.writeShort(value);
            }
            case Integer value -> {
                out.writeByte(3);
                out.writeInt(value);
            }
            case Long value -> {
                out.writeByte(4);
                out.writeLong(value);
            }
            case Float value -> {
                out.writeByte(5);
                out.writeInt(Float.floatToIntBits(value));
            }
            case Double value -> {
                out.writeByte(6);
                out.writeLong(Double.doubleToLongBits(value));
            }
            case byte[] values -> {
                out.writeByte(7);
                out.writeInt(values.length);
                out.write(values);
            }
            case String value -> {
                out.writeByte(8);
                writeString(out, value);
            }
            case List<?> values -> {
                out.writeByte(9);
                out.writeInt(values.size());
                for (Object value : values) writeTag(out, value, depth + 1);
            }
            case Map<?, ?> values -> {
                out.writeByte(10);
                Map<String, Object> sorted = new TreeMap<>();
                values.forEach((key, value) -> {
                    if (!(key instanceof String name))
                        throw new IllegalArgumentException("Expected an NBT compound key");
                    sorted.put(name, value);
                });
                out.writeInt(sorted.size());
                for (var entry : sorted.entrySet()) {
                    writeString(out, entry.getKey());
                    writeTag(out, entry.getValue(), depth + 1);
                }
            }
            case int[] values -> {
                out.writeByte(11);
                out.writeInt(values.length);
                for (int value : values) out.writeInt(value);
            }
            case long[] values -> {
                out.writeByte(12);
                out.writeInt(values.length);
                for (long value : values) out.writeLong(value);
            }
            case null, default -> throw new IllegalArgumentException("Unsupported NBT fingerprint tag");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        out.writeChars(value);
    }

    @FunctionalInterface
    private interface Write {
        void write(DataOutputStream output) throws IOException;
    }

    private static byte[] hash(Write write) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(
                         new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                write.write(output);
            }
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot compute terrain fingerprint", failure);
        }
    }
}
