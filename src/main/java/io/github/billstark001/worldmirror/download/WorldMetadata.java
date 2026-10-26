package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.JsonSupport;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-world metadata written to {@code <mirror>/worldmirror_meta.json}.
 * <p>
 * Survives being copied into {@code saves/} so that future sync sessions can
 * resume from where they left off.
 * <p>
 * This class is designed to be read/written from a background thread —
 * it does not access any Minecraft game-thread objects after construction.
 */
public class WorldMetadata {

    /** Current semantic format of the generated mirror-world dimensions. */
    public static final int CURRENT_WORLDGEN_SCHEMA = MirrorFormat.WORLDGEN_SCHEMA;
    public static final int CURRENT_METADATA_SCHEMA = MirrorFormat.METADATA_SCHEMA;
    /** Cleanup revision independent from {@link #CURRENT_WORLDGEN_SCHEMA}. */
    public static final int CURRENT_VOID_CHUNK_CLEANUP_REVISION = 1;
    public static final String FORMAT = MirrorFormat.FORMAT;

    // ── JSON fields ───────────────────────────────────────────────────────────

    /** Mod version that created / last updated this mirror. */
    public String modVersion = "unknown";

    /** Stable marker for read-only mirror-world discovery. */
    public String format = FORMAT;

    /** Format of this metadata document, independent from world-generation schema. */
    public int metadataSchema = CURRENT_METADATA_SCHEMA;

    /** Whether this is a synchronized mirror or a standalone nearby export. */
    public String mirrorKind = "synchronized";

    /** Stable save identity.  Unlike a folder name, it survives copies and moves. */
    public String mirrorId;

    /** Optional identity of the mirror this save was exported from. */
    public String parentMirrorId;

    /** {@code "singleplayer"} or {@code "server"}. */
    public String sourceType = "unknown";

    /** Server address or local world folder name. */
    public String sourceId = "unknown";

    /** New writes use the newest durably saved chunk capture time; old values are preserved. */
    public long lastSyncTime = 0;

    /**
     * Semantic world-generation schema.  Gson assigns {@code 0} when this field
     * is absent, which deliberately treats all pre-schema mirrors as migratable.
     */
    public int worldgenSchema = 0;

    /** Revision of the vanilla-readable world data pack embedded in the save. */
    public int worldgenAssetRevision = 0;

    /** Minecraft data version used when the embedded assets were last refreshed. */
    public int worldgenAssetDataVersion = 0;

    /**
     * Completion marker for removing only the all-air {@code minecraft:the_void}
     * chunks made by the pre-schema generator.  It intentionally does not bump
     * {@link #worldgenSchema}: schema 1 remains the only released schema.
     */
    public int legacyVoidChunkCleanupRevision = 0;

    /**
     * Legacy per-chunk last-write timestamp map — kept for reading old JSON files
     * during migration to {@code data/world_mirror.sqlite}.
     * <p>
     * This field is <em>not</em> written back to JSON after migration (it is set to
     * {@code null}, and GSON skips null fields by default).  New code should use
     * {@link ChunkDatabase} instead.
     *
     * @deprecated Use {@link ChunkDatabase} for chunk timestamp tracking.
     */
    @Deprecated
    public Map<String, Long> chunkUpdateTimes = null;

    // ── Persistence ───────────────────────────────────────────────────────────

    public static final String FILE_NAME = MirrorFormat.METADATA_FILE;

    /** Creates metadata for a newly-created mirror world. */
    public static WorldMetadata create(String sourceId, String sourceType, String mirrorKind) {
        WorldMetadata meta = new WorldMetadata();
        meta.modVersion = currentModVersion();
        meta.sourceType = sourceType;
        meta.sourceId = sourceId;
        meta.mirrorKind = mirrorKind;
        meta.ensureMirrorId();
        return meta;
    }

    /** Reads metadata without creating or modifying a file. */
    public static Optional<WorldMetadata> loadIfPresent(Path worldFolder) {
        Path file = worldFolder.resolve(FILE_NAME);
        var document = JsonSupport.readDocument(file, WorldMetadata.class);
        if (document.failure() != null) {
            WMLogger.warnRateLimited("metadata-read-" + worldFolder, 30_000L,
                    "World metadata unavailable: " + file, document.failure());
        }
        return Optional.ofNullable(document.value());
    }

    /** Atomically writes the metadata, returning false if the document could not be replaced. */
    public boolean save(Path worldFolder) {
        ensureMirrorId();
        Path metadataFile = worldFolder.resolve(FILE_NAME);
        try {
            JsonSupport.writeDocument(metadataFile, this);
            return true;
        } catch (Exception e) {
            WMLogger.warn("World metadata save failed file=" + metadataFile, e);
            return false;
        }
    }

