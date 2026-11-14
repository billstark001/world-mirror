package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.legacy.LegacyRouting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Discovers and validates on-disk world folders and recovers pending relocations. */
final class WorldInventoryFolders {
    private final Path game;
    private final Supplier<ModConfig.SaveLocation> defaultSaveLocation;

    WorldInventoryFolders(Path game, Supplier<ModConfig.SaveLocation> defaultSaveLocation) {
        this.game = game;
        this.defaultSaveLocation = defaultSaveLocation;
    }

    Path root(Root root) {
        return game.resolve(root == Root.SAVES ? "saves" : "downloaded_worlds");
    }

    Location locationOf(Path folder) {
        return new Location(folder.getParent().equals(root(Root.SAVES)) ? Root.SAVES : Root.DOWNLOADED,
                folder.getFileName().toString());
    }

    /** Checks a local original before a caller assigns or reuses its persistent identity. */
    Path localOriginal(Location location) throws IOException {
        if (location.root() != Root.SAVES)
            throw new IOException("Local original must be in the saves directory: " + location.folder());
        Path saves = root(Root.SAVES);
        Path folder = saves.resolve(location.folder());
        requirePlainRoot(saves);
        requirePlainRoot(folder);
        requireLocalOriginalFiles(folder);
        if (SeedConversionTarget.hasOrphanVoidGenerator(folder))
            throw new IOException(
                    "Recover the mirror metadata before using this save as an upstream: " + folder);
        return folder;
    }

    static void requireLocalOriginalFiles(Path folder) throws IOException {
        Path level = folder.resolve("level.dat");
        FileProof.requirePlainFile(level);
        if (!Files.isReadable(level) || WorldMetadata.inspectSchema(folder) != WorldMetadata.Schema.MISSING
                || !Files.notExists(folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Local original unavailable: " + folder);
    }

    static void requirePlainRoot(Path path) throws IOException {
        FileProof.requirePlainDirectory(path);
        if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) {
            throw new IOException("Invalid world root: " + path);
        }
    }

    static String locationKey(Location location) {
        return location.root().name() + "/" + location.folder();
    }

    Set<Path> recoverRelocations(List<Path> folders, WorldGraphConfig.Snapshot config, LegacyRouting legacy)
            throws IOException {
        Set<Path> deferred = new HashSet<>();
        Set<Path> handled = new HashSet<>();
        Set<Path> allowed = Set.of(root(Root.SAVES), root(Root.DOWNLOADED));
        for (Path folder : folders) {
            if (handled.contains(folder))
                continue;
            WorldRelocation.Journal journal;
            try {
                journal = WorldRelocation.journal(folder);
            } catch (IOException | RuntimeException failure) {
                deferred.add(folder);
                WMLogger.warnRateLimited("graph-relocation:" + folder, 30_000,
                        "Invalid relocation journal: " + folder, failure);
                continue;
            }
            if (journal == null)
                continue;
            handled.add(journal.source());
            handled.add(journal.target());
            try {
                Location location = journal.routingWitness().find(config.locations(),
                        oldSource -> legacy.relocationWitness(oldSource, defaultSaveLocation));
                if (location == null)
                    throw new IOException("Relocation commit location unavailable");
                WorldRelocation.recover(folder, root(location.root()).resolve(location.folder()), allowed);
            } catch (IOException | RuntimeException failure) {
                deferred.add(journal.source());
                deferred.add(journal.target());
                WMLogger.warnRateLimited("graph-relocation:" + folder, 30_000,
                        "World relocation recovery deferred: " + folder, failure);
            }
        }
        return deferred;
    }

    List<Path> folders() throws IOException {
        List<Path> result = new ArrayList<>();
        for (Root kind : Root.values()) {
            Path base = root(kind);
            if (Files.notExists(base, LinkOption.NOFOLLOW_LINKS))
                continue;
            requirePlainRoot(base);
            try (var entries = Files.list(base)) {
                for (Path path : entries.sorted().toList()) {
                    if (isWorldFolder(path))
                        result.add(path);
                }
            }
        }
        return result;
    }

    private boolean isWorldFolder(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            return false;
        try {
            requirePlainRoot(path);
            locationOf(path);
            return true;
        } catch (IOException | IllegalArgumentException invalid) {
            WMLogger.warnRateLimited(
                    "graph-directory:" + path, 30_000, "World directory unavailable: " + path, invalid);
            return false;
        }
    }
}
