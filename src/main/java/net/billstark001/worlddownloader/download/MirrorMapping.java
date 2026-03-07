package net.billstark001.worlddownloader.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.billstark001.worlddownloader.util.WDLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistent mapping from source-ID → local mirror folder name and per-world settings.
 * <p>
 * Stored in {@code config/worlddownloader/mirrors.json}.
 * Keys are the {@code sourceId} strings produced by
 * {@link WorldMetadata#detectSourceId(net.minecraft.client.MinecraftClient)},
 * e.g. {@code "server:play.example.com"} or {@code "local:MyWorld"}.
 * Values are sanitised folder names placed inside {@code downloaded_worlds/}.
 * <p>
 * Per-world save location and conflict strategy are stored as string maps (enum names).
 * A {@code null} value means "use the global config default".
 */
public class MirrorMapping {

    /** Maps {@code sourceId} → mirror folder name. */
    public Map<String, String> entries = new HashMap<>();

    /**
     * Per-world save location override.
     * Key: {@code sourceId}; value: {@link net.billstark001.worlddownloader.config.ModConfig.SaveLocation} name.
     * Absent or {@code null} → use global config.
     */
    public Map<String, String> perWorldSaveLocation = new HashMap<>();

    /**
     * Per-world conflict strategy override.
     * Key: {@code sourceId}; value: {@link net.billstark001.worlddownloader.config.ModConfig.ConflictStrategy} name.
     * Absent or {@code null} → use global config.
     */
    public Map<String, String> perWorldConflictStrategy = new HashMap<>();

    // ── Persistence ───────────────────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Characters that are NOT safe for folder names and will be replaced with underscores. */
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._\\-]");

    private static MirrorMapping instance;

    /** Returns the singleton, loading from disk lazily. */
    public static MirrorMapping getInstance() {
        if (instance == null) {
            instance = loadOrCreate();
        }
        return instance;
    }

    private static MirrorMapping loadOrCreate() {
        Path configDir = configDir();
        try {
            Files.createDirectories(configDir);
        } catch (Exception ignored) {}

        Path file = configDir.resolve("mirrors.json");
        if (file.toFile().exists()) {
            try (Reader r = new FileReader(file.toFile())) {
                MirrorMapping m = GSON.fromJson(r, MirrorMapping.class);
                if (m != null) {
                    if (m.entries == null) m.entries = new HashMap<>();
                    if (m.perWorldSaveLocation == null) m.perWorldSaveLocation = new HashMap<>();
                    if (m.perWorldConflictStrategy == null) m.perWorldConflictStrategy = new HashMap<>();
                    return m;
                }
            } catch (Exception e) {
                WDLogger.warn("Could not load mirrors.json: " + e.getMessage());
            }
        }
        return new MirrorMapping();
    }

    /** Persists the current mapping to disk. */
    public void save() {
        Path configDir = configDir();
        try {
            Files.createDirectories(configDir);
            try (Writer w = new FileWriter(configDir.resolve("mirrors.json").toFile())) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            WDLogger.warn("Could not save mirrors.json: " + e.getMessage());
        }
    }

    // ── Query / update ────────────────────────────────────────────────────────

    /**
     * Returns the folder name assigned to {@code sourceId}, auto-generating
     * (and persisting) a new name if none has been set yet.
     */
    public String getMirrorFolderName(String sourceId) {
        String existing = entries.get(sourceId);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = generateFolderName(sourceId);
        entries.put(sourceId, generated);
        save();
        return generated;
    }

    /**
     * Explicitly assigns a folder name to a source ID (e.g. from a future UI).
     */
    public void setMirrorFolderName(String sourceId, String folderName) {
        entries.put(sourceId, folderName);
        save();
    }

    // ── Per-world save location ───────────────────────────────────────────────

    /**
     * Returns the per-world save-location override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public String getPerWorldSaveLocation(String sourceId) {
        String v = perWorldSaveLocation.get(sourceId);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Sets a per-world save-location override.
     * Pass {@code null} or an empty string to remove the override.
     */
    public void setPerWorldSaveLocation(String sourceId, String locationName) {
        if (locationName == null || locationName.isBlank()) {
            perWorldSaveLocation.remove(sourceId);
        } else {
            perWorldSaveLocation.put(sourceId, locationName);
        }
        save();
    }

    // ── Per-world conflict strategy ───────────────────────────────────────────

    /**
     * Returns the per-world conflict-strategy override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public String getPerWorldConflictStrategy(String sourceId) {
        String v = perWorldConflictStrategy.get(sourceId);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Sets a per-world conflict-strategy override.
     * Pass {@code null} or an empty string to remove the override.
     */
    public void setPerWorldConflictStrategy(String sourceId, String strategyName) {
        if (strategyName == null || strategyName.isBlank()) {
            perWorldConflictStrategy.remove(sourceId);
        } else {
            perWorldConflictStrategy.put(sourceId, strategyName);
        }
        save();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static String generateFolderName(String sourceId) {
        if (sourceId == null || sourceId.isBlank() || "unknown".equals(sourceId)) {
            return "unknown_world";
        }
        // Strip type prefix so "server:play.example.com" → "play.example.com"
        String name = sourceId;
        if (sourceId.startsWith("server:")) {
            name = sourceId.substring("server:".length());
        } else if (sourceId.startsWith("local:")) {
            name = sourceId.substring("local:".length());
        }
        // Replace dots/colons with underscores, then replace remaining unsafe chars
        name = name.replace(':', '_').replace('/', '_');
        name = UNSAFE.matcher(name).replaceAll("_");
        // Collapse multiple underscores
        name = name.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (name.isBlank()) name = "unnamed";
        if (name.length() > 64) name = name.substring(0, 64);
        return name;
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("worlddownloader");
    }
}
