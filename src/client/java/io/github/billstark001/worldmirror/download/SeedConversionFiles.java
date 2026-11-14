package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.SeedConversion.Operation;
import io.github.billstark001.worldmirror.download.SeedConversion.Request;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.RegionWriteTransaction;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.SharedConstants;
import net.minecraft.util.DirectoryLock;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.github.billstark001.worldmirror.download.SeedConversionTarget.OWNED_PACK;

/** Stages conversion files, verifies the rollback archive, and publishes the transaction. */
final class SeedConversionFiles {
    private static final Path DATABASE = SeedIndexSnapshot.DATABASE;

    private SeedConversionFiles() {}

    static Path publish(SeedConversionTarget target, Request request, SeedTerrain.Plan terrain,
            Map<Path, String> proofs, SeedIndexSnapshot snapshot) throws Exception {
        Path root = target.folder();
        var operation = request.operation();
        var metadata = target.metadata();
        boolean assigning = operation == Operation.SET || operation == Operation.RESEED;
        UUID blendEpoch = assigning ? UUID.randomUUID() : metadata.generation.seedEpoch();
        try (var tx = new FileTransaction(root)) {
            Path staged = tx.stagingDirectory();
            Map<Path, Path> writes = new LinkedHashMap<>();
            Set<Path> deletes = new HashSet<>();
            Map<String, Set<ChunkPos>> removed = new HashMap<>();
            for (var entry : terrain.edits().entrySet()) {
                var region = entry.getKey();
                var changes = entry.getValue();
                stageRegion(root, staged, region, region.file(), writes, deletes,
                        (pos, chunk)
                                -> SeedTerrain.transform(
                                        pos, chunk, changes.get(pos), region.dimension(), blendEpoch));
                Set<ChunkPos> gone = new HashSet<>();
                changes.forEach((pos, edit) -> {
                    if (edit.delete())
                        gone.add(pos);
                });
                if (gone.isEmpty())
                    continue;
                if (operation != Operation.SET)
                    removed.computeIfAbsent(
                                   region.dimension().identifier().toString(), ignored -> new HashSet<>())
                            .addAll(gone);
                stageAuxiliary(root, staged, region, gone, writes, deletes);
            }
            if (snapshot.root() != null
                    && ChunkDatabase.stageWithoutSeedGenerated(
                            snapshot.root(), staged, metadata.sourceId, removed)) {
                writes.put(DATABASE, staged.resolve(DATABASE));
                for (String suffix : new String[] {"-wal", "-shm", "-journal"}) {
                    Path sidecar = Path.of(DATABASE.toString() + suffix);
                    if (Files.exists(root.resolve(sidecar), LinkOption.NOFOLLOW_LINKS))
                        deletes.add(sidecar);
                }
            }
            if (operation != Operation.REGENERATE_BORDER) {
                stageNativeFiles(target, staged, writes);
                int version = SharedConstants.getCurrentVersion().dataVersion().version();
                if (assigning) {
                    String digest = operation == Operation.SET
                            ? WorldStructureCreator.stageSeededGeneration(staged, request.seed())
                            : WorldStructureCreator.stageReseededGeneration(
                                      staged, metadata.generation.settingsDigest(), request.seed());
                    metadata.markSeededGeneration(version, digest, blendEpoch, request.blendTerrain());
                    for (Path pack : packFiles(root)) deletes.add(root.relativize(pack));
                } else {
                    WorldStructureCreator.stageVoidGeneration(staged);
                    metadata.markWorldgenCurrent(version, MirrorWorldgenAssets.ASSET_REVISION);
                    for (Path pack : packFiles(staged)) writes.put(staged.relativize(pack), pack);
                }
                if (!metadata.save(staged))
                    throw new IOException("Could not stage mirror metadata");
                writes.put(Path.of(WorldMetadata.FILE_NAME), staged.resolve(WorldMetadata.FILE_NAME));
                if (target.claim() != null)
                    deletes.add(Path.of(MirrorClaim.FILE_NAME));
            }
            if (writes.isEmpty() && deletes.isEmpty())
                return null;
            var touched = new HashSet<>(writes.keySet());
            touched.addAll(deletes);
            var expected = new HashMap<>(proofs);
            // SQLite uses a transient shared-memory file, which is not a data source.
            Path shm = Path.of(DATABASE.toString() + "-shm");
            if (deletes.contains(shm))
                expected.put(shm, FileProof.sha256(root.resolve(shm)));
            MirrorMigrationCoordinator.verifyOriginals(root, touched, expected);
            Path originals = Files.createDirectory(staged.resolve("conversion-backup"));
            var files = touched.stream().filter(expected::containsKey).sorted().map(root::resolve).toList();
            var backupProof = MirrorMigrationCoordinator.copyTouchedFiles(root, originals, files);
            for (var entry : backupProof.entrySet()) {
                if (!entry.getValue().equals(expected.get(entry.getKey())))
                    throw new IOException("The mirror changed while preparing the backup");
            }
            Path backup = MirrorMigrationCoordinator.backupTouchedFiles(root, originals, backupProof);
            MirrorMigrationCoordinator.verifyOriginals(root, touched, expected);
            tx.commit(writes, deletes);
            if (operation == Operation.SET)
                retireEmptyPack(root);
            return backup;
        }
    }

