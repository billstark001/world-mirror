package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistent mapping from source-ID → local mirror folder name and per-world settings.
 * <p>
 * Stored in {@code config/worldmirror/mirrors.json}.
 * Keys are the {@code sourceId} strings produced by
 * {@link WorldMetadata#detectSourceId(net.minecraft.client.Minecraft)},
 * e.g. {@code "server:play.example.com"} or {@code "local:MyWorld"}.
 * Values are sanitised folder names.
 * <p>
 * Two distinct base directories are recognised:
 * <ul>
 *   <li>{@code downloaded_worlds/} — the mod's own mirror store
 *       ({@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation#DOWNLOADED}).</li>
 *   <li>{@code saves/} — the vanilla saves directory
 *       ({@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation#SAVES}).</li>
 * </ul>
 * Resolved folder names are tracked <em>per save-location</em> (using compound keys of the
 * form {@code "sourceId\0saveLoc"}) so that changing the save-location for a world never
 * reuses a stale resolved name from the other directory.
 * <p>
 * Per-world save location and conflict strategy are stored as string maps (enum names).
 * A {@code null} value means "use the global config default".
 * <p>
 * All internal maps are {@code private}; callers must use the provided query and record methods.
 * Path previews are read-only. An export or move operation must claim the directory and then
 * record the selected name explicitly.
 */
public class MirrorMapping {

    // ── Persisted fields (Gson accesses private fields via reflection) ─────────

    /** Maps {@code sourceId} → mirror folder base name (never contains a generated suffix). */
    private Map<String, String> entries = new HashMap<>();

    /**
     * Maps a compound key {@code "sourceId\0saveLocationName"} → the collision-resolved
     * folder name that was actually used for that specific (source, location) combination.
     * Stored separately from {@code entries} so the base name is never contaminated with
     * numeric suffixes, and keyed by save-location so that switching between
     * {@code DOWNLOADED} and {@code SAVES} always triggers a fresh resolution scan in the
     * correct directory.
     */
    private Map<String, String> resolvedFolderNames = new HashMap<>();

    /**
     * Per-world save location override.
     * Key: {@code sourceId}; value: {@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation} name.
     * Absent or {@code null} → use global config.
     */
    private Map<String, String> perWorldSaveLocation = new HashMap<>();

    /**
     * Per-world conflict strategy override.
     * Key: {@code sourceId}; value: {@link io.github.billstark001.worldmirror.config.ModConfig.ConflictStrategy} name.
     * Absent or {@code null} → use global config.
     */
    private Map<String, String> perWorldConflictStrategy = new HashMap<>();

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Characters that are NOT safe for folder names and will be replaced with underscores. */
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._\\-]");
    private static final Pattern REPEATED_UNDERSCORES = Pattern.compile("_+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");
    private static final Pattern TRAILING_DOTS = Pattern.compile("[. ]+$");
    private static final Pattern INVALID_FOLDER_CHARACTERS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern RESERVED_FOLDER_NAME = Pattern.compile(
            "(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?", Pattern.CASE_INSENSITIVE);
    private static final int MAX_GENERATED_FOLDER_LENGTH = 64;

    /** Separator used inside compound map keys (not valid in sourceId or location names). */
    private static final char KEY_SEP = '\0';

    private static volatile MirrorMapping instance;
    private transient Path storageFile;
    private transient Path gameDirectory;

    public MirrorMapping() { }

    MirrorMapping(Path storageFile, Path gameDirectory) {
        this.storageFile = storageFile;
        this.gameDirectory = gameDirectory;
    }

    /** Returns the singleton, loading from disk lazily. */
    public static synchronized MirrorMapping getInstance() {
        if (instance == null) {
            instance = loadOrCreate();
        }
        return instance;
    }

    private static MirrorMapping loadOrCreate() {
        return load(configDir().resolve("mirrors.json"), null);
    }

    /** Loads a mapping without creating its directory or changing the process-wide Fabric environment. */
    static MirrorMapping load(Path file, Path gameDirectory) {
        if (file.toFile().exists()) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                MirrorMapping m = JsonSupport.fromJson(r, MirrorMapping.class);
                if (m != null) {
                    if (m.entries == null) m.entries = new HashMap<>();
                    if (m.resolvedFolderNames == null) m.resolvedFolderNames = new HashMap<>();
                    if (m.perWorldSaveLocation == null) m.perWorldSaveLocation = new HashMap<>();
                    if (m.perWorldConflictStrategy == null) m.perWorldConflictStrategy = new HashMap<>();
                    m.storageFile = file;
                    m.gameDirectory = gameDirectory;
                    return m;
                }
            } catch (Exception e) {
                WMLogger.warn("Mirror mapping load failed file=" + file, e);
            }
        }
        return new MirrorMapping(file, gameDirectory);
    }

    /** Persists the current mapping, returning false if its document could not be replaced. */
    public synchronized boolean save() {
        Path file = storageFile != null ? storageFile : configDir().resolve("mirrors.json");
        try {
            JsonSupport.writePrettyJsonAtomically(file, this);
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror mapping save failed file=" + file, e);
            return false;
        }
    }

    /** Publishes an in-memory mutation only when its replacement document was saved. */
    private boolean changeAndSave(Runnable change) {
        Map<String, String> oldEntries = entries;
        Map<String, String> oldResolved = resolvedFolderNames;
        Map<String, String> oldLocations = perWorldSaveLocation;
        Map<String, String> oldStrategies = perWorldConflictStrategy;
        entries = new HashMap<>(entries);
        resolvedFolderNames = new HashMap<>(resolvedFolderNames);
        perWorldSaveLocation = new HashMap<>(perWorldSaveLocation);
        perWorldConflictStrategy = new HashMap<>(perWorldConflictStrategy);
        boolean saved = false;
        try {
            change.run();
            saved = save();
            return saved;
        } finally {
            if (!saved) {
                entries = oldEntries;
                resolvedFolderNames = oldResolved;
                perWorldSaveLocation = oldLocations;
                perWorldConflictStrategy = oldStrategies;
            }
        }
    }

    // ── Query / update ────────────────────────────────────────────────────────

    /**
     * Returns the assigned base name, or a deterministic generated name when no
     * assignment has been persisted yet. This query never writes configuration.
     */
    public synchronized String previewBaseFolderName(String sourceId) {
        String existing = entries.get(sourceId);
        if (isUsableFolderName(existing)) {
            return existing;
        }
        return generateFolderName(sourceId);
    }

    // ── Resolved folder names (per save-location) ─────────────────────────────

    /**
     * Returns the previously persisted collision-resolved folder name for
     * {@code (sourceId, saveLocationName)}. This query deliberately performs no
     * filesystem validation; explicit write and move operations validate ownership.
     *
     * @param sourceId        the world's source identifier
     * @param saveLocationName the enum name of the save-location
     *                         ({@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation#name()})
     * @return the recorded resolved folder name, or {@code null}
     */
    public synchronized String previewResolvedFolderName(
            String sourceId, String saveLocationName) {
        String key = sourceId + KEY_SEP + saveLocationName;
        String v = resolvedFolderNames.get(key);
        return isUsableFolderName(v) ? v : null;
    }

    /**
     * Persists the collision-resolved folder name for {@code (sourceId, saveLocationName)}.
     * The value must be the <em>actual</em> folder name that was chosen (may include a
     * numeric suffix).  It is stored under a compound key that includes the save-location
     * so that different directories never share the same cached name.
     *
     * @param sourceId        the world's source identifier
     * @param saveLocationName the enum name of the save-location
     * @param resolvedName    the folder name that was chosen (relative to the location root)
     */
    public synchronized boolean recordResolvedFolderName(
            String sourceId, String saveLocationName, String resolvedName) {
        requireSourceId(sourceId);
        ModConfig.SaveLocation.valueOf(saveLocationName);
        requireFolderName(resolvedName);
        return changeAndSave(() -> {
            entries.putIfAbsent(sourceId, generateFolderName(sourceId));
            resolvedFolderNames.put(sourceId + KEY_SEP + saveLocationName, resolvedName);
        });
    }

    /** Records a per-world location and its resolved folder in one durable write. */
    public synchronized boolean recordMirrorLocation(
            String sourceId, String locationName, String resolvedName) {
        requireSourceId(sourceId);
        ModConfig.SaveLocation.valueOf(locationName);
        requireFolderName(resolvedName);
        return changeAndSave(() -> {
            entries.putIfAbsent(sourceId, generateFolderName(sourceId));
            perWorldSaveLocation.put(sourceId, locationName);
            resolvedFolderNames.entrySet().removeIf(
                    entry -> entry.getKey().startsWith(sourceId + KEY_SEP));
            resolvedFolderNames.put(sourceId + KEY_SEP + locationName, resolvedName);
        });
    }

    // ── Per-world save location ───────────────────────────────────────────────

    /**
     * Returns the per-world save-location override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public synchronized String getPerWorldSaveLocation(String sourceId) {
        String v = perWorldSaveLocation.get(sourceId);
        return (v != null && !v.isBlank()) ? v : null;
    }

    // ── Output directory resolution ─────────────────────────────────────────

    ModConfig.SaveLocation effectiveSaveLocation(String sourceId) {
        String configured = getPerWorldSaveLocation(sourceId);
        if (configured != null) {
            try {
                return ModConfig.SaveLocation.valueOf(configured);
            } catch (IllegalArgumentException ignored) {
                // Fall through to the current global default.
            }
        }
        return ModConfig.get().defaultSaveLocation;
    }

    Path previewOutputPath(String sourceId) {
        return previewOutputPath(sourceId, effectiveSaveLocation(sourceId));
    }

    Path previewOutputPath(String sourceId, ModConfig.SaveLocation saveLocation) {
        String baseName = previewBaseFolderName(sourceId);
        String resolved = previewResolvedFolderName(sourceId, saveLocation.name());
        return outputRoot(saveLocation).resolve(resolved == null ? baseName : resolved);
    }

    boolean setSaveLocation(String sourceId, ModConfig.SaveLocation saveLocation) {
        Path target = selectOutputPath(sourceId, saveLocation);
        return recordMirrorLocation(sourceId, saveLocation.name(), target.getFileName().toString());
    }

    record MoveResult(boolean success, String failureCode, Path source, Path target) { }

    /** Moves an owned directory and rolls it back if the new routing cannot be persisted. */
    synchronized MoveResult moveToLocation(String sourceId, ModConfig.SaveLocation location) {
        Path source = selectOutputPath(sourceId, effectiveSaveLocation(sourceId));
        Path target = selectOutputPath(sourceId, location);
        if (!Files.isDirectory(source)) return new MoveResult(false, "source_missing", source, target);
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            boolean saved = recordMirrorLocation(sourceId, location.name(), target.getFileName().toString());
            return new MoveResult(saved, saved ? null : "io_error", source, target);
        }
        if (Files.exists(target)) return new MoveResult(false, "target_exists", source, target);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException error) {
            WMLogger.warn("Mirror relocation failed source=" + source + " target=" + target, error);
            return new MoveResult(false, "io_error", source, target);
        }
        if (recordMirrorLocation(sourceId, location.name(), target.getFileName().toString())) {
            return new MoveResult(true, null, source, target);
        }
        try {
            Files.move(target, source);
        } catch (IOException rollback) {
            WMLogger.warn("Mirror relocation rollback failed source=" + source + " target=" + target
                    + "; mirror contents remain at target", rollback);
        }
        return new MoveResult(false, "io_error", source, target);
    }

    Path selectOutputPath(String sourceId, ModConfig.SaveLocation saveLocation) {
        Path base = outputRoot(saveLocation);
        String baseName = previewBaseFolderName(sourceId);
        String resolved = previewResolvedFolderName(sourceId, saveLocation.name());
        if (resolved != null) {
            Path reserved = base.resolve(resolved);
            if (isFolderFreeOrOwned(reserved, sourceId)) return reserved;
        }
        return resolveOutputPath(base, baseName, sourceId);
    }

    Path claimOutputDirectory(String sourceId) throws java.io.IOException {
        ModConfig.SaveLocation saveLocation = effectiveSaveLocation(sourceId);
        Path base = outputRoot(saveLocation);
        Files.createDirectories(base);

        String baseName = previewBaseFolderName(sourceId);
        Path candidate = selectOutputPath(sourceId, saveLocation);
        boolean created = false;
        while (true) {
            try {
                Files.createDirectory(candidate);
                created = true;
                break;
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                if (WorldMetadata.isOwnedBy(candidate, sourceId)) break;
            }
            candidate = selectOutputPath(sourceId, saveLocation);
        }

        String resolvedName = candidate.getFileName().toString();
        if (!recordResolvedFolderName(sourceId, saveLocation.name(), resolvedName)) {
            IOException failure = new IOException("Could not persist claimed mirror directory " + candidate);
            if (created) {
                try { Files.delete(candidate); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        if (!resolvedName.equals(baseName)) {
            WMLogger.debug("Folder name collision resolved: '"
                    + baseName + "' → '" + resolvedName + "'");
        }
        return candidate;
    }

    // ── Per-world conflict strategy ───────────────────────────────────────────

    /**
     * Returns the per-world conflict-strategy override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public synchronized String getPerWorldConflictStrategy(String sourceId) {
        String v = perWorldConflictStrategy.get(sourceId);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Sets a per-world conflict-strategy override.
     * Pass {@code null} or an empty string to remove the override.
     */
    public synchronized boolean setPerWorldConflictStrategy(String sourceId, String strategyName) {
        requireSourceId(sourceId);
        if (strategyName != null && !strategyName.isBlank()) {
            ModConfig.ConflictStrategy.valueOf(strategyName);
        }
        return changeAndSave(() -> {
            if (strategyName == null || strategyName.isBlank()) perWorldConflictStrategy.remove(sourceId);
            else perWorldConflictStrategy.put(sourceId, strategyName);
        });
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
        // Replace separators and reserved characters before trimming the base name.
        name = name.replace(':', '_').replace('/', '_');
        name = UNSAFE.matcher(name).replaceAll("_");
        // Collapse multiple underscores
        name = EDGE_UNDERSCORES.matcher(REPEATED_UNDERSCORES.matcher(name).replaceAll("_")).replaceAll("");
        return sanitiseFolderName(name, "unnamed");
    }

    /** Filename policy shared by persistent mirrors and one-shot nearby exports. */
    static String sanitiseFolderName(String name, String fallback) {
        name = INVALID_FOLDER_CHARACTERS.matcher(name).replaceAll("_").strip();
        int end = Math.min(name.length(), MAX_GENERATED_FOLDER_LENGTH);
        if (end < name.length() && end > 0 && Character.isHighSurrogate(name.charAt(end - 1))) end--;
        name = TRAILING_DOTS.matcher(name.substring(0, end)).replaceAll("");
        if (name.isBlank()) name = fallback;
        return RESERVED_FOLDER_NAME.matcher(name).matches() ? '_' + name : name;
    }

    static boolean isUsableFolderName(String name) {
        return name != null && !name.isBlank() && name.length() <= 255
                && !name.endsWith(".") && !name.endsWith(" ")
                && !INVALID_FOLDER_CHARACTERS.matcher(name).find()
                && !RESERVED_FOLDER_NAME.matcher(name).matches();
    }

    static void requireFolderName(String name) {
        if (!isUsableFolderName(name)) throw new IllegalArgumentException("Invalid mirror folder name");
    }

    private static void requireSourceId(String sourceId) {
        if (sourceId == null || sourceId.isBlank() || sourceId.indexOf(KEY_SEP) >= 0) {
            throw new IllegalArgumentException("Invalid source identifier");
        }
    }

    private Path outputRoot(ModConfig.SaveLocation saveLocation) {
        Path game = gameDirectory != null ? gameDirectory : FabricLoader.getInstance().getGameDir();
        return saveLocation == ModConfig.SaveLocation.SAVES
                ? game.resolve("saves") : game.resolve("downloaded_worlds");
    }

    private static Path resolveOutputPath(Path base, String folderName, String sourceId) {
        Path candidate = base.resolve(folderName);
        if (isFolderFreeOrOwned(candidate, sourceId)) return candidate;
        for (int suffix = 2; suffix < Integer.MAX_VALUE; suffix++) {
            candidate = base.resolve(folderName + "_" + suffix);
            if (isFolderFreeOrOwned(candidate, sourceId)) return candidate;
        }
        throw new IllegalStateException("No collision-free mirror folder name is available");
    }

    private static boolean isFolderFreeOrOwned(Path folder, String sourceId) {
        return !Files.exists(folder) || WorldMetadata.isOwnedBy(folder, sourceId);
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldmirror");
    }
}
