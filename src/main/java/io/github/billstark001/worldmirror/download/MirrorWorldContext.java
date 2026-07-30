package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Read-only identity of the locally-open world.  This deliberately does not
 * infer ownership from a folder name: a mirror is identified by its metadata.
 */
public final class MirrorWorldContext {

    public enum State { NOT_MIRROR, CURRENT, OUTDATED, FUTURE, UNREADABLE }

    public record Snapshot(Path worldFolder, State state, WorldMetadata metadata) {
        public static final Snapshot NONE = new Snapshot(null, State.NOT_MIRROR, null);

        public boolean isMirror() {
            return state == State.CURRENT || state == State.OUTDATED || state == State.FUTURE;
        }

        public String sourceId() {
            return metadata != null ? metadata.sourceId : null;
        }
    }

    private static volatile Snapshot current = Snapshot.NONE;

    private MirrorWorldContext() {}

    public static Snapshot current() {
        return current;
    }

    /** Updates the current local-save identity after JOIN. */
    public static void enter(Path worldFolder, int dataVersion) {
        current = inspect(worldFolder, dataVersion);
    }

    public static void leave() {
        current = Snapshot.NONE;
    }

    public static Snapshot inspect(Path worldFolder, int dataVersion) {
        if (worldFolder == null) return Snapshot.NONE;
        Path normalized = worldFolder.toAbsolutePath().normalize();
        Path metaFile = normalized.resolve(WorldMetadata.FILE_NAME);
        if (!Files.isRegularFile(metaFile)) return Snapshot.NONE;

        Optional<WorldMetadata> loaded = WorldMetadata.loadIfPresent(normalized);
        if (loaded.isEmpty()) return new Snapshot(normalized, State.UNREADABLE, null);

        WorldMetadata metadata = loaded.get();
        if (metadata.sourceId == null || metadata.sourceId.isBlank()) {
            return new Snapshot(normalized, State.UNREADABLE, metadata);
        }
        if (metadata.hasFutureWorldgenSchema()
                || metadata.hasFutureWorldgenAssets(dataVersion, MirrorWorldgenAssets.ASSET_REVISION)) {
            return new Snapshot(normalized, State.FUTURE, metadata);
        }
        if (metadata.needsWorldgenMigration()
                || metadata.needsWorldgenAssetRefresh(dataVersion, MirrorWorldgenAssets.ASSET_REVISION)) {
            return new Snapshot(normalized, State.OUTDATED, metadata);
        }
        return new Snapshot(normalized, State.CURRENT, metadata);
    }
}
