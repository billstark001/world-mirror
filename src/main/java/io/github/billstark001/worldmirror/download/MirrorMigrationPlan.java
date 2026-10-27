package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.MirrorWorldgenCheck;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Read-only classification of a mirror save before an operation may write it.
 * This deliberately contains no client or UI API so it can be shared by the
 * download path, world-open hook, and tests.
 */
public final class MirrorMigrationPlan {
    public enum State {
        NEW,
        CURRENT,
        OUTDATED,
        FUTURE,
        UNREADABLE,
        UNMANAGED
    }

    public record Inspection(Path worldFolder, State state, WorldMetadata metadata,
                             boolean migrateWorldgen, boolean refreshAssets,
                             boolean cleanupLegacyVoidChunks, boolean repairWorldgen, String problem) {
        public boolean requiresApproval() {
            return state == State.OUTDATED;
        }

        public boolean mayCreateOrWriteWithoutMigration() {
            return state == State.NEW || state == State.CURRENT;
        }
    }

    private MirrorMigrationPlan() {}

    public static Inspection inspect(Path worldFolder, int dataVersion) {
        return inspect(worldFolder, dataVersion, false);
    }

    static Inspection inspect(Path worldFolder, int dataVersion, boolean fresh) {
        Path normalized = worldFolder.toAbsolutePath().normalize();
        Path metadataFile = normalized.resolve(WorldMetadata.FILE_NAME);
        boolean hasLevelDat = !Files.notExists(normalized.resolve("level.dat"));
        if (!Files.isRegularFile(metadataFile)) {
            if (!Files.notExists(metadataFile)) {
                return new Inspection(normalized, State.UNREADABLE, null, false, false, false, false, null);
            }
            return new Inspection(normalized, hasLevelDat && MirrorClaim.source(normalized) == null ? State.UNMANAGED : State.NEW,
                    null, false, false, false, false, null);
        }

        Optional<WorldMetadata> loaded = WorldMetadata.loadIfPresent(normalized);
        if (loaded.isEmpty()) {
            return new Inspection(normalized, State.UNREADABLE, null, false, false, false, false, null);
        }
        WorldMetadata metadata = loaded.get();
        if (!WorldMetadata.FORMAT.equals(metadata.format)) {
            return new Inspection(normalized, State.UNMANAGED, metadata, false, false, false, false, null);
        }
        if (metadata.sourceId == null || metadata.sourceId.isBlank()) {
            return new Inspection(normalized, State.UNREADABLE, metadata, false, false, false, false, null);
        }
        if (metadata.metadataSchema > WorldMetadata.CURRENT_METADATA_SCHEMA
                || metadata.hasFutureWorldgenSchema()
                || metadata.hasFutureWorldgenAssets(dataVersion,
                        MirrorWorldgenAssets.ASSET_REVISION)) {
            return new Inspection(normalized, State.FUTURE, metadata, false, false, false, false, null);
        }
        if (!Files.isRegularFile(normalized.resolve("level.dat"))) {
            return new Inspection(normalized, State.UNREADABLE, metadata,
                    false, false, false, false, "level_data_missing");
        }
        boolean migrateWorldgen = metadata.needsWorldgenMigration();
        boolean refreshAssets = metadata.needsWorldgenAssetRefresh(
                dataVersion, MirrorWorldgenAssets.ASSET_REVISION);
        boolean cleanupLegacyVoidChunks = metadata.needsLegacyVoidChunkCleanup();
        MirrorWorldgenCheck.Result content = fresh ? MirrorWorldgenCheck.inspectFresh(normalized)
                : MirrorWorldgenCheck.inspect(normalized);
        if (content.state() == MirrorWorldgenCheck.State.UNREADABLE
                || content.state() == MirrorWorldgenCheck.State.UNRECOGNIZED) {
            return new Inspection(normalized, State.UNREADABLE, metadata,
                    false, false, false, false, content.problem());
        }
        if (content.state() == MirrorWorldgenCheck.State.FUTURE) {
            return new Inspection(normalized, State.FUTURE, metadata,
                    false, false, false, false, content.problem());
        }
        boolean repairWorldgen = content.state() == MirrorWorldgenCheck.State.LEGACY
                && !(migrateWorldgen || refreshAssets);
        migrateWorldgen |= content.state() == MirrorWorldgenCheck.State.LEGACY;
        return new Inspection(normalized,
                migrateWorldgen || refreshAssets || cleanupLegacyVoidChunks
                        ? State.OUTDATED : State.CURRENT,
                metadata, migrateWorldgen, refreshAssets, cleanupLegacyVoidChunks, repairWorldgen, null);
    }
}
