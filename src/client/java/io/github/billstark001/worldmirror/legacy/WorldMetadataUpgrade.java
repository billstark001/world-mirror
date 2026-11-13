package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

/** Commits a legacy save's SQLite timestamps before publishing its canonical metadata. */
public final class WorldMetadataUpgrade {
    private WorldMetadataUpgrade() {}

    public static WorldMetadata upgrade(Path worldFolder, String nodeId, String parentNodeId)
            throws IOException {
        return upgrade(worldFolder, nodeId, parentNodeId, null);
    }

    /** Uses the optional inspected legacy document as the precondition for publishing a planned identity. */
    public static WorldMetadata upgrade(Path worldFolder, String nodeId, String parentNodeId,
            LegacyWorldMetadata expected) throws IOException {
        if (!Files.isDirectory(worldFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Mirror folder is missing or not a plain directory: " + worldFolder);
        }
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            LegacyWorldMetadata legacy = LegacyWorldMetadata.read(worldFolder);
            if (legacy == null) {
                WorldMetadata current =
                        WorldMetadata.loadIfPresent(worldFolder)
                                .orElseThrow(
                                        () -> new IOException("Mirror metadata is missing: " + worldFolder));
                if (!Objects.equals(nodeId, current.nodeId)
                        || !Objects.equals(parentNodeId, current.parentNodeId)
                        || (expected != null && !expected.sourceId().equals(current.sourceId))) {
                    throw new IOException("Mirror identity changed before metadata upgrade: " + worldFolder);
                }
                return current;
            }
            if (expected != null && !expected.equals(legacy)) {
                throw new IOException("Mirror metadata changed before upgrade: " + worldFolder);
            }
            WorldMetadata metadata = legacy.upgrade(nodeId, parentNodeId);
            backupLegacyDocument(worldFolder, legacy);
            if (!legacy.chunkUpdateTimes().isEmpty()) {
                try (var connection = ChunkDatabase.openWritableConnection(worldFolder)) {
                    ChunkIndexStore.initialize(connection);
                    // Commit durably before publishing metadata without its original timestamps.
                    try (var statement = connection.createStatement()) {
                        statement.execute("PRAGMA synchronous=FULL");
                    }
                    LegacyChunkTimes.importInto(connection, legacy.sourceId(), legacy.chunkUpdateTimes());
                } catch (SQLException failure) {
                    throw new IOException("Cannot migrate legacy chunk timestamps: " + worldFolder, failure);
                }
            }
            JsonSupport.compareAndWriteDocument(
                    worldFolder.resolve(WorldMetadata.FILE_NAME), metadata, legacy.version());
            return metadata;
        }
    }

    private static void backupLegacyDocument(Path world, LegacyWorldMetadata legacy) throws IOException {
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        Path backups = world.resolve("backups");
        FileProof.requirePlainDirectory(backups);
        if (Files.notExists(backups, LinkOption.NOFOLLOW_LINKS))
            Files.createDirectory(backups);
        try (var transaction = new FileTransaction(backups)) {
            Path staged = transaction.stagingDirectory().resolve(WorldMetadata.FILE_NAME);
            String digest = FileProof.copyVerified(metadata, staged);
            if (!legacy.version().equals(FileProof.version(metadata))) {
                throw new IOException("Mirror metadata changed before backup: " + metadata);
            }
            Path name = Path.of("worldmirror-meta-v1-" + digest + ".json");
            Path backup = backups.resolve(name);
            if (!Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                        || !digest.equals(FileProof.sha256(backup))) {
                    throw new IOException("Existing mirror metadata backup differs: " + backup);
                }
                return;
            }
            transaction.commit(java.util.Map.of(name, staged));
        }
    }
}
