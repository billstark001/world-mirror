package io.github.billstark001.worldmirror.download;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.format.WorldLineage;
import io.github.billstark001.worldmirror.legacy.LegacyVoidChunkCleanup;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical metadata for a mirror or snapshot saved on disk. */
public final class WorldMetadata {
    public static final String FILE_NAME = MirrorFormat.METADATA_FILE;
    public static final String FORMAT = MirrorFormat.FORMAT;
    public static final int CURRENT_METADATA_SCHEMA = 2;
    public static final int CURRENT_WORLDGEN_SCHEMA = MirrorFormat.WORLDGEN_SCHEMA;
    public static final String NORMAL_GENERATION_PRESET = "minecraft:normal";
    private static final int SEEDED_TERRAIN_REPAIR_REVISION = 3;
    private static final Pattern SETTINGS_DIGEST = Pattern.compile("[0-9a-f]{64}");

    public enum Schema { MISSING, CURRENT, FUTURE, UNMANAGED, UNREADABLE }
    public enum GenerationMode { VOID, SEEDED }

    record Inspection(Schema schema, WorldMetadata metadata) {}

    public record Generation(GenerationMode mode, int revision, int assetRevision, int dataVersion,
            int cleanupRevision, String preset, String settingsDigest, UUID seedEpoch, boolean blendTerrain) {
        public Generation(
                GenerationMode mode, int revision, int assetRevision, int dataVersion, int cleanupRevision) {
            this(mode, revision, assetRevision, dataVersion, cleanupRevision, null, null, null, false);
        }

        public Generation {
            if (mode == null || revision < 0 || assetRevision < 0 || dataVersion < 0 || cleanupRevision < 0) {
                throw new IllegalArgumentException("Invalid mirror generation");
            }
            if (mode == GenerationMode.SEEDED) {
                if (!NORMAL_GENERATION_PRESET.equals(preset) || settingsDigest == null
                        || !SETTINGS_DIGEST.matcher(settingsDigest).matches() || seedEpoch == null) {
                    throw new IllegalArgumentException("Invalid seeded mirror generation");
                }
            } else if (preset != null || settingsDigest != null || seedEpoch != null || blendTerrain) {
                throw new IllegalArgumentException("Seed configuration is invalid for a void mirror");
            }
        }
    }

    public final String format = FORMAT;
    public final int metadataSchema = CURRENT_METADATA_SCHEMA;
    public String modVersion;
    public String nodeId;
    public WorldGraph.Kind kind;
    public String parentNodeId;
    public WorldGraph.Role role;
    /** Physical SQLite namespace retained when graph identities or routes change. */
    public String sourceId;
    public long lastSyncTime;
    public Generation generation;

    private WorldMetadata() {}

    public static WorldMetadata create(
            String nodeId, WorldGraph.Kind kind, String parentNodeId, WorldGraph.Role role, String sourceId) {
        WorldMetadata metadata = new WorldMetadata();
        metadata.modVersion = currentModVersion();
        metadata.nodeId = nodeId;
        metadata.kind = kind;
        metadata.parentNodeId = parentNodeId;
        metadata.role = role;
        metadata.sourceId = sourceId;
        metadata.generation = new Generation(GenerationMode.VOID, 0, 0, 0, 0);
        metadata.validate();
        return metadata;
    }

    /** Inspects current metadata without adopting a document of an unsupported format. */
    public static Schema inspectSchema(Path worldFolder) {
        return inspect(worldFolder).schema();
    }

    static Inspection inspect(Path worldFolder) {
        var read = readDocument(worldFolder);
        if (read.state() == JsonSupport.ReadState.MISSING)
            return new Inspection(Schema.MISSING, null);
        if (read.state() != JsonSupport.ReadState.VALID)
            return new Inspection(Schema.UNREADABLE, null);
        try {
            Schema schema = schema(read.value());
            return new Inspection(schema, schema == Schema.CURRENT ? fromCurrent(read.value()) : null);
        } catch (IllegalArgumentException malformed) {
            return new Inspection(Schema.UNREADABLE, null);
        }
    }

