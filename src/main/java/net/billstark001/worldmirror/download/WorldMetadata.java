package net.billstark001.worldmirror.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Map;

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

    // ── JSON fields ───────────────────────────────────────────────────────────

    /** Mod version that created / last updated this mirror. */
    public String modVersion = "unknown";

    /** {@code "singleplayer"} or {@code "server"}. */
    public String sourceType = "unknown";

    /** Server address or local world folder name. */
    public String sourceId = "unknown";

    /** Unix millis of the most recent completed sync. */
    public long lastSyncTime = 0;

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

    private static final String FILE_NAME = "worldmirror_meta.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Loads existing metadata from {@code worldFolder/worldmirror_meta.json}, or
     * creates a fresh instance with the supplied source information.
     * Safe to call from any thread.
     * <p>
     * If the loaded file contains a legacy {@code chunkUpdateTimes} field, that data
     * is preserved in the returned instance so the caller can migrate it to the
     * SQLite database via {@link #migrateAndCleanChunkTimes(ChunkDatabase, Path)}.
     */
    public static WorldMetadata loadOrCreate(Path worldFolder,
                                             String sourceId,
                                             String sourceType) {
        Path metaFile = worldFolder.resolve(FILE_NAME);
        if (metaFile.toFile().exists()) {
            try (Reader r = new FileReader(metaFile.toFile())) {
                WorldMetadata loaded = GSON.fromJson(r, WorldMetadata.class);
                if (loaded != null) {
                    // chunkUpdateTimes may be non-null if this is an old JSON file;
                    // keep it so the caller can trigger migration if needed.
                    return loaded;
                }
            } catch (Exception e) {
                WMLogger.warn("Could not read worldmirror_meta.json, creating fresh: " + e.getMessage());
            }
        }
        WorldMetadata meta = new WorldMetadata();
        meta.modVersion = FabricLoader.getInstance()
                .getModContainer("worldmirror")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        meta.sourceType = sourceType;
        meta.sourceId   = sourceId;
        return meta;
    }

    /** Writes this metadata to {@code worldFolder/worldmirror_meta.json}. */
    public void save(Path worldFolder) {
        try (Writer w = new FileWriter(worldFolder.resolve(FILE_NAME).toFile())) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            WMLogger.warn("Failed to save worldmirror_meta.json: " + e.getMessage());
        }
    }

    // ── Per-chunk time tracking (legacy — replaced by ChunkDatabase) ─────────

    /**
     * If this instance was loaded from an old JSON file that contained a non-empty
     * {@code chunkUpdateTimes} map, this method migrates that data into {@code db}
     * and then clears the field so it is not written back to JSON.
     * <p>
     * Safe to call even if migration has already been performed (idempotent).
     *
     * @param db          open {@link ChunkDatabase} to migrate into
     * @param worldFolder mirror-world root (used to re-save cleaned JSON)
     */
    @SuppressWarnings("deprecation")
    public void migrateAndCleanChunkTimes(ChunkDatabase db, Path worldFolder) {
        if (chunkUpdateTimes != null && !chunkUpdateTimes.isEmpty()) {
            db.migrateFromChunkUpdateTimes(chunkUpdateTimes);
        }
        if (chunkUpdateTimes != null) {
            chunkUpdateTimes = null; // null → GSON skips field on next save
            save(worldFolder);      // write clean JSON without chunkUpdateTimes
        }
    }

    // ── Convenience update ────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a {@code worldmirror_meta.json} exists in {@code worldFolder}
     * and its {@code sourceId} field matches the given value.
     * Returns {@code false} if the file does not exist or cannot be read.
     * Safe to call from any thread.
     */
    public static boolean isOwnedBy(Path worldFolder, String sourceId) {
        Path metaFile = worldFolder.resolve(FILE_NAME);
        if (!metaFile.toFile().exists()) return false;
        try (Reader r = new FileReader(metaFile.toFile())) {
            WorldMetadata meta = GSON.fromJson(r, WorldMetadata.class);
            return meta != null && sourceId.equals(meta.sourceId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Load-or-create, update {@code lastSyncTime} and {@code modVersion}, then save.
     * <p>
     * Chunk write timestamps are no longer stored in JSON; they are managed by
     * {@link ChunkDatabase} instead.
     */
    public static void update(Path worldFolder,
                              String sourceId,
                              String sourceType) {
        WorldMetadata meta = loadOrCreate(worldFolder, sourceId, sourceType);
        long now = System.currentTimeMillis();
        meta.lastSyncTime = now;
        meta.modVersion = FabricLoader.getInstance()
                .getModContainer("worldmirror")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        meta.chunkUpdateTimes = null; // ensure legacy field is not written back
        meta.save(worldFolder);
    }

    // ── Source detection (call on game thread) ────────────────────────────────

    public static String detectSourceType(MinecraftClient client) {
        try {
            if (client.getServer() != null) return "singleplayer";
        } catch (Exception ignored) {}
        return "server";
    }

    public static String detectSourceId(MinecraftClient client) {
        try {
            if (client.getCurrentServerEntry() != null) {
                return "server:" + client.getCurrentServerEntry().address;
            }
        } catch (Exception ignored) {}
        try {
            if (client.getServer() != null) {
                return "local:" + client.getServer().getSaveProperties().getLevelName();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}