    private static void stageAuxiliary(Path root, Path staged, SeedTerrain.Region region, Set<ChunkPos> gone,
            Map<Path, Path> writes, Set<Path> deletes) throws IOException {
        for (String kind : new String[] {"entities", "poi"}) {
            Path file = SeedTerrainFiles.sibling(region, kind);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
                continue;
            var existing = positions(file, region);
            var auxiliary = new HashSet<>(gone);
            auxiliary.retainAll(existing);
            if (!auxiliary.isEmpty())
                stageRegion(root, staged, region, file, writes, deletes,
                        (pos, chunk) -> auxiliary.contains(pos) ? null : chunk);
        }
    }

    private static void stageNativeFiles(SeedConversionTarget target, Path staged, Map<Path, Path> writes)
            throws IOException {
        Path root = target.folder();
        if (target.initializeNative()) {
            if (!WorldStructureCreator.createVoidWorld(
                        staged, root.getFileName().toString(), WorldSettingsSnapshot.defaults()))
                throw new IOException("Cannot initialize the reserved mirror");
            try (var paths = Files.walk(staged)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path relative = staged.relativize(file);
                    if (!relative.startsWith(OWNED_PACK)
                            && !relative.equals(Path.of(DirectoryLock.LOCK_FILE)))
                        writes.put(relative, file);
                }
            }
        } else {
            for (Path relative : WorldStructureCreator.generationFiles(root)) {
                Path file = staged.resolve(relative);
                Files.createDirectories(file.getParent());
                FileProof.copyVerified(root.resolve(relative), file);
                writes.put(relative, file);
            }
        }
    }

    // Only payload files are transactional. Empty directories carry no pack data and can be retired after commit.
    private static void retireEmptyPack(Path root) {
        Path pack = root.resolve(OWNED_PACK);
        try {
            FileProof.requirePlainPath(root, pack);
            FileProof.requirePlainDirectory(pack);
            if (Files.notExists(pack, LinkOption.NOFOLLOW_LINKS))
                return;
            try (var paths = Files.walk(pack)) {
                for (var directory : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                        continue;
                    FileProof.requirePlainPath(root, directory);
                    try {
                        Files.deleteIfExists(directory);
                    } catch (DirectoryNotEmptyException occupied) {
                        // Another writer installed a file. Leave it alone.
                    }
                }
            }
        } catch (IOException failure) {
            WMLogger.warn(
                    "Seed conversion committed; empty data pack directories could not be retired: " + pack,
                    failure);
        }
    }

    private static void stageRegion(Path root, Path staged, SeedTerrain.Region region, Path source,
            Map<Path, Path> writes, Set<Path> deletes, RegionWriteTransaction.Transform transform)
            throws IOException {
        Path relative = root.relativize(source);
        Path destination = staged.resolve(relative);
        RegionWriteTransaction.stage(
                source, destination, region.dimension(), region.x(), region.z(), Set.of(), transform);
        writes.put(relative, destination);
        var prior = positions(source, region);
        for (ChunkPos pos : prior) {
            Path originalPayload = SeedTerrainFiles.external(source, pos);
            Path stagedPayload = SeedTerrainFiles.external(destination, pos);
            Path key = root.relativize(originalPayload);
            if (Files.isRegularFile(stagedPayload, LinkOption.NOFOLLOW_LINKS))
                writes.put(key, stagedPayload);
            else if (Files.exists(originalPayload, LinkOption.NOFOLLOW_LINKS))
                deletes.add(key);
        }
    }

    private static Set<ChunkPos> positions(Path file, SeedTerrain.Region region) throws IOException {
        try (AnvilReader.Reader reader = AnvilReader.open(file, region.x(), region.z())) {
            return reader.positions();
        }
    }

    static List<Path> packFiles(Path root) throws IOException {
        Path pack = root.resolve(OWNED_PACK);
        FileProof.requirePlainPath(root, pack);
        if (Files.notExists(pack, LinkOption.NOFOLLOW_LINKS))
            return List.of();
        FileProof.requirePlainDirectory(pack);
        try (var walk = Files.walk(pack)) {
            var files = new ArrayList<Path>();
            for (Path path : walk.toList()) {
                FileProof.requirePlainPath(root, path);
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    FileProof.requirePlainFile(path);
                    files.add(path);
                }
            }
            return files;
        }
    }
}
