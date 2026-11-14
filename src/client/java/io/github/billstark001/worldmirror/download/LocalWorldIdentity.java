package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;

/** Identity of a local world without World Mirror save metadata. */
public final class LocalWorldIdentity {
    static final String FILE_NAME = "worldmirror_identity.json";

    private record Document(int schema, String nodeId) {
        boolean valid() {
            return schema == 1 && WorldGraph.isNodeId(nodeId) && nodeId.startsWith("local:");
        }
    }

    private LocalWorldIdentity() {}

    public static String read(Path folder) throws IOException {
        Path file = folder.resolve(FILE_NAME);
        if (Files.isSymbolicLink(file))
            throw new IOException("Linked local world identity: " + file);
        var document = JsonSupport.readDocument(file, Document.class);
        if (document.state() == JsonSupport.ReadState.MISSING)
            return null;
        if (document.value() == null || !document.value().valid()) {
            throw new IOException("Invalid local world identity: " + folder, document.failure());
        }
        return document.value().nodeId();
    }

    /** Caller holds the offline or integrated server's native world lease. */
    public static String ensure(Path folder) throws IOException {
        String current = read(folder);
        if (current != null)
            return current;
        String nodeId = "local:" + UUID.randomUUID();
        JsonSupport.compareAndWriteDocument(folder.resolve(FILE_NAME), new Document(1, nodeId), null);
        WMLogger.info("Local world identity assigned world=" + folder);
        return nodeId;
    }

    static void reassign(Path folder, String previous, String replacement) throws IOException {
        Path path = folder.resolve(FILE_NAME);
        FileProof.Version stamp = FileProof.version(path);
        if (!previous.equals(read(folder)))
            throw new NoSuchFileException(path.toString());
        JsonSupport.compareAndWriteDocument(path, new Document(1, replacement), stamp);
    }
}
