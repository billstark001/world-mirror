package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Durable ownership of an unfinished mirror before its metadata is published. */
public final class MirrorClaim {
    public static final String FILE_NAME = ".worldmirror-claim.json";

    public record Claim(UUID operationId, String nodeId, String parentNodeId, String sourceId) {
        public Claim {
            if (operationId == null || !WorldGraph.isNodeId(nodeId) || !nodeId.startsWith("mirror:")
                    || (parentNodeId != null && !WorldGraph.isNodeId(parentNodeId)) || sourceId == null
                    || sourceId.isBlank() || sourceId.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid mirror reservation");
            }
        }
    }
    private MirrorClaim() {}

    static void create(Path folder, String nodeId, String parentNodeId, String sourceId) throws IOException {
        JsonSupport.compareAndWriteDocument(folder.resolve(FILE_NAME),
                new Claim(UUID.randomUUID(), nodeId, parentNodeId, sourceId), null);
    }

    static Claim read(Path folder) {
        Path file = folder.resolve(FILE_NAME);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                ? JsonSupport.readDocument(file, Claim.class).value()
                : null;
    }

    static boolean ownedBy(Path folder, String nodeId) {
        Claim claim = read(folder);
        return claim != null && nodeId.equals(claim.nodeId());
    }

    static void reassign(Path folder, String previousId, String parentId, String replacementId)
            throws IOException {
        Path file = folder.resolve(FILE_NAME);
        FileProof.Version stamp = FileProof.version(file);
        Claim current = read(folder);
        if (current == null || !previousId.equals(current.nodeId())
                || !Objects.equals(parentId, current.parentNodeId())) {
            throw new IOException("Mirror reservation changed: " + folder);
        }
        JsonSupport.compareAndWriteDocument(
                file, new Claim(UUID.randomUUID(), replacementId, parentId, current.sourceId()), stamp);
    }

    static void changeParent(Path folder, String nodeId, String previousParent, String parentId)
            throws IOException {
        Path file = folder.resolve(FILE_NAME);
        FileProof.Version stamp = FileProof.version(file);
        Claim current = read(folder);
        if (current == null || !nodeId.equals(current.nodeId())
                || !Objects.equals(previousParent, current.parentNodeId()))
            throw new IOException("Mirror reservation changed: " + folder);
        JsonSupport.compareAndWriteDocument(
                file, new Claim(current.operationId(), nodeId, parentId, current.sourceId()), stamp);
    }

    static void finish(Path folder, String nodeId) throws IOException {
        if (ownedBy(folder, nodeId)
                && WorldMetadata.loadIfPresent(folder)
                        .filter(metadata -> nodeId.equals(metadata.nodeId))
                        .isPresent()) {
            Files.deleteIfExists(folder.resolve(FILE_NAME));
        }
    }
}
