package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.FileProof;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
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

    /** The persisted field names stay stable; runtime readers use one immutable, durably published view. */
    private record Routing(Map<String, String> entries, Map<String, String> resolvedFolderNames,
                           Map<String, String> perWorldSaveLocation, Map<String, String> perWorldConflictStrategy) {
        Routing {
            entries = freeze(entries);
            resolvedFolderNames = freeze(resolvedFolderNames);
            perWorldSaveLocation = freeze(perWorldSaveLocation);
            perWorldConflictStrategy = freeze(perWorldConflictStrategy);
        }

        private static Map<String, String> freeze(Map<String, String> source) {
            // Old documents use null maps and null values to mean defaults.
            return source == null ? Map.of() : java.util.Collections.unmodifiableMap(new HashMap<>(source));
        }
    }

    private volatile Routing routing = new Routing(null, null, null, null);

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
    private transient volatile boolean readable = true;
    private transient FileProof.Version storedStamp;
    private final transient java.util.concurrent.locks.ReentrantLock mutationLock = new java.util.concurrent.locks.ReentrantLock();

    public MirrorMapping() { }

    public boolean isAvailable() { return readable; }

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
        try { io.github.billstark001.worldmirror.util.FileTransaction.recover(file.toAbsolutePath().getParent()); }
        catch (Exception recoveryFailure) {
            OperationFailures.report("routing", "mapping_recovery", file, recoveryFailure);
            MirrorMapping blocked = new MirrorMapping(file, gameDirectory);
            blocked.readable = false;
            return blocked;
        }
        FileProof.Version observed;
        try { observed = stamp(file); }
        catch (IOException failure) {
            OperationFailures.report("routing", "mapping_read", file, failure);
            MirrorMapping blocked = new MirrorMapping(file, gameDirectory);
            blocked.readable = false;
            return blocked;
        }
        var document = JsonSupport.readDocument(file, Routing.class);
        if (document.state() == JsonSupport.ReadState.VALID) {
            MirrorMapping mapping = new MirrorMapping(file, gameDirectory);
            mapping.routing = document.value();
            mapping.storedStamp = observed;
            try {
                if (!java.util.Objects.equals(observed, stamp(file))) throw new IOException("Routing changed while loading");
                for (String value : mapping.routing.perWorldConflictStrategy().values()) {
                    if (value != null && !value.isBlank()) ModConfig.ConflictStrategy.valueOf(value);
                }
                for (String value : mapping.routing.perWorldSaveLocation().values()) {
                    if (value != null && !value.isBlank()) ModConfig.SaveLocation.valueOf(value);
                }
                return mapping;
            } catch (IOException | RuntimeException invalid) {
                OperationFailures.report("routing", "mapping_read", file, invalid);
            }
        } else if (document.failure() != null) {
            OperationFailures.report("routing", "mapping_read", file, document.failure());
        }
        MirrorMapping blocked = new MirrorMapping(file, gameDirectory);
        blocked.readable = document.state() == JsonSupport.ReadState.MISSING;
        return blocked;
    }

    /** Persists the current mapping, returning false if its document could not be replaced. */
    public boolean save() {
        mutationLock.lock();
        try { return persist(routing, false); }
        finally { mutationLock.unlock(); }
    }

    /** Publishes an in-memory mutation only when its replacement document was saved. */
    private boolean changeAndSave(java.util.function.UnaryOperator<Routing> change) {
        mutationLock.lock();
        try { return persist(change.apply(routing), true); }
        finally { mutationLock.unlock(); }
    }

    private boolean persist(Routing next, boolean skipUnchanged) {
        if (!readable) return false;
        Path file = storageFile != null ? storageFile : configDir().resolve("mirrors.json");
        try {
            if (!java.util.Objects.equals(storedStamp, stamp(file))) {
                throw new IOException("Routing changed outside this mapping; reload before writing: " + file);
            }
            if (skipUnchanged && next.equals(routing)) return true;
            storedStamp = JsonSupport.compareAndWriteDocument(file, next, storedStamp);
        } catch (Exception failure) {
            WMLogger.warn("Mirror mapping save failed file=" + file, failure);
            return false;
        }
        // The change token was captured under publication's lease. Never capture
        // another writer's later token and attach it to this older routing view.
        routing = next;
        return true;
    }

    private static FileProof.Version stamp(Path file) throws IOException {
        try {
            return FileProof.version(file);
        } catch (java.nio.file.NoSuchFileException missing) { return null; }
    }

    private static Map<String, String> updated(Map<String, String> source, String key, String value) {
        Map<String, String> next = new HashMap<>(source);
        if (value == null) next.remove(key); else next.put(key, value);
        return next;
    }

    // ── Query / update ────────────────────────────────────────────────────────

    /**
     * Returns the assigned base name, or a deterministic generated name when no
     * assignment has been persisted yet. This query never writes configuration.
     */
    public String previewBaseFolderName(String sourceId) {
        String existing = routing.entries().get(sourceId);
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
    public String previewResolvedFolderName(
            String sourceId, String saveLocationName) {
        String key = sourceId + KEY_SEP + saveLocationName;
        String v = routing.resolvedFolderNames().get(key);
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
    public boolean recordResolvedFolderName(
            String sourceId, String saveLocationName, String resolvedName) {
        requireSourceId(sourceId);
        ModConfig.SaveLocation.valueOf(saveLocationName);
        requireFolderName(resolvedName);
        return changeAndSave(current -> {
            String key = sourceId + KEY_SEP + saveLocationName;
            if (current.entries().get(sourceId) != null && resolvedName.equals(current.resolvedFolderNames().get(key))) return current;
            return new Routing(current.entries().get(sourceId) == null ? updated(current.entries(), sourceId, generateFolderName(sourceId)) : current.entries(),
                    updated(current.resolvedFolderNames(), key, resolvedName), current.perWorldSaveLocation(), current.perWorldConflictStrategy());
        });
    }

    /** Records a per-world location and its resolved folder in one durable write. */
    public boolean recordMirrorLocation(
            String sourceId, String locationName, String resolvedName) {
        requireSourceId(sourceId);
        ModConfig.SaveLocation.valueOf(locationName);
        requireFolderName(resolvedName);
        return changeAndSave(current -> {
            String key = sourceId + KEY_SEP + locationName;
            if (current.entries().get(sourceId) != null && resolvedName.equals(current.resolvedFolderNames().get(key))
                    && locationName.equals(current.perWorldSaveLocation().get(sourceId))) return current;
            return new Routing(current.entries().get(sourceId) == null ? updated(current.entries(), sourceId, generateFolderName(sourceId)) : current.entries(),
                    updated(current.resolvedFolderNames(), key, resolvedName), updated(current.perWorldSaveLocation(), sourceId, locationName), current.perWorldConflictStrategy());
        });
    }

    // ── Per-world save location ───────────────────────────────────────────────

    /**
     * Returns the per-world save-location override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public String getPerWorldSaveLocation(String sourceId) {
        String v = routing.perWorldSaveLocation().get(sourceId);
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
        Routing current = routing;
        String configured = current.perWorldSaveLocation().get(sourceId);
        ModConfig.SaveLocation location = configured == null || configured.isBlank()
                ? ModConfig.get().defaultSaveLocation : ModConfig.SaveLocation.valueOf(configured);
        return previewOutputPath(sourceId, location, current);
    }

    Path previewOutputPath(String sourceId, ModConfig.SaveLocation saveLocation) {
        return previewOutputPath(sourceId, saveLocation, routing);
    }

    private Path previewOutputPath(String sourceId, ModConfig.SaveLocation saveLocation, Routing current) {
        String baseName = current.entries().get(sourceId);
        if (!isUsableFolderName(baseName)) baseName = generateFolderName(sourceId);
        String resolved = current.resolvedFolderNames().get(sourceId + KEY_SEP + saveLocation.name());
        if (!isUsableFolderName(resolved)) resolved = null;
        return outputRoot(saveLocation).resolve(resolved == null ? baseName : resolved);
    }

    boolean setSaveLocation(String sourceId, ModConfig.SaveLocation saveLocation) {
        Path target = selectOutputPath(sourceId, saveLocation);
        return recordMirrorLocation(sourceId, saveLocation.name(), target.getFileName().toString());
    }

    record MoveResult(boolean success, String failureCode, Path source, Path target) { }

    /** Moves an owned directory and rolls it back if the new routing cannot be persisted. */
    MoveResult moveToLocation(String sourceId, ModConfig.SaveLocation location) {
        if (!readable) return new MoveResult(false, "io_error", null, null);
        mutationLock.lock();
        try {
            try { recoverRelocations(sourceId); }
            catch (Exception failure) {
                OperationFailures.report(sourceId, "relocation_recovery", null, failure);
                return new MoveResult(false, "io_error", null, null);
            }
            Path source = selectOutputPath(sourceId, effectiveSaveLocation(sourceId));
            Path target = selectOutputPath(sourceId, location);
            if (!Files.isDirectory(source)) return new MoveResult(false, "source_missing", source, target);
            if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                boolean saved = recordMirrorLocation(sourceId, location.name(), target.getFileName().toString());
                return new MoveResult(saved, saved ? null : "io_error", source, target);
            }
            try {
                if (Files.exists(target) && !WorldRelocation.isEmptyReservation(target, sourceId)) {
                    return new MoveResult(false, "target_exists", source, target);
                }
                WorldRelocation.move(source, target, sourceId,
                        () -> recordMirrorLocation(sourceId, location.name(), target.getFileName().toString()));
                return new MoveResult(true, null, source, target);
            } catch (Exception error) {
                OperationFailures.report(sourceId, "relocation", source, error);
                return new MoveResult(false, "io_error", source, target);
            }


        } finally { mutationLock.unlock(); }
    }

    private void recoverRelocations(String sourceId) throws IOException {
        Path routed = previewOutputPath(sourceId).toAbsolutePath().normalize();
        var allowedRoots = java.util.Set.of(outputRoot(ModConfig.SaveLocation.SAVES).toAbsolutePath().normalize(),
                outputRoot(ModConfig.SaveLocation.DOWNLOADED).toAbsolutePath().normalize());
        for (ModConfig.SaveLocation location : ModConfig.SaveLocation.values()) {
            String resolved = previewResolvedFolderName(sourceId, location.name());
            Path candidate = outputRoot(location).resolve(resolved == null ? previewBaseFolderName(sourceId) : resolved)
                    .toAbsolutePath().normalize();
            WorldRelocation.recover(candidate, sourceId, routed, allowedRoots);
        }
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
        mutationLock.lock();
        try {
            if (!readable) throw new IOException("Routing document is unreadable; repair it before creating a mirror");
            recoverRelocations(sourceId);
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
                    try {
                        MirrorClaim.create(candidate, sourceId);
                    } catch (IOException failure) {
                        try { Files.deleteIfExists(candidate.resolve(MirrorClaim.FILE_NAME)); Files.delete(candidate); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                        throw failure;
                    }
                    break;
                } catch (java.nio.file.FileAlreadyExistsException raced) {
                    if (isOwnedOrReserved(candidate, sourceId)) break;
                }
                candidate = selectOutputPath(sourceId, saveLocation);
            }

            String resolvedName = candidate.getFileName().toString();
            if (!recordResolvedFolderName(sourceId, saveLocation.name(), resolvedName)) {
                IOException failure = new IOException("Could not persist claimed mirror directory " + candidate);
                if (created) {
                    try { Files.deleteIfExists(candidate.resolve(MirrorClaim.FILE_NAME)); Files.delete(candidate); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
            if (!resolvedName.equals(baseName)) {
                WMLogger.debug("Folder name collision resolved: '"
                        + baseName + "' → '" + resolvedName + "'");
            }
            return candidate;


        } finally { mutationLock.unlock(); }
    }

    // ── Per-world conflict strategy ───────────────────────────────────────────

    /**
     * Returns the per-world conflict-strategy override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public String getPerWorldConflictStrategy(String sourceId) {
        String v = routing.perWorldConflictStrategy().get(sourceId);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Sets a per-world conflict-strategy override.
     * Pass {@code null} or an empty string to remove the override.
     */
    public boolean setPerWorldConflictStrategy(String sourceId, String strategyName) {
        requireSourceId(sourceId);
        if (strategyName != null && !strategyName.isBlank()) {
            ModConfig.ConflictStrategy.valueOf(strategyName);
        }
        return changeAndSave(current -> {
            String value = strategyName == null || strategyName.isBlank() ? null : strategyName;
            if (java.util.Objects.equals(value, current.perWorldConflictStrategy().get(sourceId))) return current;
            return new Routing(current.entries(), current.resolvedFolderNames(), current.perWorldSaveLocation(),
                    updated(current.perWorldConflictStrategy(), sourceId, value));
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
                && !name.equals(".") && !name.equals("..")
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

    private static boolean isOwnedOrReserved(Path folder, String sourceId) {
        if (!Files.notExists(folder.resolve(WorldMetadata.FILE_NAME))) return WorldMetadata.isOwnedBy(folder, sourceId);
        return MirrorClaim.ownedBy(folder, sourceId);
    }

    private static boolean isFolderFreeOrOwned(Path folder, String sourceId) {
        return Files.notExists(folder) || isOwnedOrReserved(folder, sourceId);
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldmirror");
    }
}
