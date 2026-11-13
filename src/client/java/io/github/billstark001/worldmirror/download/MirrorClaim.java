package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/** Durable ownership of an unfinished mirror before its metadata is published. */
final class MirrorClaim {
    static final String FILE_NAME = ".worldmirror-claim.json";

    record Claim(UUID operationId, String nodeId, String parentNodeId, String sourceId) {
        Claim {
            if (operationId == null || !WorldMetadata.validKey(nodeId) || !nodeId.startsWith("mirror:")
                    || nodeId.substring("mirror:".length()).isBlank() || !WorldGraph.isNodeId(parentNodeId)
                    || !WorldMetadata.validKey(sourceId)) {
                throw new IllegalArgumentException("Invalid mirror reservation");
            }
        }
    }
    private record LegacyClaim(String sourceId, UUID token) {}

    private MirrorClaim() {}

    static void create(Path folder, String nodeId, String parentNodeId, String sourceId) throws IOException {
        JsonSupport.compareAndWriteDocument(folder.resolve(FILE_NAME),
                new Claim(UUID.randomUUID(), nodeId, parentNodeId, sourceId), null);
    }

    static Claim read(Path folder) {
        Path file = folder.resolve(FILE_NAME);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                ? JsonSupport.readDocument(file, Claim.class).value() : null;
    }

    static boolean ownedBy(Path folder, String nodeId) {
        Claim claim = read(folder);
        return claim != null && nodeId.equals(claim.nodeId());
    }

    static void reassign(Path folder, String previousId, String parentId, String replacementId) throws IOException {
        Path file = folder.resolve(FILE_NAME);
        FileProof.Version stamp = FileProof.version(file);
        Claim current = read(folder);
        if (current == null || !previousId.equals(current.nodeId()) || !parentId.equals(current.parentNodeId())) {
            throw new IOException("Mirror reservation changed: " + folder);
        }
        JsonSupport.compareAndWriteDocument(file,
                new Claim(UUID.randomUUID(), replacementId, parentId, current.sourceId()), stamp);
    }

    static void finish(Path folder, String nodeId) throws IOException {
        if (ownedBy(folder, nodeId) && WorldMetadata.loadIfPresent(folder)
                .filter(metadata -> nodeId.equals(metadata.nodeId)).isPresent()) {
            Files.deleteIfExists(folder.resolve(FILE_NAME));
        }
    }

    /** Only recovery of a previously persisted reservation uses the old claim shape. */
    static String readLegacySource(Path folder) {
        Path file = folder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        LegacyClaim claim = JsonSupport.readDocument(file, LegacyClaim.class).value();
        return claim != null && claim.token() != null && claim.sourceId() != null
                && !claim.sourceId().isBlank() ? claim.sourceId() : null;
    }
}
