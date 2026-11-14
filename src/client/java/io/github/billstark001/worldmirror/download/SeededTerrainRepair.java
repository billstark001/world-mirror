package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.legacy.LegacySeedConversionBackup;
import io.github.billstark001.worldmirror.legacy.LegacySeedTerrainRepair;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Publishes a one-time legacy terrain repair under the native save lease. */
final class SeededTerrainRepair {
    enum Phase { READING_BACKUP, SCANNING, STAGING, PUBLISHING }

    @FunctionalInterface
    interface Progress {
        void update(Phase phase, int completed, int total);
    }

    private SeededTerrainRepair() {}

    static boolean repairLocked(Path world, WorldMetadata metadata) throws Exception {
        return repairLocked(world, metadata, (phase, completed, total) -> {});
    }

    static boolean repairLocked(Path world, WorldMetadata metadata, Progress progress) throws Exception {
        if (metadata == null || !metadata.needsSeededTerrainRepair())
            return false;
        long started = System.nanoTime();
        progress.update(Phase.READING_BACKUP, 0, 0);
        var metadataPath = Path.of(WorldMetadata.FILE_NAME);
        Map<Path, String> proofs = new HashMap<>();
        var checkpoint = new SeedConversion.Progress();
        SeedTerrainFiles.proofIncludingAbsence(world, world.resolve(metadataPath), proofs, checkpoint);
        var current = WorldMetadata.loadIfPresent(world).orElseThrow(
                () -> new IOException("Missing mirror metadata"));
        if (!current.needsSeededTerrainRepair())
            return false;
        if (!current.nodeId.equals(metadata.nodeId) || !current.sourceId.equals(metadata.sourceId)
                || !current.generation.equals(metadata.generation))
            throw new IOException("Mirror seed settings changed before terrain repair");
        for (Path path : WorldStructureCreator.generationFiles(world))
            SeedTerrainFiles.proofIncludingAbsence(world, world.resolve(path), proofs, checkpoint);
        if (!WorldStructureCreator.matchesSeededGeneration(world, current.generation.settingsDigest()))
            throw new IOException("Native seed settings changed before terrain repair");
        try (var tx = new FileTransaction(world)) {
            var staged = tx.stagingDirectory();
            var before = LegacySeedConversionBackup.open(
                    world, Files.createDirectory(staged.resolve("pre-seed")), current);
            if (before == null) {
                WMLogger.warn("No verified pre-seed backup for terrain repair world=" + world);
                return false;
            }
            return publishRepair(world, current, progress, started, proofs, metadataPath, tx, before.root());
        }
    }

    private static boolean publishRepair(Path world, WorldMetadata metadata, Progress progress, long started,
            Map<Path, String> proofs, Path metadataPath, FileTransaction tx, Path archive) throws Exception {
        long archived = System.nanoTime();
        progress.update(Phase.SCANNING, 0, 0);
        try (var index = ChunkDatabase.lockForReplacement(world);
                var snapshot = SeedIndexSnapshot.open(world, () -> {})) {
            var staged = tx.stagingDirectory();
            var changes = LegacySeedTerrainRepair.scan(world, archive, snapshot.root(), metadata, proofs,
                    (completed, total) -> progress.update(Phase.SCANNING, completed, total));
            long scanned = System.nanoTime();
            Map<Path, Path> writes = new LinkedHashMap<>();
            Set<Path> deletes = new HashSet<>();
            int total = changes.stream().mapToInt(SeededTerrainRepair::regionCount).sum();
            int[] completed = {0};
            progress.update(Phase.STAGING, 0, total);
            for (var dimension : changes)
                stage(world, staged, dimension, writes, deletes, proofs,
                        () -> progress.update(Phase.STAGING, ++completed[0], total));
            proofs.putAll(stageGeneratedIndex(world, staged, snapshot, metadata, changes, writes, deletes));
            metadata.markSeededTerrainRepaired();
            if (!metadata.save(staged))
                throw new IOException("Could not stage seed repair metadata");
            writes.put(metadataPath, staged.resolve(metadataPath));
            long stagedAt = System.nanoTime();
            progress.update(Phase.PUBLISHING, 0, 0);
            Path backup = SeedConversionFiles.commitWithBackup(world, tx, writes, deletes, proofs, () -> {
                snapshot.verifyDurableContents(world);
                SeedConversionFiles.verifyDependencies(world, proofs, writes.keySet(), deletes);
            });
            WMLogger.info("Repaired legacy seed terrain world=" + world
                    + " removed=" + changes.stream().mapToInt(change -> change.remove().size()).sum()
                    + " relit=" + changes.stream().mapToInt(change -> change.relight().size()).sum()
                    + " backup=" + backup.getFileName() + " backupReadMs=" + millis(started, archived)
                    + " scanMs=" + millis(archived, scanned) + " stageMs=" + millis(scanned, stagedAt)
                    + " publishMs=" + millis(stagedAt, System.nanoTime()));
            return true;
        }
    }

