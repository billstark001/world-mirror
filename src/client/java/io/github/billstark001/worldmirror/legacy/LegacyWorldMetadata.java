package io.github.billstark001.worldmirror.legacy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads released metadata formats solely for migration to the current document. */
public record LegacyWorldMetadata(int schemaVersion, String modVersion, String sourceId, String sourceType, String mirrorKind,
                                  String mirrorId, String parentMirrorId, long lastSyncTime,
                                  WorldMetadata.Generation generation, Map<String, Long> chunkUpdateTimes,
                                  FileProof.Version version) {
    public static final int UNVERSIONED_SCHEMA = 0;
    public static final int FIRST_VERSIONED_SCHEMA = 1;

    public LegacyWorldMetadata {
        chunkUpdateTimes = Map.copyOf(chunkUpdateTimes);
    }

    /** Detects released legacy headers at a migration entry point without decoding payload fields. */
    public static boolean hasLegacyHeader(Path worldFolder) {
        var document = readDocument(worldFolder);
        if (document.state() != JsonSupport.ReadState.VALID) return false;
        try {
            return legacySchema(document.value()) != null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Returns null for absent or current metadata. Invalid and foreign documents cannot be upgraded. */
    public static LegacyWorldMetadata read(Path worldFolder) throws IOException {
        Path metadataFile = worldFolder.resolve(WorldMetadata.FILE_NAME);
        FileProof.Version before;
        try {
            before = FileProof.version(metadataFile);
        } catch (java.nio.file.NoSuchFileException missing) {
            if (readDocument(worldFolder).state() == JsonSupport.ReadState.MISSING) return null;
            throw missing;
        }
        var document = readDocument(worldFolder);
        if (document.state() != JsonSupport.ReadState.VALID) {
            throw new IOException("Cannot read mirror metadata: " + metadataFile, document.failure());
        }
        if (!before.equals(FileProof.version(metadataFile))) {
            throw new IOException("Mirror metadata changed while reading: " + metadataFile);
        }
        try {
            Integer schema = legacySchema(document.value());
            if (schema != null) return decode(document.value(), schema, before);
            if (WorldMetadata.inspectSchema(worldFolder) == WorldMetadata.Schema.CURRENT) return null;
            throw new IllegalArgumentException("Unsupported mirror metadata schema");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Cannot upgrade mirror metadata: " + metadataFile, invalid);
        }
    }

    private static JsonSupport.Document<JsonObject> readDocument(Path worldFolder) {
        return JsonSupport.readDocument(worldFolder.resolve(WorldMetadata.FILE_NAME), JsonObject.class);
    }

    private static Integer legacySchema(JsonObject json) {
        String marker = string(json, "format", false);
        if (marker == null) {
            return !json.has("metadataSchema") && hasUnversionedSignature(json) ? UNVERSIONED_SCHEMA : null;
        }
        if (!WorldMetadata.FORMAT.equals(marker)) return null;
        int version = integer(json, "metadataSchema", UNVERSIONED_SCHEMA);
        return version == UNVERSIONED_SCHEMA || version == FIRST_VERSIONED_SCHEMA ? version : null;
    }

    private static boolean hasUnversionedSignature(JsonObject json) {
        String source = string(json, "sourceId", false);
        String type = string(json, "sourceType", false);
        if (source == null || type == null) return false;
        String prefix = switch (type) {
            case "server" -> "server:";
            case "singleplayer" -> "local:";
            case "mirror" -> "mirror:";
            case "nearby_export" -> "snapshot:";
            default -> null;
        };
        return prefix != null && source.startsWith(prefix) && validKey(source.substring(prefix.length()));
    }

    private static LegacyWorldMetadata decode(JsonObject json, int schema, FileProof.Version version) {
        String kind = string(json, "mirrorKind", false);
        if (kind != null && !kind.equals("synchronized") && !kind.equals("nearby_export")) {
            throw new IllegalArgumentException("Unknown legacy mirror kind: " + kind);
        }
        long lastSyncTime = number(json, "lastSyncTime", 0);
        if (lastSyncTime < 0) throw new IllegalArgumentException("Invalid legacy capture time");
        return new LegacyWorldMetadata(schema, string(json, "modVersion", false),
                string(json, "sourceId", true), string(json, "sourceType", false),
                kind, optionalLegacyId(json, "mirrorId"), optionalLegacyId(json, "parentMirrorId"),
                lastSyncTime, new WorldMetadata.Generation(WorldMetadata.GenerationMode.VOID,
                        integer(json, "worldgenSchema", 0),
                        integer(json, "worldgenAssetRevision", 0),
                        integer(json, "worldgenAssetDataVersion", 0),
                        integer(json, "legacyVoidChunkCleanupRevision", 0)),
                readChunkTimes(json), version);
    }

    private static String optionalLegacyId(JsonObject json, String field) {
        var value = json.get(field);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && value.getAsString().isBlank()) {
            return null;
        }
        return string(json, field, false);
    }

    private static Map<String, Long> readChunkTimes(JsonObject json) {
        var element = json.get("chunkUpdateTimes");
        if (element == null || element.isJsonNull()) return Map.of();
        if (!(element instanceof JsonObject times)) {
            throw new IllegalArgumentException("Invalid legacy chunk timestamp map");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (String key : times.keySet()) {
            if (times.get(key).isJsonNull()) continue;
            int separator = key.lastIndexOf('|');
            if (separator <= 0 || !validKey(key.substring(0, separator))) {
                throw new IllegalArgumentException("Invalid legacy chunk dimension: " + key);
            }
            String[] coordinates = key.substring(separator + 1).split(",", 2);
            if (coordinates.length != 2) throw new IllegalArgumentException("Invalid legacy chunk coordinate: " + key);
            Integer.parseInt(coordinates[0].trim());
            Integer.parseInt(coordinates[1].trim());
            long time = number(times, key, 0);
            if (time < 0) throw new IllegalArgumentException("Invalid legacy chunk timestamp: " + key);
            result.put(key, time);
        }
        return result;
    }

    private static String string(JsonObject json, String name, boolean required) {
        JsonElement element = json.get(name);
        if (!required && (element == null || element.isJsonNull())) return null;
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || !validKey(element.getAsString())) {
            throw new IllegalArgumentException("Invalid legacy metadata string: " + name);
        }
        return element.getAsString();
    }

    private static int integer(JsonObject json, String name, int defaultValue) {
        long value = number(json, name, defaultValue);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Invalid legacy metadata integer: " + name);
        }
        return (int) value;
    }

    private static long number(JsonObject json, String name, long defaultValue) {
        JsonElement element = json.get(name);
        if (element == null) return defaultValue;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid legacy metadata number: " + name);
        }
        return Long.parseLong(element.getAsString());
    }

    private static boolean validKey(String key) {
        return !key.isBlank() && key.chars().noneMatch(Character::isISOControl);
    }

    public WorldGraph.Kind kind() {
        return role() == WorldGraph.Role.SNAPSHOT && sourceId.startsWith("snapshot:") && parentMirrorId == null
                ? WorldGraph.Kind.SNAPSHOT : WorldGraph.Kind.MIRROR;
    }

    public WorldGraph.Role role() {
        return "nearby_export".equals(mirrorKind) || "nearby_export".equals(sourceType)
                ? WorldGraph.Role.SNAPSHOT : WorldGraph.Role.SYNC;
    }

    /** Resolves graph relationships supplied by the graph migration, retaining the original index key. */
    public WorldMetadata upgrade(String nodeId, String parentNodeId) {
        WorldMetadata metadata = WorldMetadata.create(nodeId, kind(), parentNodeId, role(), sourceId);
        if (modVersion != null) metadata.modVersion = modVersion;
        metadata.lastSyncTime = lastSyncTime;
        metadata.generation = generation;
        return metadata;
    }
}
