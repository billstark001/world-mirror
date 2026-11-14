package io.github.billstark001.worldmirror.legacy;

import com.google.common.io.ByteStreams;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.NbtReadSupport;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CheckedInputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the terrain and native generator saved by the original offline seed conversion. */
public record LegacySeedConversionBackup(Path archive, Path root) {
    private static final Path LEVEL = Path.of("level.dat");
    private static final Path METADATA = Path.of(WorldMetadata.FILE_NAME);
    private static final Path PACK = Path.of("datapacks", MirrorWorldgenAssets.PACK_DIRECTORY);
    private static final Path BIOMES = PACK.resolve("data/worldmirror/worldgen/biome");
    private static final Set<Path> PACK_FILES = Set.of(PACK.resolve("pack.mcmeta"),
            PACK.resolve("worldmirror_manifest.json"), BIOMES.resolve("mirror_overworld.json"),
            BIOMES.resolve("mirror_nether.json"), BIOMES.resolve("mirror_end.json"));
    private static final Pattern ARCHIVE_NAME = Pattern.compile(
            "worldmirror-schema-backup-(\\d{4}-\\d{2}-\\d{2}T\\d{2})-(\\d{2})-(\\d{2}(?:\\.\\d{1,9})?Z)\\.zip");
    private static final Pattern EXTERNAL_NAME = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");
    private static final long MAX_WORLD_DATA = 128L * 1024 * 1024;
    private static final long MAX_REGION = (0xFFFFFFL + 255) * 4096;
    private static final long MAX_EXTERNAL = 64L * 1024 * 1024;
    private static final long MAX_JSON = 64 * 1024;

    private record Candidate(Path path, Instant time) {}
    private record Contents(Map<Path, ZipEntry> generation, Map<Path, ZipEntry> terrain) {}

    /** Uses the supplied transaction staging directory. The caller owns its cleanup. */
    public static LegacySeedConversionBackup open(Path world, Path staging, WorldMetadata current)
            throws IOException {
        Path backups = world.resolve("backups");
        FileProof.requirePlainPath(world, backups);
        FileProof.requirePlainDirectory(backups);
        if (Files.notExists(backups, LinkOption.NOFOLLOW_LINKS))
            return null;
        int dataVersion = current.generation.dataVersion();
        var currentLevel = NbtReadSupport.readWorldData(world.resolve(LEVEL)).getCompoundOrEmpty("Data");
        long lastPlayed = currentLevel.getLongOr("LastPlayed", Long.MAX_VALUE);
        for (var candidate : candidates(backups)) {
            try {
                var backup = readCandidate(candidate, staging, current, dataVersion, lastPlayed);
                if (backup != null)
                    return backup;
            } catch (IOException invalid) {
                WMLogger.warnRateLimited("seed-conversion-backup-" + candidate.path(), 30_000L,
                        "Cannot read seed conversion backup: " + candidate.path(), invalid);
            }
        }
        return null;
    }

