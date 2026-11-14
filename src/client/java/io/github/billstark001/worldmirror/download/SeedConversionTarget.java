package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.SeedConversion.Progress;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.MirrorWorldgenCheck;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.util.DirectoryLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/** The identity and native files eligible for an offline seed operation. */
record SeedConversionTarget(Path folder, String expectedNodeId, MirrorTarget existing, WorldMetadata metadata,
        MirrorClaim.Claim claim, boolean initializeNative) {
    static final Path OWNED_PACK = Path.of("datapacks", MirrorWorldgenAssets.PACK_DIRECTORY);

    boolean sameIdentity(SeedConversionTarget other) {
        return java.util.Objects.equals(existing, other.existing)
                && java.util.Objects.equals(claim, other.claim) && initializeNative == other.initializeNative
                && metadata.sourceId.equals(other.metadata.sourceId);
    }

    /** Claims an inventory category only. Seed adoption requires stronger checks. */
    static boolean hasOrphanVoidGenerator(Path folder) {
        Path root = folder.toAbsolutePath().normalize();
        if (Files.notExists(root.resolve(OWNED_PACK), LinkOption.NOFOLLOW_LINKS))
            return false;
        try {
            requireAvailableFolder(root);
            if (!hasNoWorldMirrorIdentity(root))
                return false;
            var state = MirrorWorldgenCheck.inspect(root).state();
            return state == MirrorWorldgenCheck.State.CURRENT || state == MirrorWorldgenCheck.State.LEGACY;
        } catch (IOException unsupported) {
            return false;
        }
    }

    static boolean isAdoptableVoid(Path folder) {
        Path root = folder.toAbsolutePath().normalize();
        if (!hasOrphanVoidGenerator(root))
            return false;
        try {
            if (!isCurrentOwnedVoid(root))
                return false;
            inspectOrphanNamespace(root, new Progress());
            return true;
        } catch (Exception unsupported) {
            return false;
        }
    }

    static void requireAvailableFolder(Path root) throws IOException {
        FileProof.requirePlainDirectory(root.getParent());
        FileProof.requirePlainDirectory(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(root.resolve(WorldRelocation.JOURNAL), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The selected world is unavailable");
    }

    private static boolean isOrphanVoid(Path root) throws IOException {
        return hasNoWorldMirrorIdentity(root) && isCurrentOwnedVoid(root);
    }

    private static boolean hasNoWorldMirrorIdentity(Path root) throws IOException {
        return WorldMetadata.inspectSchema(root) == WorldMetadata.Schema.MISSING
                && Files.notExists(root.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(root.resolve(LocalWorldIdentity.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                && !MirrorMigrationCoordinator.needsRecovery(root)
                && Files.isRegularFile(root.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS);
    }

    static SeedConversionTarget inspect(Path root, String nodeId, String plannedId, Progress progress,
            SeedIndexSnapshot snapshot) throws Exception {
        requireAvailableFolder(root);
        if (MirrorMigrationCoordinator.needsRecovery(root))
            throw new IOException("The selected world requires recovery before seed changes");
        var schema = WorldMetadata.inspectSchema(root);
        if (schema == WorldMetadata.Schema.CURRENT) {
            var metadata = WorldMetadata.loadIfPresent(root).orElseThrow();
            if (!metadata.nodeId.equals(nodeId))
                throw new IOException("The selected mirror identity changed");
            var target = new MirrorTarget(root, metadata.nodeId, metadata.sourceId);
            return new SeedConversionTarget(root, nodeId, target, metadata, null, false);
        }
        if (schema != WorldMetadata.Schema.MISSING
                || !Files.notExists(root.resolve(LocalWorldIdentity.FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The selected world is not an unregistered World Mirror save");
        var claim = MirrorClaim.read(root);
        if (claim != null && claim.nodeId().equals(nodeId)) {
            boolean initializeNative = isUnmaterializedClaim(root);
            if (!initializeNative && !isCurrentOwnedVoid(root))
                throw new IOException("The unfinished mirror has unverified world generation");
            var metadata = WorldMetadata.create(nodeId, WorldGraph.Kind.MIRROR, claim.parentNodeId(),
                    WorldGraph.Role.SYNC, claim.sourceId());
            return new SeedConversionTarget(root, nodeId, null, metadata, claim, initializeNative);
        }
        if (nodeId != null || !isOrphanVoid(root))
            throw new IOException("The selected world is not an unregistered World Mirror save");
        if (snapshot == null) {
            inspectOrphanNamespace(root, progress);
            return new SeedConversionTarget(root, null, null, null, null, false);
        }
        String namespace = snapshot.orphanNamespace();
        String id = plannedId == null ? "snapshot:" + UUID.randomUUID() : plannedId;
        var metadata = WorldMetadata.create(id, WorldGraph.Kind.SNAPSHOT, null, WorldGraph.Role.SNAPSHOT,
                namespace == null ? id : namespace);
        return new SeedConversionTarget(root, null, null, metadata, null, false);
    }

    private static void inspectOrphanNamespace(Path root, Progress progress) throws Exception {
        if (Files.notExists(root.resolve(SeedIndexSnapshot.DATABASE), LinkOption.NOFOLLOW_LINKS))
            return;
        try (var world = WorldWriteLock.acquire(root, progress::checkpoint);
                var index = ChunkDatabase.lockForReplacement(root, progress::checkpoint);
                var snapshot = SeedIndexSnapshot.open(root, progress::checkpoint)) {
            snapshot.orphanNamespace();
        }
    }

    private static boolean isUnmaterializedClaim(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (name.equals(MirrorClaim.FILE_NAME) || name.equals(DirectoryLock.LOCK_FILE))
                    continue;
                // A failed attempt may have already durably published its rollback archive.
                if (!name.equals("backups"))
                    return false;
                FileProof.requirePlainDirectory(path);
            }
            return true;
        }
    }

    private static boolean isCurrentOwnedVoid(Path root) throws IOException {
        return Files.isRegularFile(root.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)
                && WorldStructureCreator.hasCanonicalVoidGeneration(root)
                && WorldStructureCreator.hasUnmodifiedVoidAssets(root);
    }
}