    /** Reads a complete current document. Explicit upgrade code owns older disk schemas. */
    public static Optional<WorldMetadata> loadIfPresent(Path worldFolder) {
        var read = readDocument(worldFolder);
        if (read.state() == JsonSupport.ReadState.MISSING)
            return Optional.empty();
        try {
            if (read.state() == JsonSupport.ReadState.VALID) {
                return schema(read.value()) == Schema.CURRENT ? Optional.of(fromCurrent(read.value()))
                                                              : Optional.empty();
            }
            WMLogger.warnRateLimited("metadata-read-" + worldFolder, 30_000L,
                    "World metadata unavailable: " + worldFolder, read.failure());
        } catch (IllegalArgumentException malformed) {
            WMLogger.warnRateLimited("metadata-read-" + worldFolder, 30_000L,
                    "Invalid world metadata: " + worldFolder, malformed);
        }
        return Optional.empty();
    }

    private static JsonSupport.Document<JsonObject> readDocument(Path worldFolder) {
        return JsonSupport.readDocument(worldFolder.resolve(FILE_NAME), JsonObject.class);
    }

    private static Schema schema(JsonObject json) {
        String marker = string(json, "format", false);
        if (marker == null && json.has("metadataSchema"))
            return Schema.UNREADABLE;
        if (!FORMAT.equals(marker))
            return Schema.UNMANAGED;
        int version = integer(json, "metadataSchema", -1);
        if (version > CURRENT_METADATA_SCHEMA)
            return Schema.FUTURE;
        return version == CURRENT_METADATA_SCHEMA ? Schema.CURRENT : Schema.UNREADABLE;
    }

    private static WorldMetadata fromCurrent(JsonObject json) {
        WorldMetadata metadata = new WorldMetadata();
        metadata.modVersion = string(json, "modVersion", false);
        metadata.nodeId = string(json, "nodeId", true);
        metadata.kind = WorldGraph.Kind.valueOf(string(json, "kind", true));
        metadata.parentNodeId = string(json, "parentNodeId", false);
        metadata.role = WorldGraph.Role.valueOf(string(json, "role", true));
        metadata.sourceId = string(json, "sourceId", true);
        metadata.lastSyncTime = number(json, "lastSyncTime", -1);
        if (!(json.get("generation") instanceof JsonObject generation)) {
            throw new IllegalArgumentException("Missing mirror generation");
        }
        GenerationMode mode = GenerationMode.valueOf(string(generation, "mode", true));
        String epoch = string(generation, "seedEpoch", mode == GenerationMode.SEEDED);
        metadata.generation = new Generation(mode, integer(generation, "revision", -1),
                integer(generation, "assetRevision", -1), integer(generation, "dataVersion", -1),
                integer(generation, "cleanupRevision", -1),
                string(generation, "preset", mode == GenerationMode.SEEDED),
                string(generation, "settingsDigest", mode == GenerationMode.SEEDED),
                epoch == null ? null : parseEpoch(epoch), bool(generation, "blendTerrain"));
        metadata.validate();
        return metadata;
    }

    private static UUID parseEpoch(String value) {
        UUID epoch = UUID.fromString(value);
        if (!epoch.toString().equals(value))
            throw new IllegalArgumentException("Invalid seed epoch");
        return epoch;
    }

