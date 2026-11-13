package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.MirrorFolders;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/** The unversioned v0 routing document used for migration and interrupted move recovery. */
public record LegacyRouting(Map<String, Entry> entries) {
    public static final String FILE_NAME = "mirrors.json";

    public LegacyRouting {
        entries = Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    public Entry entryFor(String sourceId) {
        Entry existing = entries.get(sourceId);
        return existing == null ? new Entry(sourceId, null, Map.of(), null, null) : existing;
    }

    public Location relocationWitness(String sourceId, Supplier<ModConfig.SaveLocation> defaultLocation) {
        Entry entry = entryFor(sourceId);
        ModConfig.SaveLocation save =
                entry.saveLocation() == null ? defaultLocation.get() : entry.saveLocation();
        Location location = entry.location(() -> save);
        if (location != null)
            return location;
        return new Location(Root.valueOf(save.name()),
                MirrorFolders.generate(sourceId.replaceFirst("^(server|local):", "")));
    }

    private static FileProof.Version stamp(Path file) throws IOException {
        try {
            return FileProof.version(file);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    public record Entry(String sourceId, String preferredFolderName,
            Map<ModConfig.SaveLocation, String> resolvedFolderNames, ModConfig.SaveLocation saveLocation,
            ModConfig.ConflictStrategy conflictStrategy) {
        public Location location(Supplier<ModConfig.SaveLocation> defaultLocation) {
            ModConfig.SaveLocation save = saveLocation == null ? defaultLocation.get() : saveLocation;
            String name = resolvedFolderNames.getOrDefault(save, preferredFolderName);
            return MirrorFolders.isUsableFolderName(name) ? new Location(Root.valueOf(save.name()), name)
                                                          : null;
        }

        public Entry {
            if (sourceId == null || sourceId.isBlank()
                    || sourceId.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid legacy source");
            }
            Map<ModConfig.SaveLocation, String> resolved = new EnumMap<>(ModConfig.SaveLocation.class);
            resolvedFolderNames.forEach((root, name) -> {
                if (!MirrorFolders.isUsableFolderName(name))
                    throw new IllegalArgumentException("Invalid legacy folder name");
                resolved.put(root, name);
            });
            if (preferredFolderName != null && !MirrorFolders.isUsableFolderName(preferredFolderName)) {
                throw new IllegalArgumentException("Invalid legacy preferred folder name");
            }
            resolvedFolderNames = Collections.unmodifiableMap(resolved);
        }
    }

    /** Reads the previous routing file only for the explicit migration. */
    public static LegacyRouting read(Path file) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        FileProof.Version observed = stamp(path);
        JsonSupport.Document<LegacyDocument> document = JsonSupport.readDocument(path, LegacyDocument.class);
        if (!Objects.equals(observed, stamp(path)))
            throw new IOException("Legacy mirror routing changed while loading: " + path);
        return switch (document.state()) {
            case MISSING -> new LegacyRouting(Map.of());
            case VALID -> {
                try {
                    yield document.value().toImport();
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Cannot import legacy mirror routing: " + path, invalid);
                }
            }
            case INVALID, UNAVAILABLE ->
                throw new IOException("Cannot import legacy mirror routing: " + path, document.failure());
        };
    }

    private record LegacyDocument(Map<String, String> entries, Map<String, String> resolvedFolderNames,
            Map<String, String> perWorldSaveLocation, Map<String, String> perWorldConflictStrategy) {
        private LegacyDocument {
            entries = orEmpty(entries);
            resolvedFolderNames = orEmpty(resolvedFolderNames);
            perWorldSaveLocation = orEmpty(perWorldSaveLocation);
            perWorldConflictStrategy = orEmpty(perWorldConflictStrategy);
        }

        private LegacyRouting toImport() {
            Set<String> sources = new TreeSet<>(entries.keySet());
            sources.addAll(perWorldSaveLocation.keySet());
            sources.addAll(perWorldConflictStrategy.keySet());
            Map<String, Map<ModConfig.SaveLocation, String>> locations = new TreeMap<>();
            resolvedFolderNames.forEach((key, name) -> {
                int delimiter = key.indexOf('\0');
                if (delimiter <= 0 || key.indexOf('\0', delimiter + 1) >= 0) {
                    throw new IllegalArgumentException("Invalid legacy folder locator");
                }
                String source = key.substring(0, delimiter);
                ModConfig.SaveLocation root = ModConfig.SaveLocation.valueOf(key.substring(delimiter + 1));
                sources.add(source);
                if (MirrorFolders.isUsableFolderName(name)) {
                    locations.computeIfAbsent(source, ignored -> new EnumMap<>(ModConfig.SaveLocation.class))
                            .put(root, name);
                }
            });
            Map<String, Entry> result = new TreeMap<>();
            for (String source : sources) {
                String preferred = entries.get(source);
                result.put(source,
                        new Entry(source, MirrorFolders.isUsableFolderName(preferred) ? preferred : null,
                                locations.getOrDefault(source, Map.of()),
                                parseOverride(ModConfig.SaveLocation.class, perWorldSaveLocation.get(source)),
                                parseOverride(ModConfig.ConflictStrategy.class,
                                        perWorldConflictStrategy.get(source))));
            }
            return new LegacyRouting(result);
        }

        private static <K, V> Map<K, V> orEmpty(Map<K, V> entries) {
            return entries == null ? Map.of() : entries;
        }

        private static <E extends Enum<E>> E parseOverride(Class<E> type, String name) {
            return name == null || name.isBlank() ? null : Enum.valueOf(type, name);
        }
    }
}
