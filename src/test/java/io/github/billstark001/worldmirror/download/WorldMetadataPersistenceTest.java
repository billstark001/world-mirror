package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.MirrorFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMetadataPersistenceTest {
    private static final String MIRROR = "mirror:existing";
    private static final String PARENT = "remote:graph-id";
    private static final String SOURCE = "server:legacy.example.test";
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void upgradesLegacyMetadataAndKeepsExistingIndexRows(@TempDir Path world) throws Exception {
        writeLegacy(world);
        String original = Files.readString(world.resolve(WorldMetadata.FILE_NAME));
        try (ChunkDatabase database = ChunkDatabase.open(world, SOURCE)) {
            assertTrue(database.recordUpdates(OVERWORLD, Map.of(new net.minecraft.world.level.ChunkPos(1, 2), 75L),
                    "replay_import"));
        }

        assertEquals(WorldMetadata.Schema.LEGACY, WorldMetadata.inspectSchema(world));
        assertTrue(WorldMetadata.loadIfPresent(world).isEmpty());
        LegacyWorldMetadata legacy = LegacyWorldMetadata.read(world);
        WorldMetadata upgraded = WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT, legacy);

        assertEquals(MIRROR, upgraded.nodeId);
        assertEquals(PARENT, upgraded.parentNodeId);
        assertEquals(SOURCE, upgraded.sourceId);
        assertEquals(WorldGraph.Kind.MIRROR, upgraded.kind);
        assertEquals(WorldGraph.Role.SYNC, upgraded.role);
        assertEquals(123L, upgraded.lastSyncTime);
        assertEquals(new WorldMetadata.Generation(WorldMetadata.GenerationMode.VOID, 1, 3, 5000, 1),
                upgraded.generation);
        assertEquals(WorldMetadata.Schema.CURRENT, WorldMetadata.inspectSchema(world));
        assertNull(LegacyWorldMetadata.read(world));
        String canonical = Files.readString(world.resolve(WorldMetadata.FILE_NAME));
        assertFalse(canonical.contains("chunkUpdateTimes"));
        assertFalse(canonical.contains("mirrorId"));
        assertFalse(canonical.contains("worldgenAssetRevision"));

        var index = ChunkDatabase.queryAllReadOnly(world, SOURCE, OVERWORLD);
        assertEquals(2, index.size());
        assertTrue(index.contains(new ChunkDatabase.ChunkRecord(1, 2, 75L, "replay_import")));
        assertTrue(index.contains(new ChunkDatabase.ChunkRecord(3, 4, 125L, "world_mirror")));
        assertTrue(ChunkDatabase.queryAllReadOnly(world, PARENT, OVERWORLD).isEmpty());
        Path backup = onlyBackup(world);
        assertEquals(original, Files.readString(backup));

        WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT, legacy);
        assertEquals(canonical, Files.readString(world.resolve(WorldMetadata.FILE_NAME)));
        assertEquals(2, ChunkDatabase.countAllReadOnly(world, SOURCE));
        assertEquals(backup, onlyBackup(world));
    }

    @Test
    void failedLegacyMigrationKeepsTheOnlyCopyOfTheTimestamps(@TempDir Path world) throws Exception {
        writeLegacy(world);
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        String original = Files.readString(metadata);
        Path database = world.resolve(MirrorFormat.DATABASE_FILE);
        Files.createDirectories(database);

        assertThrows(IOException.class, () -> WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT));
        assertEquals(original, Files.readString(metadata));
        assertEquals(WorldMetadata.Schema.LEGACY, WorldMetadata.inspectSchema(world));
        Path backup = onlyBackup(world);
        assertEquals(original, Files.readString(backup));

        Files.delete(database);
        WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT);
        assertEquals(2, ChunkDatabase.countAllReadOnly(world, SOURCE));
        assertEquals(backup, onlyBackup(world));
    }

    @Test
    void rejectsChangedLegacyIdentityBeforeOpeningTheDatabase(@TempDir Path world) throws Exception {
        writeLegacy(world);
        var observed = LegacyWorldMetadata.read(world);
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        String changed = Files.readString(metadata).replace(SOURCE, "server:changed.example.test");
        Files.writeString(metadata, changed);

        assertThrows(IOException.class, () -> WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT, observed));
        assertEquals(changed, Files.readString(metadata));
        assertTrue(Files.notExists(world.resolve(MirrorFormat.DATABASE_FILE)));
        assertTrue(Files.notExists(world.resolve("backups")));
    }

    @Test
    void recognizesUnversionedWorldMirrorMetadataByItsSignature(@TempDir Path world) throws Exception {
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        Files.writeString(metadata, """
                {"sourceId":"server:example.test","sourceType":"server"}
                """);
        assertEquals(WorldMetadata.Schema.LEGACY, WorldMetadata.inspectSchema(world));
        WorldMetadata upgraded = WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT);
        assertEquals("server:example.test", upgraded.sourceId);
        assertTrue(upgraded.needsWorldgenMigration());

        Files.writeString(metadata, """
                {"sourceId":"server:example.test"}
                """);
        assertEquals(WorldMetadata.Schema.UNMANAGED, WorldMetadata.inspectSchema(world));
        assertThrows(IOException.class, () -> WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT));
    }

    @Test
    void distinguishesIndependentAndLinkedNearbyExports(@TempDir Path world) throws Exception {
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        Files.writeString(metadata, """
                {"format":"worldmirror","metadataSchema":1,"mirrorKind":"nearby_export",
                 "sourceType":"nearby_export","sourceId":"snapshot:legacy-namespace","mirrorId":"existing"}
                """);
        WorldMetadata standalone = WorldMetadataUpgrade.upgrade(world, "snapshot:existing", null);
        assertEquals(WorldGraph.Kind.SNAPSHOT, standalone.kind);
        assertEquals(WorldGraph.Role.SNAPSHOT, standalone.role);
        assertNull(standalone.parentNodeId);
        assertEquals("snapshot:legacy-namespace", standalone.sourceId);

        Files.writeString(metadata, """
                {"format":"worldmirror","metadataSchema":1,"mirrorKind":"nearby_export",
                 "sourceType":"mirror","sourceId":"mirror:parent","parentMirrorId":"parent","mirrorId":"existing"}
                """);
        WorldMetadata linked = WorldMetadataUpgrade.upgrade(world, MIRROR, "mirror:parent");
        assertEquals(WorldGraph.Kind.MIRROR, linked.kind);
        assertEquals(WorldGraph.Role.SNAPSHOT, linked.role);
        assertEquals("mirror:parent", linked.parentNodeId);
    }

    @Test
    void refusesForeignFutureAndMalformedMetadata(@TempDir Path world) throws Exception {
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        Files.writeString(metadata, """
                {"format":"other","metadataSchema":1,"sourceId":"server:example.test","sourceType":"server"}
                """);
        assertEquals(WorldMetadata.Schema.UNMANAGED, WorldMetadata.inspectSchema(world));

        Files.writeString(metadata, """
                {"format":"worldmirror","metadataSchema":3,"sourceId":"server:example.test"}
                """);
        assertEquals(WorldMetadata.Schema.FUTURE, WorldMetadata.inspectSchema(world));

        Files.writeString(metadata, """
                {"format":"worldmirror","metadataSchema":2,"sourceId":"server:example.test"}
                """);
        assertEquals(WorldMetadata.Schema.UNREADABLE, WorldMetadata.inspectSchema(world));
        assertTrue(WorldMetadata.loadIfPresent(world).isEmpty());
    }

    @Test
    void identifiesLegacyHeaderBeforeValidatingTimestampContents(@TempDir Path world) throws Exception {
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        String original = """
                {"format":"worldmirror","metadataSchema":1,"sourceId":"server:example.test",
                 "sourceType":"server","chunkUpdateTimes":{"invalid-coordinate":125}}
                """;
        Files.writeString(metadata, original);

        assertEquals(WorldMetadata.Schema.LEGACY, WorldMetadata.inspectSchema(world));
        assertThrows(IOException.class, () -> LegacyWorldMetadata.read(world));
        assertThrows(IOException.class, () -> WorldMetadataUpgrade.upgrade(world, MIRROR, PARENT));
        assertEquals(original, Files.readString(metadata));
        assertTrue(Files.notExists(world.resolve(MirrorFormat.DATABASE_FILE)));
    }

    private static Path onlyBackup(Path world) throws IOException {
        try (var paths = Files.list(world.resolve("backups"))) {
            var backups = paths.filter(file -> file.getFileName().toString().startsWith("worldmirror-meta-v1-")).toList();
            assertEquals(1, backups.size());
            return backups.getFirst();
        }
    }

    private static void writeLegacy(Path world) throws IOException {
        Files.writeString(world.resolve(WorldMetadata.FILE_NAME), """
                {
                  "format":"worldmirror", "metadataSchema":1, "modVersion":"0.5.4",
                  "mirrorId":"existing", "sourceId":"server:legacy.example.test", "sourceType":"server",
                  "lastSyncTime":123, "worldgenSchema":1, "worldgenAssetRevision":3,
                  "worldgenAssetDataVersion":5000, "legacyVoidChunkCleanupRevision":1,
                  "chunkUpdateTimes":{"minecraft:overworld|1,2":100,"minecraft:overworld|3,4":125}
                }
                """);
    }
}
