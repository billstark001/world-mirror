package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Durable ownership of an unfinished new mirror, before its final metadata exists. */
final class MirrorClaim {
    static final String FILE_NAME = ".worldmirror-claim.json";
    private record Document(String sourceId, UUID token) {}
    private MirrorClaim() {}

    static void create(Path folder, String sourceId) throws IOException {
        JsonSupport.writeDocument(folder.resolve(FILE_NAME), new Document(sourceId, UUID.randomUUID()));
    }

    static String source(Path folder) {
        var read = JsonSupport.readDocument(folder.resolve(FILE_NAME), Document.class);
        Document document = read.value();
        return document != null && document.token() != null && document.sourceId() != null
                && !document.sourceId().isBlank() ? document.sourceId() : null;
    }

    static boolean ownedBy(Path folder, String sourceId) {
        return sourceId.equals(source(folder));
    }

    static void finish(Path folder, String sourceId) throws IOException {
        if (ownedBy(folder, sourceId) && WorldMetadata.isOwnedBy(folder, sourceId)) {
            Files.deleteIfExists(folder.resolve(FILE_NAME));
        }
    }
}