    // ── Per-chunk time tracking (legacy — replaced by ChunkDatabase) ─────────

    /**
     * If this instance was loaded from an old JSON file that contained a non-empty
     * {@code chunkUpdateTimes} map, this method migrates that data into {@code db}
     * and then clears the field so it is not written back to JSON.
     * <p>
     * Safe to call even if migration has already been performed (idempotent).
     * Failed database or metadata writes retain the legacy field for retry.
     *
     * @param db          open {@link ChunkDatabase} to migrate into
     * @param worldFolder mirror-world root (used to re-save cleaned JSON)
     * @return true if migration and removal of the legacy field both succeeded
     */
    @SuppressWarnings("deprecation")
    public boolean migrateAndCleanChunkTimes(ChunkDatabase db, Path worldFolder) {
        if (chunkUpdateTimes == null) return true;
        Map<String, Long> legacyTimes = chunkUpdateTimes;
        if (!legacyTimes.isEmpty() && !db.migrateFromChunkUpdateTimes(legacyTimes)) return false;
        chunkUpdateTimes = null; // null → GSON skips field on next save
        if (save(worldFolder)) return true;
        chunkUpdateTimes = legacyTimes;
        return false;
    }

    /** Whether the dimension generator itself must be replaced. */
    public boolean needsWorldgenMigration() {
        return worldgenSchema < CURRENT_WORLDGEN_SCHEMA;
    }

    /** Assigns an identity to legacy metadata the next time it is written. */
    public void ensureMirrorId() {
        if (mirrorId == null || mirrorId.isBlank()) mirrorId = UUID.randomUUID().toString();
    }

    /** True when this save was written by a newer world-generation schema. */
    public boolean hasFutureWorldgenSchema() {
        return worldgenSchema > CURRENT_WORLDGEN_SCHEMA;
    }

    /** Whether the embedded vanilla data pack must be refreshed for this game version. */
    public boolean needsWorldgenAssetRefresh(int dataVersion, int assetRevision) {
        return worldgenAssetDataVersion < dataVersion || worldgenAssetRevision < assetRevision;
    }

    /** True when refreshing assets would downgrade a newer mirror save. */
    public boolean hasFutureWorldgenAssets(int dataVersion, int assetRevision) {
        return worldgenAssetDataVersion > dataVersion || worldgenAssetRevision > assetRevision;
    }

    /** Whether an old, blank void chunk cleanup has not yet been performed. */
    public boolean needsLegacyVoidChunkCleanup() {
        return legacyVoidChunkCleanupRevision < CURRENT_VOID_CHUNK_CLEANUP_REVISION;
    }

    /** Marks both layers of world-generation migration as complete after a successful write. */
    public void markWorldgenCurrent(int dataVersion, int assetRevision) {
        worldgenSchema = CURRENT_WORLDGEN_SCHEMA;
        worldgenAssetDataVersion = dataVersion;
        worldgenAssetRevision = assetRevision;
        legacyVoidChunkCleanupRevision = CURRENT_VOID_CHUNK_CLEANUP_REVISION;
        modVersion = currentModVersion();
    }

    // ── Convenience update ────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a {@code worldmirror_meta.json} exists in {@code worldFolder}
     * and its format marker and {@code sourceId} field match this writer.
     * Returns {@code false} if the file does not exist or cannot be read.
     * Safe to call from any thread.
     */
    public static boolean isOwnedBy(Path worldFolder, String sourceId) {
        return loadIfPresent(worldFolder)
                .map(meta -> FORMAT.equals(meta.format) && sourceId != null && sourceId.equals(meta.sourceId))
                .orElse(false);
    }

    /** Records the newest durably saved capture time without rewriting older metadata values. */
    public boolean markSyncComplete(Path worldFolder, long latestCaptureTime) {
        if (latestCaptureTime > 0L) {
            lastSyncTime = Math.max(lastSyncTime, latestCaptureTime);
        }
        modVersion = currentModVersion();
        return save(worldFolder);
    }

    // ── Source detection (call on game thread) ────────────────────────────────

    public static String detectSourceType(Minecraft client) {
        try {
            if (client.getSingleplayerServer() != null) return "singleplayer";
        } catch (Exception ignored) {}
        return "server";
    }

    public static String detectSourceId(Minecraft client) {
        try {
            if (client.getCurrentServer() != null) {
                return "server:" + client.getCurrentServer().ip;
            }
        } catch (Exception ignored) {}
        try {
            if (client.getSingleplayerServer() != null) {
                return "local:" + client.getSingleplayerServer().getWorldData().getLevelName();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static String currentModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("worldmirror")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