    private static List<Candidate> candidates(Path backups) throws IOException {
        var result = new ArrayList<Candidate>();
        try (var entries = Files.list(backups)) {
            for (Path file : entries.toList()) {
                var match = ARCHIVE_NAME.matcher(file.getFileName().toString());
                if (!match.matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    continue;
                try {
                    Instant time =
                            Instant.parse(match.group(1) + ":" + match.group(2) + ":" + match.group(3));
                    result.add(new Candidate(file, time));
                } catch (DateTimeParseException invalid) {
                    // Not a timestamp written by World Mirror.
                }
            }
        }
        result.sort(Comparator.comparing(Candidate::time).reversed().thenComparing(Candidate::path));
        return result;
    }

    private static LegacySeedConversionBackup readCandidate(Candidate candidate, Path staging,
            WorldMetadata current, int dataVersion, long lastPlayed) throws IOException {
        Path archive = candidate.path();
        var version = FileProof.version(archive);
        try (var zip = new ZipFile(archive.toFile())) {
            var contents = contents(zip, staging);
            if (contents == null)
                return null;
            Path extracted = Files.createTempDirectory(staging, "seed-backup-");
            extract(zip, extracted, METADATA, contents.generation().get(METADATA));
            var before = WorldMetadata.loadIfPresent(extracted).orElse(null);
            if (!matchesMetadata(before, current, dataVersion))
                return null;
            extract(zip, extracted, LEVEL, contents.generation().get(LEVEL));
            if (!matchesLevel(extracted, candidate.time(), dataVersion, lastPlayed))
                return null;
            for (var entry : contents.generation().entrySet()) {
                if (!entry.getKey().equals(METADATA) && !entry.getKey().equals(LEVEL))
                    extract(zip, extracted, entry.getKey(), entry.getValue());
            }
            if (!WorldStructureCreator.hasCanonicalVoidGeneration(extracted))
                return null;
            for (var entry : contents.terrain().entrySet())
                extract(zip, extracted, entry.getKey(), entry.getValue());
            if (!version.equals(FileProof.version(archive)))
                throw new IOException("Seed conversion backup changed while it was being read");
            return new LegacySeedConversionBackup(archive, extracted);
        }
    }

    private static boolean matchesMetadata(WorldMetadata before, WorldMetadata current, int dataVersion) {
        return before != null && before.generation.mode() == WorldMetadata.GenerationMode.VOID
                && before.generation.revision() == current.generation.revision()
                && before.generation.dataVersion() == dataVersion
                && before.generation.cleanupRevision() == LegacyVoidChunkCleanup.COMPLETED_REVISION
                && before.nodeId.equals(current.nodeId) && before.sourceId.equals(current.sourceId)
                && before.lastSyncTime <= current.lastSyncTime;
    }

    private static boolean matchesLevel(Path root, Instant time, int dataVersion, long lastPlayed)
            throws IOException {
        var level = NbtReadSupport.readWorldData(root.resolve(LEVEL)).getCompoundOrEmpty("Data");
        long before = level.getLongOr("LastPlayed", 0);
        return level.getIntOr("DataVersion", 0) == dataVersion && before <= lastPlayed
                && (before == 0 || !Instant.ofEpochMilli(before).isAfter(time));
    }

    private static Contents contents(ZipFile zip, Path staging) throws IOException {
        var required = new HashMap<String, Path>();
        for (Path path : WorldStructureCreator.generationFiles(staging)) required.put(zipName(path), path);
        required.put(zipName(METADATA), METADATA);
        for (Path path : PACK_FILES) required.put(zipName(path), path);
        var directories = new HashMap<String, Path>();
        for (var dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            Path path = staging.relativize(WorldSaveLayout.regionDirectory(staging, dimension));
            directories.put(zipName(path), path);
        }
        Map<Path, ZipEntry> generation = new HashMap<>();
        Map<Path, ZipEntry> terrain = new HashMap<>();
        boolean hasRegion = false;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            Path requiredFile = required.get(entry.getName());
            if (requiredFile != null) {
                collect(generation, requiredFile, entry, fileLimit(requiredFile));
                continue;
            }
            Path region = terrainPath(directories, entry.getName());
            if (region != null) {
                collect(terrain, region, entry, fileLimit(region));
                hasRegion |= region.getFileName().toString().endsWith(".mca");
            }
        }
        return generation.size() == required.size() && hasRegion ? new Contents(generation, terrain) : null;
    }

    private static Path terrainPath(Map<String, Path> directories, String name) throws IOException {
        int slash = name.lastIndexOf('/');
        if (slash < 0)
            return null;
        Path directory = directories.get(name.substring(0, slash));
        if (directory == null)
            return null;
        String file = name.substring(slash + 1);
        if (AnvilReader.REGION_NAME.matcher(file).matches()) {
            Path path = directory.resolve(file);
            AnvilReader.coordinates(path);
            return path;
        }
        var external = EXTERNAL_NAME.matcher(file);
        if (!external.matches())
            return null;
        try {
            int x = Integer.parseInt(external.group(1));
            int z = Integer.parseInt(external.group(2));
            return file.equals("c." + x + "." + z + ".mcc") ? directory.resolve(file) : null;
        } catch (NumberFormatException invalid) {
            throw new IOException(
                    "Invalid external chunk coordinates in seed conversion backup: " + name, invalid);
        }
    }

    private static long fileLimit(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".mca"))
            return MAX_REGION;
        if (name.endsWith(".mcc"))
            return MAX_EXTERNAL;
        return name.endsWith(".dat") ? MAX_WORLD_DATA : MAX_JSON;
    }

    private static void collect(Map<Path, ZipEntry> files, Path path, ZipEntry entry, long limit)
            throws IOException {
        if (entry.isDirectory() || entry.getCrc() < 0 || entry.getSize() < 0 || entry.getSize() > limit
                || files.putIfAbsent(path, entry) != null)
            throw new IOException("Invalid or duplicate seed conversion backup entry: " + entry.getName());
    }

    private static void extract(ZipFile zip, Path root, Path relative, ZipEntry entry) throws IOException {
        Path path = root.resolve(relative);
        FileProof.requirePlainPath(root, path);
        Files.createDirectories(path.getParent());
        try (var input = new CheckedInputStream(zip.getInputStream(entry), new CRC32());
                var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            long copied = ByteStreams.limit(input, entry.getSize()).transferTo(output);
            if (copied != entry.getSize() || input.read() != -1
                    || input.getChecksum().getValue() != entry.getCrc())
                throw new IOException("Seed conversion backup entry failed verification: " + entry.getName());
        }
    }

    private static String zipName(Path path) {
        return path.toString().replace('\\', '/');
    }
}
