package net.billstark001.worlddownloader.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.billstark001.worlddownloader.util.WDLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-world metadata written to {@code <mirror>/wdl_meta.json}.
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
     * Per-chunk last-write timestamp.
     * Key format: {@code "<dim_namespace>:<dim_path>|<chunkX>,<chunkZ>"},
     * e.g. {@code "minecraft:overworld|0,0"}.
     */
    public Map<String, Long> chunkUpdateTimes = new HashMap<>();

    // ── Persistence ───────────────────────────────────────────────────────────

    private static final String FILE_NAME = "wdl_meta.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Loads existing metadata from {@code worldFolder/wdl_meta.json}, or
     * creates a fresh instance with the supplied source information.
     * Safe to call from any thread.
     */
    public static WorldMetadata loadOrCreate(Path worldFolder,
                                             String sourceId,
                                             String sourceType) {
        Path metaFile = worldFolder.resolve(FILE_NAME);
        if (metaFile.toFile().exists()) {
            try (Reader r = new FileReader(metaFile.toFile())) {
                WorldMetadata loaded = GSON.fromJson(r, WorldMetadata.class);
                if (loaded != null) {
                    if (loaded.chunkUpdateTimes == null) loaded.chunkUpdateTimes = new HashMap<>();
                    return loaded;
                }
            } catch (Exception e) {
                WDLogger.warn("Could not read wdl_meta.json, creating fresh: " + e.getMessage());
            }
        }
        WorldMetadata meta = new WorldMetadata();
        meta.modVersion = FabricLoader.getInstance()
                .getModContainer("worlddownloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        meta.sourceType = sourceType;
        meta.sourceId   = sourceId;
        return meta;
    }

    /** Writes this metadata to {@code worldFolder/wdl_meta.json}. */
    public void save(Path worldFolder) {
        try (Writer w = new FileWriter(worldFolder.resolve(FILE_NAME).toFile())) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            WDLogger.warn("Failed to save wdl_meta.json: " + e.getMessage());
        }
    }

    // ── Per-chunk time tracking ───────────────────────────────────────────────

    /**
     * Returns the last time the given chunk was written to disk (0 if never).
     */
    public long getChunkWriteTime(RegistryKey<World> dimension, ChunkPos pos) {
        return chunkUpdateTimes.getOrDefault(chunkKey(dimension, pos), 0L);
    }

    /** Records that the given chunk was written at {@code timeMs}. */
    public void setChunkWriteTime(RegistryKey<World> dimension, ChunkPos pos, long timeMs) {
        chunkUpdateTimes.put(chunkKey(dimension, pos), timeMs);
    }

    private static String chunkKey(RegistryKey<World> dim, ChunkPos pos) {
        return dim.getValue().toString() + "|" + pos.x + "," + pos.z;
    }

    // ── Convenience update ────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a {@code wdl_meta.json} exists in {@code worldFolder}
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
     * Load-or-create, stamp all written chunks with the current time,
     * update {@code lastSyncTime}, then save.
     */
    public static void update(Path worldFolder,
                              String sourceId,
                              String sourceType,
                              Map<RegistryKey<World>, Set<ChunkPos>> writtenByDim) {
        WorldMetadata meta = loadOrCreate(worldFolder, sourceId, sourceType);
        long now = System.currentTimeMillis();
        meta.lastSyncTime = now;
        meta.modVersion = FabricLoader.getInstance()
                .getModContainer("worlddownloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        for (Map.Entry<RegistryKey<World>, Set<ChunkPos>> dimEntry : writtenByDim.entrySet()) {
            for (ChunkPos pos : dimEntry.getValue()) {
                meta.setChunkWriteTime(dimEntry.getKey(), pos, now);
            }
        }
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
