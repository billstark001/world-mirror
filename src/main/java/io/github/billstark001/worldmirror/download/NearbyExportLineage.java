package io.github.billstark001.worldmirror.download;

import java.util.UUID;

/** Pure metadata choice for exports made while a mirror save is open. */
public final class NearbyExportLineage {
    public enum Choice { INHERIT_ORIGINAL, CURRENT_MIRROR, INDEPENDENT }

    public record Result(String sourceId, String sourceType, String parentMirrorId) {}

    private NearbyExportLineage() {}

    public static Result resolve(Choice choice, WorldMetadata currentMirror,
                                 String fallbackSourceId, String fallbackSourceType) {
        if (currentMirror == null) {
            return new Result(fallbackSourceId, fallbackSourceType, null);
        }
        if (currentMirror.mirrorId == null || currentMirror.mirrorId.isBlank()) {
            throw new IllegalArgumentException("current mirror must have a persistent mirrorId");
        }
        return switch (choice) {
            case INHERIT_ORIGINAL -> new Result(currentMirror.sourceId, currentMirror.sourceType,
                    currentMirror.mirrorId);
            case CURRENT_MIRROR -> new Result("mirror:" + currentMirror.mirrorId, "mirror",
                    currentMirror.mirrorId);
            case INDEPENDENT -> new Result("snapshot:" + UUID.randomUUID(), "nearby_export", null);
        };
    }
}
