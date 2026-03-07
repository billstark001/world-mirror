package net.billstark001.worlddownloader.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;

/**
 * Persistent mod configuration stored in config/worlddownloader.json.
 */
public class ModConfig {

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum SaveLocation {
        /** Default: game-dir/downloaded_world */
        DOWNLOADED,
        /** Directly in game-dir/saves/<name> so it is immediately playable */
        SAVES
    }

    public enum LogLevel {
        DEBUG, INFO, WARNING
    }

    public enum ConflictStrategy {
        /** Always replace local chunk with server version. */
        OVERWRITE,
        /** Keep local chunk, discard incoming server data. */
        IGNORE,
        /** Queue for manual user decision; falls back to IGNORE until UI is available. */
        MANUAL
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    public SaveLocation defaultSaveLocation = SaveLocation.DOWNLOADED;
    /** How often (seconds) the periodic sync fires while downloading is active. */
    public int syncIntervalSeconds = 10;
    public LogLevel logLevel = LogLevel.INFO;
    public ConflictStrategy defaultConflictStrategy = ConflictStrategy.OVERWRITE;

    // ── Singleton + persistence ───────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "worlddownloader.json";

    private static ModConfig instance;

    /** Returns the loaded singleton, loading it from disk if needed. */
    public static ModConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Loads (or reloads) the config from disk. */
    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        if (file.toFile().exists()) {
            try (Reader r = new FileReader(file.toFile())) {
                ModConfig loaded = GSON.fromJson(r, ModConfig.class);
                instance = (loaded != null) ? loaded : new ModConfig();
            } catch (Exception e) {
                instance = new ModConfig();
            }
        } else {
            instance = new ModConfig();
        }
    }

    /** Persists the current config to disk. */
    public void save() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        try (Writer w = new FileWriter(file.toFile())) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            // Not critical — ignore
        }
    }
}