    private static boolean bool(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null)
            return false;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Invalid metadata boolean: " + name);
        return element.getAsBoolean();
    }

    private static String string(JsonObject json, String name, boolean required) {
        JsonElement element = json.get(name);
        if (!required && (element == null || element.isJsonNull()))
            return null;
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || !validKey(element.getAsString())) {
            throw new IllegalArgumentException("Invalid metadata string: " + name);
        }
        return element.getAsString();
    }

    private static int integer(JsonObject json, String name, int defaultValue) {
        long value = number(json, name, defaultValue);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Invalid metadata integer: " + name);
        }
        return (int) value;
    }

    private static long number(JsonObject json, String name, long defaultValue) {
        JsonElement element = json.get(name);
        if (element == null)
            return defaultValue;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid metadata number: " + name);
        }
        try {
            return Long.parseLong(element.getAsString());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid metadata integer: " + name, invalid);
        }
    }

    private static boolean validKey(String key) {
        return key != null && !key.isBlank() && key.chars().noneMatch(Character::isISOControl);
    }

    private void validate() {
        var idKind = WorldLineage.kindOfId(nodeId);
        boolean mirror = kind == WorldGraph.Kind.MIRROR && idKind == WorldLineage.Kind.MIRROR && role != null;
        boolean snapshot = kind == WorldGraph.Kind.SNAPSHOT && idKind == WorldLineage.Kind.SNAPSHOT
                && role == WorldGraph.Role.SNAPSHOT;
        boolean validParent = parentNodeId == null || WorldLineage.kindOfId(parentNodeId) != null;
        if (!(mirror || snapshot) || !validParent || !validKey(sourceId) || lastSyncTime < 0
                || generation == null) {
            throw new IllegalArgumentException("Invalid mirror metadata");
        }
    }

    /** Atomically writes the validated current document. */
    public boolean save(Path worldFolder) {
        try {
            validate();
            JsonSupport.writeDocument(worldFolder.resolve(FILE_NAME), this);
            return true;
        } catch (IOException | RuntimeException failure) {
            WMLogger.warn("World metadata save failed: " + worldFolder, failure);
            return false;
        }
    }

    public boolean needsWorldgenMigration() {
        return generation.revision() < CURRENT_WORLDGEN_SCHEMA;
    }

    public boolean hasFutureWorldgenSchema() {
        return generation.revision() > CURRENT_WORLDGEN_SCHEMA;
    }

    public boolean needsWorldgenAssetRefresh(int dataVersion, int assetRevision) {
        return generation.dataVersion() < dataVersion || generation.assetRevision() < assetRevision;
    }

    public boolean hasFutureWorldgenAssets(int dataVersion, int assetRevision) {
        return generation.dataVersion() > dataVersion || generation.assetRevision() > assetRevision;
    }

    /** Marks both layers of world-generation migration as complete after a successful write. */
    public void markWorldgenCurrent(int dataVersion, int assetRevision) {
        generation = new Generation(GenerationMode.VOID, CURRENT_WORLDGEN_SCHEMA, assetRevision, dataVersion,
                LegacyVoidChunkCleanup.COMPLETED_REVISION);
        modVersion = currentModVersion();
    }

    public void markSeededGeneration(
            int dataVersion, String settingsDigest, UUID seedEpoch, boolean blendTerrain) {
        int cleanup = generation.mode() == GenerationMode.SEEDED ? generation.cleanupRevision()
                                                                 : SEEDED_TERRAIN_REPAIR_REVISION;
        generation = new Generation(GenerationMode.SEEDED, CURRENT_WORLDGEN_SCHEMA, 0, dataVersion, cleanup,
                NORMAL_GENERATION_PRESET, settingsDigest, seedEpoch, blendTerrain);
        modVersion = currentModVersion();
    }

    boolean needsSeededTerrainRepair() {
        return generation.mode() == GenerationMode.SEEDED
                && generation.cleanupRevision() < SEEDED_TERRAIN_REPAIR_REVISION;
    }

    void markSeededTerrainRepaired() {
        generation = new Generation(generation.mode(), generation.revision(), generation.assetRevision(),
                generation.dataVersion(), SEEDED_TERRAIN_REPAIR_REVISION, generation.preset(),
                generation.settingsDigest(), generation.seedEpoch(), generation.blendTerrain());
        modVersion = currentModVersion();
    }

    public boolean markSyncComplete(Path worldFolder, long latestCaptureTime) {
        lastSyncTime = Math.max(lastSyncTime, latestCaptureTime);
        modVersion = currentModVersion();
        return save(worldFolder);
    }

    private static String currentModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("worldmirror")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
