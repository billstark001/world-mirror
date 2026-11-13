package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.legacy.LegacyWorldMetadata;
import io.github.billstark001.worldmirror.legacy.WorldMetadataUpgrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorWorldContextTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static void createWorld(Path world) {
        assertTrue(io.github.billstark001.worldmirror.io.WorldStructureCreator.createLoadableWorld(world,
                "test", true, true, io.github.billstark001.worldmirror.io.WorldSettingsSnapshot.defaults()));
    }

    @Test
    void classifiesUnversionedMetadataBeforeAndAfterExplicitUpgrade(@TempDir Path world) throws Exception {
        createWorld(world);
        Files.writeString(world.resolve(WorldMetadata.FILE_NAME),
                JsonSupport.toPrettyJson(new LegacyMetadata("server:example.test", "server")));

        assertTrue(LegacyWorldMetadata.hasLegacyHeader(world));
        assertEquals(WorldMetadata.Schema.UNMANAGED, WorldMetadata.inspectSchema(world));
        assertFalse(MirrorMigrationPlan.inspect(world, 100).mayCreateOrWriteWithoutMigration());
        WorldMetadataUpgrade.upgrade(world, "mirror:test", "remote:test");
        assertEquals(MirrorWorldContext.State.OUTDATED, MirrorWorldContext.inspect(world, 100).state());
    }

    @Test
    void rejectsNewerSchemasInsteadOfDowngradingThem(@TempDir Path world) {
        WorldMetadata metadata = metadata();
        metadata.generation = new WorldMetadata.Generation(WorldMetadata.GenerationMode.VOID,
                WorldMetadata.CURRENT_WORLDGEN_SCHEMA + 1, MirrorWorldgenAssets.ASSET_REVISION + 1, 101, 0);
        metadata.save(world);

        assertEquals(MirrorWorldContext.State.FUTURE, MirrorWorldContext.inspect(world, 100).state());
        assertTrue(metadata.hasFutureWorldgenSchema());
        assertTrue(metadata.hasFutureWorldgenAssets(100, MirrorWorldgenAssets.ASSET_REVISION));
        assertFalse(metadata.needsWorldgenAssetRefresh(100, MirrorWorldgenAssets.ASSET_REVISION));
    }

    @Test
    void protectsFutureMetadataEvenWhenWorldgenIsCurrent(@TempDir Path world) throws Exception {
        WorldMetadata metadata = metadata();
        metadata.markWorldgenCurrent(100, MirrorWorldgenAssets.ASSET_REVISION);
        var document =
                com.google.gson.JsonParser.parseString(JsonSupport.toPrettyJson(metadata)).getAsJsonObject();
        document.addProperty("metadataSchema", WorldMetadata.CURRENT_METADATA_SCHEMA + 1);
        JsonSupport.writeDocument(world.resolve(WorldMetadata.FILE_NAME), document);

        assertEquals(MirrorWorldContext.State.FUTURE, MirrorWorldContext.inspect(world, 100).state());
        assertFalse(MirrorMigrationPlan.inspect(world, 100).mayCreateOrWriteWithoutMigration());
    }

    @Test
    void doesNotAdoptMetadataFromAnotherFormat(@TempDir Path world) throws Exception {
        WorldMetadata metadata = metadata();
        metadata.markWorldgenCurrent(100, MirrorWorldgenAssets.ASSET_REVISION);
        var document =
                com.google.gson.JsonParser.parseString(JsonSupport.toPrettyJson(metadata)).getAsJsonObject();
        document.addProperty("format", "another-tool");
        JsonSupport.writeDocument(world.resolve(WorldMetadata.FILE_NAME), document);

        assertEquals(MirrorMigrationPlan.State.UNMANAGED, MirrorMigrationPlan.inspect(world, 100).state());
        assertTrue(WorldMetadata.loadIfPresent(world).isEmpty());
        assertFalse(MirrorWorldContext.inspect(world, 100).isMirror());
    }

    @Test
    void aNonFileMetadataPathIsUnreadableInsteadOfANewSave(@TempDir Path world) throws Exception {
        Files.createDirectory(world.resolve(WorldMetadata.FILE_NAME));
        assertEquals(MirrorMigrationPlan.State.UNREADABLE, MirrorMigrationPlan.inspect(world, 100).state());
    }

    @Test
    void classifiesFreshAndUnmanagedFoldersSeparately(@TempDir Path root) throws Exception {
        Path fresh = root.resolve("fresh");
        Files.createDirectories(fresh);
        assertEquals(MirrorMigrationPlan.State.NEW, MirrorMigrationPlan.inspect(fresh, 100).state());

        Path unmanaged = root.resolve("unmanaged");
        Files.createDirectories(unmanaged);
        Files.writeString(unmanaged.resolve("level.dat"), "not a real level file");
        assertEquals(
                MirrorMigrationPlan.State.UNMANAGED, MirrorMigrationPlan.inspect(unmanaged, 100).state());
    }

    @Test
    void copiedReservationDoesNotAuthorizeUnrelatedWorldData(@TempDir Path root) throws Exception {
        Path world = Files.createDirectory(root.resolve("original"));
        MirrorClaim.create(world, "mirror:test", "remote:test", "remote:test");
        Files.writeString(world.resolve("level.dat"), "unrelated data");
        assertEquals(MirrorMigrationPlan.State.UNMANAGED, MirrorMigrationCoordinator.inspect(world).state());
        Files.move(world.resolve("level.dat"), world.resolve("level.dat_old"));
        assertEquals(MirrorMigrationPlan.State.UNMANAGED, MirrorMigrationCoordinator.inspect(world).state());
    }

    @Test
    void reservationCanResumeMaterializedMirrorBeforeMetadataCommit(@TempDir Path world) throws Exception {
        MirrorClaim.create(world, "mirror:test", "remote:test", "remote:test");
        createWorld(world);
        assertEquals(MirrorMigrationPlan.State.NEW, MirrorMigrationCoordinator.inspect(world).state());
    }

    @Test
    void schedulesTheOneTimeVoidCleanupWithoutChangingSchemaOne(@TempDir Path world) {
        createWorld(world);
        WorldMetadata metadata = metadata();
        metadata.generation = new WorldMetadata.Generation(WorldMetadata.GenerationMode.VOID,
                WorldMetadata.CURRENT_WORLDGEN_SCHEMA, MirrorWorldgenAssets.ASSET_REVISION, 100, 0);
        metadata.save(world);

        MirrorMigrationPlan.Inspection pending = MirrorMigrationPlan.inspect(world, 100);
        assertEquals(MirrorMigrationPlan.State.OUTDATED, pending.state());
        assertTrue(pending.cleanupLegacyVoidChunks());

        metadata.markWorldgenCurrent(100, MirrorWorldgenAssets.ASSET_REVISION);
        metadata.save(world);
        assertEquals(MirrorMigrationPlan.State.CURRENT, MirrorMigrationPlan.inspect(world, 100).state());
    }

    @Test
    void treatsRevisionTwoAsOutdatedOnEveryTarget(@TempDir Path world) {
        createWorld(world);
        WorldMetadata metadata = metadata();
        metadata.markWorldgenCurrent(100, 2);
        metadata.save(world);

        MirrorMigrationPlan.Inspection inspection = MirrorMigrationPlan.inspect(world, 100);
        assertEquals(3, MirrorWorldgenAssets.ASSET_REVISION);
        assertEquals(MirrorMigrationPlan.State.OUTDATED, inspection.state());
        assertFalse(inspection.migrateWorldgen());
        assertTrue(inspection.refreshAssets());
    }

    private static WorldMetadata metadata() {
        return WorldMetadata.create("mirror:test", WorldGraph.Kind.MIRROR, "remote:test",
                WorldGraph.Role.SYNC, "server:example.test");
    }

    private record LegacyMetadata(String sourceId, String sourceType) {}
}
