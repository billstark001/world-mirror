package net.billstark001.worlddownloader.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.billstark001.worlddownloader.util.WDLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;

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
     * Per-chunk last-write timestamp.  Key is {@code "chunkX,chunkZ"};
     * value is System.currentTimeMillis() at the time of writing.
     */
    public Map<String, Long> chunkUpdateTimes = new HashMap<>();

    // ── Persistence ───────────────────────────────────────────────────────────

    private static final String FILE_NAME = "wdl_meta.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Loads metadata from {@code worldFolder/wdl_meta.json}, or creates a
     * fresh instance populated from the current Minecraft client state.
     */
    public static WorldMetadata loadOrCreate(Path worldFolder, MinecraftClient client) {
        Path metaFile = worldFolder.resolve(FILE_NAME);
        if (metaFile.toFile().exists()) {
            try (Reader r = new FileReader(metaFile.toFile())) {
                WorldMetadata loaded = GSON.fromJson(r, WorldMetadata.class);
                if (loaded != null) {
                    if (loaded.chunkUpdateTimes == null) {
                        loaded.chunkUpdateTimes = new HashMap<>();
                    }
                    return loaded;
                }
            } catch (Exception e) {
                WDLogger.warn("Could not read wdl_meta.json, creating fresh: " + e.getMessage());
            }
        }

        // Build a fresh metadata object from the running client
        WorldMetadata meta = new WorldMetadata();
        meta.modVersion = FabricLoader.getInstance()
                .getModContainer("worlddownloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        meta.sourceType = detectSourceType(client);
        meta.sourceId   = detectSourceId(client);
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

    /**
     * Convenience method: load/create, stamp the given chunks as written right
     * now, update {@code lastSyncTime}, then save.
     */
    public static void update(Path worldFolder, MinecraftClient client,
                              Set<ChunkPos> writtenChunks) {
        WorldMetadata meta = loadOrCreate(worldFolder, client);
        long now = System.currentTimeMillis();
        meta.lastSyncTime = now;
        for (ChunkPos pos : writtenChunks) {
            meta.chunkUpdateTimes.put(pos.x + "," + pos.z, now);
        }
        // Keep mod version up-to-date
        meta.modVersion = FabricLoader.getInstance()
                .getModContainer("worlddownloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        meta.save(worldFolder);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String detectSourceType(MinecraftClient client) {
        try {
            if (client.getServer() != null) return "singleplayer";
        } catch (Exception ignored) {}
        return "server";
    }

    private static String detectSourceId(MinecraftClient client) {
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
