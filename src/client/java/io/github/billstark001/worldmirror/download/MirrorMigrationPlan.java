package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.MirrorWorldgenCheck;
import io.github.billstark001.worldmirror.legacy.LegacyVoidChunkCleanup;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Read-only classification of a mirror save before an operation may write it.
 * This deliberately contains no client or UI API so it can be shared by the
 * download path, world-open hook, and tests.
 */
public final class MirrorMigrationPlan {
    public enum State { NEW, CURRENT, OUTDATED, FUTURE, UNREADABLE, UNMANAGED }

    public record Inspection(Path worldFolder, State state, WorldMetadata metadata, boolean migrateWorldgen,
            boolean refreshAssets, boolean cleanupLegacyVoidChunks, boolean repairWorldgen, String problem) {
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
        var schema = WorldMetadata.inspectSchema(normalized);
        switch (schema) {
            case MISSING -> {
                if (Files.notExists(normalized.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) {
                    boolean backup =
                            !Files.notExists(normalized.resolve("level.dat_old"), LinkOption.NOFOLLOW_LINKS);
                    return unavailable(normalized, backup ? State.UNMANAGED : State.NEW, null);
                }
                // A copied claim cannot turn an unrelated playable world into a mirror. Only
                // resume materialization after both level data and our recognized generator exist.
                boolean owned = MirrorClaim.read(normalized) != null
                        && Files.isRegularFile(normalized.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)
                        && MirrorWorldgenCheck.inspect(normalized).state()
                                == MirrorWorldgenCheck.State.CURRENT;
                return unavailable(normalized, owned ? State.NEW : State.UNMANAGED, null);
            }
            case UNMANAGED -> {
                return unavailable(normalized, State.UNMANAGED, null);
            }
            case FUTURE -> {
                return unavailable(normalized, State.FUTURE, "metadata_schema_future");
            }
            case UNREADABLE -> {
                return unavailable(normalized, State.UNREADABLE, "metadata_unreadable");
            }
            case CURRENT -> {
            }
        }
        var loaded = WorldMetadata.loadIfPresent(normalized);
        if (loaded.isEmpty())
            return unavailable(normalized, State.UNREADABLE, "metadata_unreadable");
        WorldMetadata metadata = loaded.get();
        if (metadata.hasFutureWorldgenSchema()
                || metadata.hasFutureWorldgenAssets(dataVersion, MirrorWorldgenAssets.ASSET_REVISION)) {
            return new Inspection(normalized, State.FUTURE, metadata, false, false, false, false, null);
        }
        if (!Files.isRegularFile(normalized.resolve("level.dat"))) {
            return new Inspection(
                    normalized, State.UNREADABLE, metadata, false, false, false, false, "level_data_missing");
        }
        boolean migrateWorldgen = metadata.needsWorldgenMigration();
        boolean refreshAssets =
                metadata.needsWorldgenAssetRefresh(dataVersion, MirrorWorldgenAssets.ASSET_REVISION);
        boolean cleanupLegacyVoidChunks =
                LegacyVoidChunkCleanup.isNeeded(metadata.generation.cleanupRevision());
        MirrorWorldgenCheck.Result content = fresh ? MirrorWorldgenCheck.inspectFresh(normalized)
                                                   : MirrorWorldgenCheck.inspect(normalized);
        if (content.state() == MirrorWorldgenCheck.State.UNREADABLE
                || content.state() == MirrorWorldgenCheck.State.UNRECOGNIZED) {
            return new Inspection(
                    normalized, State.UNREADABLE, metadata, false, false, false, false, content.problem());
        }
        if (content.state() == MirrorWorldgenCheck.State.FUTURE) {
            return new Inspection(
                    normalized, State.FUTURE, metadata, false, false, false, false, content.problem());
        }
        boolean repairWorldgen =
                content.state() == MirrorWorldgenCheck.State.LEGACY && !(migrateWorldgen || refreshAssets);
        migrateWorldgen |= content.state() == MirrorWorldgenCheck.State.LEGACY;
        return new Inspection(normalized,
                migrateWorldgen || refreshAssets || cleanupLegacyVoidChunks ? State.OUTDATED : State.CURRENT,
                metadata, migrateWorldgen, refreshAssets, cleanupLegacyVoidChunks, repairWorldgen, null);
    }

    private static Inspection unavailable(Path folder, State state, String problem) {
        return new Inspection(folder, state, null, false, false, false, false, problem);
    }
}
