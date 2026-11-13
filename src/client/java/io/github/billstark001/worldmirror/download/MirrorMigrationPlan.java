package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.MirrorWorldgenCheck;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.legacy.LegacyVoidChunkCleanup;

import java.io.IOException;
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
    public enum WorldgenWork { NONE, REFRESH_VOID_ASSETS, UPGRADE_VOID_GENERATOR, REPAIR_VOID_GENERATOR }
    public enum TerrainWork { NONE, REMOVE_LEGACY_VOID_CHUNKS }

    public record Inspection(Path worldFolder, State state, WorldMetadata metadata, WorldgenWork worldgenWork,
            TerrainWork terrainWork, String problem) {
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
            return noWork(normalized, State.FUTURE, metadata, null);
        }
        if (!Files.isRegularFile(normalized.resolve("level.dat"))) {
            return noWork(normalized, State.UNREADABLE, metadata, "level_data_missing");
        }
        if (metadata.generation.mode() == WorldMetadata.GenerationMode.SEEDED) {
            try {
                boolean matches = WorldStructureCreator.matchesSeededGeneration(
                        normalized, metadata.generation.settingsDigest());
                return noWork(normalized, matches ? State.CURRENT : State.UNREADABLE, metadata,
                        matches ? null : "seeded_generation_changed");
            } catch (IOException | RuntimeException failure) {
                return noWork(normalized, State.UNREADABLE, metadata, "seeded_generation_unreadable");
            }
        }
        boolean migrateWorldgen = metadata.needsWorldgenMigration();
        boolean refreshAssets =
                metadata.needsWorldgenAssetRefresh(dataVersion, MirrorWorldgenAssets.ASSET_REVISION);
        TerrainWork terrainWork = LegacyVoidChunkCleanup.isNeeded(metadata.generation.cleanupRevision())
                ? TerrainWork.REMOVE_LEGACY_VOID_CHUNKS
                : TerrainWork.NONE;
        MirrorWorldgenCheck.Result content = fresh ? MirrorWorldgenCheck.inspectFresh(normalized)
                                                   : MirrorWorldgenCheck.inspect(normalized);
        if (content.state() == MirrorWorldgenCheck.State.UNREADABLE
                || content.state() == MirrorWorldgenCheck.State.UNRECOGNIZED) {
            return noWork(normalized, State.UNREADABLE, metadata, content.problem());
        }
        if (content.state() == MirrorWorldgenCheck.State.FUTURE) {
            return noWork(normalized, State.FUTURE, metadata, content.problem());
        }
        WorldgenWork worldgenWork = WorldgenWork.NONE;
        if (content.state() == MirrorWorldgenCheck.State.LEGACY) {
            worldgenWork = migrateWorldgen || refreshAssets ? WorldgenWork.UPGRADE_VOID_GENERATOR
                                                            : WorldgenWork.REPAIR_VOID_GENERATOR;
        } else if (migrateWorldgen) {
            worldgenWork = WorldgenWork.UPGRADE_VOID_GENERATOR;
        } else if (refreshAssets) {
            worldgenWork = WorldgenWork.REFRESH_VOID_ASSETS;
        }
        State state = worldgenWork != WorldgenWork.NONE || terrainWork != TerrainWork.NONE ? State.OUTDATED
                                                                                           : State.CURRENT;
        return new Inspection(normalized, state, metadata, worldgenWork, terrainWork, null);
    }

    private static Inspection unavailable(Path folder, State state, String problem) {
        return noWork(folder, state, null, problem);
    }

    private static Inspection noWork(Path folder, State state, WorldMetadata metadata, String problem) {
        return new Inspection(folder, state, metadata, WorldgenWork.NONE, TerrainWork.NONE, problem);
    }
}
