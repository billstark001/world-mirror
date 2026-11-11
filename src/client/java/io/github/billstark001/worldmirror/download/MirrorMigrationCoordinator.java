package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.LegacyVoidChunkCleanup;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.FileTransaction;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Performs approved, offline mirror upgrades.  Export workers only inspect this
 * coordinator; they never invoke migration implicitly.
 */
public final class MirrorMigrationCoordinator {

    public record Result(boolean success, boolean changed, Path backup, String failure) {
        static Result success(boolean changed, Path backup) {
            return new Result(true, changed, backup, null);
        }

        static Result failure(String failure) {
            return new Result(false, false, null, failure);
        }
    }

    public enum Phase {
        SCANNING_VOID_CHUNKS,
        BACKING_UP,
        WRITING_WORLD_DATA,
        REMOVING_VOID_CHUNKS
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (phase, completed, total) -> {};

        void update(Phase phase, int completed, int total);
    }

    private MirrorMigrationCoordinator() {}

    public static MirrorMigrationPlan.Inspection inspect(Path worldFolder) {
        return MirrorMigrationPlan.inspect(worldFolder,
                SharedConstants.getCurrentVersion().dataVersion().version());
    }

    /** Background entry checks recover interrupted transactions before classifying their files. */
    public static MirrorMigrationPlan.Inspection recoverAndInspect(Path worldFolder) throws IOException {
        if (io.github.billstark001.worldmirror.util.WorldRecovery.required(worldFolder)) {
            try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
                return inspect(worldFolder);
            }
        }
        return inspect(worldFolder);
    }

    /**
     * Upgrades an existing mirror after explicit user confirmation.  The caller
     * must ensure the world is not loaded by an integrated server.
     */
    public static Result migrateApproved(Path worldFolder) {
        return migrateApproved(worldFolder, ProgressListener.NONE);
    }

    public static Result migrateApproved(Path worldFolder, ProgressListener progress) {
        ProgressListener observer = progress;
        ProgressListener safeProgress = (phase, completed, total) -> {
            try { observer.update(phase, completed, total); }
            catch (RuntimeException failure) {
                WMLogger.warnRateLimited("migration-progress", 30_000L, "Migration progress observer failed", failure);
            }
        };
        Path normalized = worldFolder.toAbsolutePath().normalize();
        try (WorldWriteLock ignored = WorldWriteLock.acquire(normalized)) {
            MirrorMigrationPlan.Inspection plan = MirrorMigrationPlan.inspect(normalized,
                    SharedConstants.getCurrentVersion().dataVersion().version(), true);
            if (plan.state() == MirrorMigrationPlan.State.CURRENT) {
                return Result.success(false, null);
            }
            if (plan.state() != MirrorMigrationPlan.State.OUTDATED || plan.metadata() == null) {
                return Result.failure("mirror_not_migratable:" + plan.state().name().toLowerCase(Locale.ROOT));
            }

            LegacyVoidChunkCleanup.Plan voidCleanup;
            try {
                safeProgress.update(Phase.SCANNING_VOID_CHUNKS, 0, 1);
                voidCleanup = plan.cleanupLegacyVoidChunks()
                        ? LegacyVoidChunkCleanup.plan(normalized,
                                (completed, total) -> safeProgress.update(
                                        Phase.SCANNING_VOID_CHUNKS, completed, total))
                        : LegacyVoidChunkCleanup.Plan.empty();
            } catch (Exception e) {
                WMLogger.warn("Failed to scan legacy void chunks in " + normalized, e);
                return Result.failure("legacy_void_scan_failed");
            }

            try (FileTransaction transaction = new FileTransaction(normalized)) {
                Path stagedWorld = transaction.stagingDirectory();
                Path backup;
                try {
                    safeProgress.update(Phase.BACKING_UP, 0, 1);
                    // Snapshot each source once; the verified ZIP and edits share those exact bytes.
                    var snapshot = copyTouchedFiles(normalized, stagedWorld, touchedFiles(normalized, voidCleanup));
                    backup = backupTouchedFiles(normalized, stagedWorld, snapshot);
                    safeProgress.update(Phase.BACKING_UP, 1, 1);
                } catch (Exception error) {
                    WMLogger.warn("Mirror migration backup failed world=" + normalized, error);
                    return Result.failure("backup_failed");
                }
                safeProgress.update(Phase.WRITING_WORLD_DATA, 0, 1);
                boolean created = WorldStructureCreator.createLoadableWorld(stagedWorld,
                        displayName(normalized, plan.metadata()), plan.migrateWorldgen(),
                        plan.refreshAssets(), WorldSettingsSnapshot.defaults());
                if (!created) return Result.failure("worldgen_write_failed");
                safeProgress.update(Phase.WRITING_WORLD_DATA, 1, 1);
                var stagedRegions = voidCleanup.regions().stream().map(region ->
                        new LegacyVoidChunkCleanup.RegionPlan(stagedWorld.resolve(
                                normalized.relativize(region.regionFile())), region.chunkIndices())).toList();
                LegacyVoidChunkCleanup.apply(new LegacyVoidChunkCleanup.Plan(stagedRegions),
                        (completed, total) -> safeProgress.update(Phase.REMOVING_VOID_CHUNKS, completed, total));
                WorldMetadata metadata = plan.metadata();
                metadata.ensureMirrorId();
                metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(),
                        MirrorWorldgenAssets.ASSET_REVISION);
                if (!metadata.save(stagedWorld)) return Result.failure("metadata_write_failed");
                java.util.Map<Path, Path> replacements = new java.util.HashMap<>();
                try (var files = Files.walk(stagedWorld)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        Path relative = stagedWorld.relativize(file);
                        if (publishable(relative)) replacements.put(relative, file);
                    }
                }
                var verified = io.github.billstark001.worldmirror.io.MirrorWorldgenCheck.inspectFresh(stagedWorld);
                if (verified.state() != io.github.billstark001.worldmirror.io.MirrorWorldgenCheck.State.CURRENT) {
                    return Result.failure("worldgen_verification_failed");
                }
                transaction.commit(replacements);
                return Result.success(true, backup);
            }
        } catch (Exception error) {
            OperationFailures.report(normalized.toString(), "migration", normalized, error);
            return Result.failure(error instanceof net.minecraft.util.DirectoryLock.LockException
                    ? "world_locked" : "migration_failed");
        }
    }

    private static boolean publishable(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(FileTransaction.PREFIX) || name.startsWith(FileTransaction.CLEANUP_PREFIX)
                    || name.equals("session.lock")
                    || name.equals(io.github.billstark001.worldmirror.util.WorldRecovery.MARKER)) return false;
        }
        return true;
    }

    private static String displayName(Path worldFolder, WorldMetadata metadata) {
        Path name = worldFolder.getFileName();
        if (name != null && !name.toString().isBlank()) return name.toString();
        return metadata.sourceId;
    }

    /** Creates a compact, recoverable backup of every file this migration may change. */
    private static Path backupTouchedFiles(Path worldFolder, Path snapshot,
                                           java.util.Map<Path, String> expected) throws IOException {
        Path backupRoot = worldFolder.resolve("backups");
        Files.createDirectories(backupRoot);
        String timestamp = Instant.now().toString().replace(':', '-');
        Path archive = backupRoot.resolve("worldmirror-schema-backup-" + timestamp + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Path relative : expected.keySet()) {
                zip.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                Files.copy(snapshot.resolve(relative), zip);
                zip.closeEntry();
            }
        }
        java.util.Set<Path> verified = new java.util.HashSet<>();
        try (var zip = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path relative = Path.of(entry.getName());
                var digest = io.github.billstark001.worldmirror.util.FileProof.sha256();
                new java.security.DigestInputStream(zip, digest).transferTo(java.io.OutputStream.nullOutputStream());
                zip.closeEntry();
                if (!verified.add(relative) || !java.util.HexFormat.of().formatHex(digest.digest()).equals(expected.get(relative))) {
                    throw new IOException("Migration backup content differs from the verified snapshot: " + relative);
                }
            }
        }
        if (verified.size() != expected.size()) throw new IOException("Incomplete migration backup");
        return archive;
    }

    private static java.util.List<Path> touchedFiles(Path root, LegacyVoidChunkCleanup.Plan cleanup)
            throws IOException {
        java.util.Set<Path> files = new java.util.LinkedHashSet<>();
        for (String name : java.util.List.of("level.dat", WorldMetadata.FILE_NAME,
                "data/minecraft/world_gen_settings.dat", "data/minecraft/world_clocks.dat")) {
            addExistingFile(files, root.resolve(name));
        }
        Path pack = root.resolve("datapacks").resolve(MirrorWorldgenAssets.PACK_DIRECTORY);
        if (!Files.notExists(pack)) {
            try (var paths = Files.walk(pack)) {
                for (Path path : paths.toList()) {
                    if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    addExistingFile(files, path);
                }
            }
        }
        for (var region : cleanup.regions()) {
            addExistingFile(files, region.regionFile());
            var coordinates = io.github.billstark001.worldmirror.io.AnvilReader.coordinates(region.regionFile());
            for (var pos : io.github.billstark001.worldmirror.io.McaWriteSupport.listChunkPositions(
                    region.regionFile(), coordinates.x(), coordinates.z())) {
                Path payload = region.regionFile().getParent().resolve("c."
                        + (pos.getRegionX() * 32 + pos.getRegionLocalX()) + "."
                        + (pos.getRegionZ() * 32 + pos.getRegionLocalZ()) + ".mcc");
                addExistingFile(files, payload);
            }
        }
        return java.util.List.copyOf(files);
    }

    private static void addExistingFile(java.util.Set<Path> files, Path file) throws IOException {
        if (Files.notExists(file)) return;
        var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Migration input is not a regular file: " + file);
        files.add(file);
    }

    private static java.util.Map<Path, String> copyTouchedFiles(Path source, Path destination,
                                                               java.util.List<Path> touched) throws IOException {
        java.util.Map<Path, String> hashes = new java.util.LinkedHashMap<>();
        for (Path file : touched) {
            Path relative = source.relativize(file);
            Path target = destination.resolve(relative);
            Files.createDirectories(target.getParent());
            hashes.put(relative, io.github.billstark001.worldmirror.util.FileProof.copyVerified(file, target));
        }
        return hashes;
    }

}
