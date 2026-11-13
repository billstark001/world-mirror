package io.github.billstark001.worldmirror.legacy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.billstark001.worldmirror.download.MirrorClaim;
import io.github.billstark001.worldmirror.download.WorldRelocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Decodes v1 relocation journals and verifies their save identity. */
public final class LegacyRelocation {
    public static final int SCHEMA = 1;
    private static final Gson JSON = new Gson();

    private record Document(UUID operation, String sourceId, String mirrorId, String source, String target,
                            List<String> entries) {}

    public record Plan(UUID operation, String sourceId, String nodeId, String source, String target,
                       List<String> entries) implements WorldRelocation.RecoveryPlan {
        @Override public boolean matchesMetadata(Path folder) throws IOException {
            LegacyWorldMetadata metadata = LegacyWorldMetadata.read(folder);
            return metadata != null && sourceId.equals(metadata.sourceId()) && nodeId.equals("mirror:" + metadata.mirrorId());
        }

        @Override public boolean matchesClaim(Path folder) {
            return sourceId.equals(LegacyMirrorClaim.readSource(folder));
        }

        @Override public void finishCommitted(Path source, Path target) throws IOException {
            Files.deleteIfExists(source.resolve(MirrorClaim.FILE_NAME));
            Files.deleteIfExists(target.resolve(MirrorClaim.FILE_NAME));
        }

        @Override public boolean retainsSourceReservation() { return false; }

        @Override public WorldRelocation.LocationWitness routingWitness() {
            return (locations, oldSource) -> oldSource.apply(sourceId);
        }
    }

    private LegacyRelocation() {}

    public static Plan decode(JsonObject json) throws IOException {
        Document old = JSON.fromJson(json, Document.class);
        if (old.mirrorId() == null || old.mirrorId().isBlank()) throw new IOException("Missing legacy mirror ID");
        return new Plan(old.operation(), old.sourceId(), "mirror:" + old.mirrorId(), old.source(), old.target(), old.entries());
    }

}
