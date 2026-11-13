package io.github.billstark001.worldmirror.legacy;

import com.google.gson.JsonObject;
import io.github.billstark001.worldmirror.download.MirrorClaim;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Set;

/** Reads and upgrades the unversioned v0 source-only mirror reservation. */
public final class LegacyMirrorClaim {
    private static final Set<String> V0_FIELDS = Set.of("sourceId", "token");

    private LegacyMirrorClaim() {}

    public static String readSource(Path folder) {
        Path file = folder.resolve(MirrorClaim.FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return null;
        JsonObject claim = JsonSupport.readDocument(file, JsonObject.class).value();
        if (claim == null || !claim.keySet().equals(V0_FIELDS))
            return null;
        var source = claim.get("sourceId");
        var token = claim.get("token");
        if (!source.isJsonPrimitive() || !source.getAsJsonPrimitive().isString() || !token.isJsonPrimitive()
                || !token.getAsJsonPrimitive().isString())
            return null;
        String sourceId = source.getAsString();
        if (sourceId.isBlank() || sourceId.chars().anyMatch(Character::isISOControl))
            return null;
        try {
            UUID.fromString(token.getAsString());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return sourceId;
    }

    public static MirrorClaim.Claim upgrade(Path folder, String sourceId, String parentId)
            throws IOException {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(folder)) {
            Path file = folder.resolve(MirrorClaim.FILE_NAME);
            FileProof.Version stamp = FileProof.version(file);
            if (!sourceId.equals(readSource(folder)))
                throw new IOException("Legacy reservation changed");
            var claim = new MirrorClaim.Claim(
                    UUID.randomUUID(), "mirror:" + UUID.randomUUID(), parentId, sourceId);
            JsonSupport.compareAndWriteDocument(file, claim, stamp);
            return claim;
        }
    }
}