    private static Map<Path, String> stageGeneratedIndex(Path world, Path staged, SeedIndexSnapshot snapshot,
            WorldMetadata metadata, java.util.List<LegacySeedTerrainRepair.Changes> changes,
            Map<Path, Path> writes, Set<Path> deletes) throws Exception {
        var removed = new HashMap<String, Set<ChunkPos>>();
        for (var change : changes)
            if (!change.generated().isEmpty())
                removed.put(change.dimension().identifier().toString(), change.generated());
        if (snapshot.root() == null
                || !ChunkDatabase.stageWithoutSeedGenerated(
                        snapshot.root(), staged, metadata.sourceId, removed))
            return Map.of();
        Path database = SeedIndexSnapshot.DATABASE;
        var proofs = new HashMap<>(snapshot.proofs());
        writes.put(database, staged.resolve(database));
        for (String suffix : new String[] {"-wal", "-journal", "-shm"}) {
            Path path = Path.of(database.toString() + suffix);
            if (Files.exists(world.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                deletes.add(path);
                if (suffix.equals("-shm"))
                    proofs.put(path, FileProof.sha256(world.resolve(path)));
            }
        }
        return proofs;
    }

    private static long millis(long start, long end) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(end - start);
    }

    private static int regionCount(LegacySeedTerrainRepair.Changes change) {
        var paths = new HashSet<>(change.remove().values());
        paths.addAll(change.relight().values());
        return paths.size();
    }

    private static void stage(Path world, Path staged, LegacySeedTerrainRepair.Changes changes,
            Map<Path, Path> writes, Set<Path> deletes, Map<Path, String> proofs, Runnable regionStaged)
            throws IOException {
        Map<Path, Set<ChunkPos>> regions = new LinkedHashMap<>();
        changes.remove().forEach(
                (pos, path) -> regions.computeIfAbsent(path, key -> new HashSet<>()).add(pos));
        changes.relight().forEach(
                (pos, path) -> regions.computeIfAbsent(path, key -> new HashSet<>()).add(pos));
        for (var entry : regions.entrySet()) {
            var file = entry.getKey();
            var changed = entry.getValue();
            var gone = new HashSet<>(changed);
            gone.retainAll(changes.remove().keySet());
            var coordinates = AnvilReader.coordinates(file);
            var region = new SeedTerrain.Region(changes.dimension(), file, coordinates.x(), coordinates.z());
            proveExternalPayloads(world, file, changed, proofs);
            SeedConversionFiles.stageSparseRegion(
                    world, staged, region, file, writes, deletes, changed, (pos, chunk) -> {
                        if (gone.contains(pos))
                            return null;
                        if (changes.relight().containsKey(pos))
                            SeedTerrainLighting.invalidate(chunk);
                        return chunk;
                    });
            if (!gone.isEmpty()) {
                for (String kind : new String[] {"entities", "poi"})
                    proveExternalPayloads(world, SeedTerrainFiles.sibling(region, kind), gone, proofs);
                SeedConversionFiles.stageAuxiliary(world, staged, region, gone, writes, deletes);
            }
            regionStaged.run();
        }
    }

    private static void proveExternalPayloads(
            Path world, Path file, Set<ChunkPos> changed, Map<Path, String> proofs) throws IOException {
        var progress = new SeedConversion.Progress();
        for (ChunkPos pos : changed)
            SeedTerrainFiles.proof(world, AnvilReader.externalFile(file.getParent(), pos), proofs, progress);
    }
}
