package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.download.MirrorMigrationCoordinator;
import io.github.billstark001.worldmirror.download.MirrorMigrationPlan;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.WorldRecovery;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MirrorWorldgenRepairTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void currentMetadataDoesNotHideOldGeneratorsAndRepairPreservesOtherData(@TempDir Path world)
            throws Exception {
        createOldWorld(world);
        CompoundTag root = read(world);
        root.putString("root_extension", "retain");
        CompoundTag settings = WorldStructureApi.worldgenData(root);
        settings.putLong("seed", 12345);
        settings.putString("extra_option", "retain");
        CompoundTag dimensions = settings.getCompoundOrEmpty("dimensions");
        CompoundTag custom = WorldStructureCreator.createMirrorWorldGenSettings()
                .getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").copy();
        custom.putString("extra_dimension_option", "retain");
        dimensions.put("example:extra", custom);
        // A valid customized generator must survive repair of its obsolete neighbors.
        CompoundTag endGenerator = WorldStructureCreator.createMirrorWorldGenSettings()
                .getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:the_end")
                .getCompoundOrEmpty("generator");
        endGenerator.getCompoundOrEmpty("settings").putInt("sea_level", 47);
        dimensions.getCompoundOrEmpty("minecraft:the_end").put("generator", endGenerator);
        write(world, root);
        Path unrelated = Files.writeString(world.resolve("captured-terrain-fixture"), "retain captured data");
        byte[] original = Files.readAllBytes(WorldStructureApi.worldgenDataPath(world));
        byte[] originalMetadata = Files.readAllBytes(world.resolve(WorldMetadata.FILE_NAME));

        var plan = MirrorMigrationCoordinator.inspect(world);
        assertEquals(MirrorMigrationPlan.State.OUTDATED, plan.state());
        assertTrue(plan.repairWorldgen());
        assertTrue(plan.requiresApproval());
        assertFalse(plan.mayCreateOrWriteWithoutMigration());
        // Inspection/cancelling at this point is entirely read-only.
        assertArrayEquals(original, Files.readAllBytes(WorldStructureApi.worldgenDataPath(world)));
        assertFalse(Files.exists(world.resolve("backups")));

        var result = MirrorMigrationCoordinator.migrateApproved(world);
        assertTrue(result.success(), result.failure());
        assertTrue(result.changed());
        assertEquals(MirrorMigrationPlan.State.CURRENT, MirrorMigrationCoordinator.inspect(world).state());
        CompoundTag updatedRoot = read(world);
        assertEquals("retain", updatedRoot.getStringOr("root_extension", ""));
        CompoundTag updated = WorldStructureApi.worldgenData(updatedRoot);
        assertEquals(12345L, updated.getLongOr("seed", 0));
        assertEquals("retain", updated.getStringOr("extra_option", ""));
        assertEquals(custom, updated.getCompoundOrEmpty("dimensions").get("example:extra"));
        assertEquals(endGenerator, updated.getCompoundOrEmpty("dimensions")
                .getCompoundOrEmpty("minecraft:the_end").get("generator"));
        assertEquals("retain captured data", Files.readString(unrelated));
        try (var zip = new ZipFile(result.backup().toFile())) {
            String relative = world.relativize(WorldStructureApi.worldgenDataPath(world)).toString().replace('\\', '/');
            try (var input = zip.getInputStream(zip.getEntry(relative))) {
                assertArrayEquals(original, input.readAllBytes());
            }
            try (var input = zip.getInputStream(zip.getEntry(WorldMetadata.FILE_NAME))) {
                assertArrayEquals(originalMetadata, input.readAllBytes());
            }
        }
        Map<Path, byte[]> after = snapshot(world);
        var repeated = MirrorMigrationCoordinator.migrateApproved(world);
        assertTrue(repeated.success());
        assertFalse(repeated.changed());
        assertNull(repeated.backup());
        assertSnapshot(world, after);
        try (var backups = Files.list(world.resolve("backups"))) { assertEquals(1, backups.count()); }
    }

    @Test
    void refusesUnknownAndUnreadableGeneratorsWithoutReplacingAnything(@TempDir Path world) throws Exception {
        createOldWorld(world);
        CompoundTag root = read(world);
        WorldStructureApi.worldgenData(root).getCompoundOrEmpty("dimensions")
                .getCompoundOrEmpty("minecraft:overworld").getCompoundOrEmpty("generator")
                .putString("type", "example:custom_generator");
        write(world, root);
        Map<Path, byte[]> original = snapshot(world);
        assertEquals(MirrorMigrationPlan.State.UNREADABLE, MirrorMigrationCoordinator.inspect(world).state());
        assertFalse(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertSnapshot(world, original);
        assertFalse(Files.exists(world.resolve("backups")));

        Files.writeString(WorldStructureApi.worldgenDataPath(world), "broken gzip");
        original = snapshot(world);
        assertEquals(MirrorMigrationPlan.State.UNREADABLE, MirrorMigrationCoordinator.inspect(world).state());
        assertFalse(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertSnapshot(world, original);
    }

    @Test
    void missingLevelDataCannotCauseAnExistingMirrorToBeRecreated(@TempDir Path world) throws Exception {
        createOldWorld(world);
        Files.delete(world.resolve("level.dat"));
        Map<Path, byte[]> original = snapshot(world);
        assertEquals(MirrorMigrationPlan.State.UNREADABLE, MirrorMigrationCoordinator.inspect(world).state());
        assertFalse(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertSnapshot(world, original);
        assertFalse(Files.exists(world.resolve("level.dat")));
        assertFalse(Files.exists(world.resolve("backups")));
    }

    @Test
    void futureLevelDataCannotBePatchedByRepairingAnOlderGenerator(@TempDir Path world) throws Exception {
        createOldWorld(world);
        Path file = world.resolve("level.dat");
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        root.getCompoundOrEmpty("Data").putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version() + 1);
        NbtIo.writeCompressed(root, file);
        Map<Path, byte[]> original = snapshot(world);
        assertFalse(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertSnapshot(world, original);
    }

    @Test
    void cachesOnlyStableResultsAndSerializesConcurrentInspections(@TempDir Path world) throws Exception {
        createOldWorld(world);
        MirrorWorldgenCheck.Result first;
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(i -> executor.submit(() -> MirrorWorldgenCheck.inspect(world))).toList();
            first = tasks.getFirst().get();
            for (var task : tasks) assertSame(first, task.get());
        }
        assertTrue(MirrorMigrationCoordinator.migrateApproved(world).success());
        var updated = MirrorWorldgenCheck.inspect(world);
        assertNotSame(first, updated);
        assertEquals(MirrorWorldgenCheck.State.CURRENT, updated.state());
        assertSame(updated, MirrorWorldgenCheck.inspect(world));
    }

    @Test
    void approvedRepairRechecksBytesEvenWhenCheapCacheTokensHaveNotChanged(@TempDir Path world)
            throws Exception {
        createOldWorld(world);
        var cached = MirrorWorldgenCheck.inspect(world);
        Path file = WorldStructureApi.worldgenDataPath(world);
        var before = FileProof.version(file);
        byte[] damaged = Files.readAllBytes(file);
        damaged[0] = 0; // Same inode, size and timestamp; invalid gzip content.
        Files.write(file, damaged);
        Files.setLastModifiedTime(file, before.modified());
        assertEquals(before, FileProof.version(file));
        assertSame(cached, MirrorWorldgenCheck.inspect(world));
        assertFalse(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertArrayEquals(damaged, Files.readAllBytes(file));
        assertFalse(Files.exists(world.resolve("backups")));
    }

    @Test
    void futureNativeDataVersionIsProtectedEvenWithCurrentMetadata(@TempDir Path world) throws Exception {
        createOldWorld(world);
        CompoundTag root = read(world);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version() + 1);
        write(world, root);
        Map<Path, byte[]> original = snapshot(world);
        assertEquals(MirrorMigrationPlan.State.FUTURE, MirrorMigrationCoordinator.inspect(world).state());
        assertFalse(MirrorMigrationCoordinator.migrateApproved(world).success());
        assertSnapshot(world, original);
        assertFalse(Files.exists(world.resolve("backups")));
    }

    @Test
    void invalidStagedGeneratorCannotPublishDataOrCurrentMetadata(@TempDir Path world) throws Exception {
        createOldWorld(world);
        Map<Path, byte[]> original = snapshot(world);
        var result = MirrorMigrationCoordinator.migrateApproved(world, (phase, complete, total) -> {
            if (phase == MirrorMigrationCoordinator.Phase.WRITING_WORLD_DATA && complete == total) {
                try (var paths = Files.list(world)) {
                    Path transaction = paths.filter(p -> p.getFileName().toString().startsWith(FileTransaction.PREFIX))
                            .findFirst().orElseThrow();
                    Files.writeString(WorldStructureApi.worldgenDataPath(transaction.resolve("new")), "damaged staged gzip");
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
        });
        assertFalse(result.success());
        assertEquals("worldgen_verification_failed", result.failure());
        assertSnapshot(world, original);
        assertTrue(MirrorMigrationCoordinator.inspect(world).repairWorldgen());
        assertTrue(MirrorMigrationCoordinator.migrateApproved(world).success());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void interruptedRepairRestoresOnlyCheckedImagesWithoutOverwritingUnknownEdits(
            boolean externalEdit, @TempDir Path world) throws Exception {
        createOldWorld(world);
        Map<Path, byte[]> original = snapshot(world);
        Path generator = WorldStructureApi.worldgenDataPath(world);
        byte[] oldGenerator = Files.readAllBytes(generator);
        CompoundTag root = read(world);
        CompoundTag current = WorldStructureCreator.createMirrorWorldGenSettings();
        if (generator.equals(world.resolve("level.dat"))) {
            root.getCompoundOrEmpty("Data").put("WorldGenSettings", current);
        } else root.put("data", current);
        write(world, root);
        byte[] replacement = Files.readAllBytes(generator);
        Files.write(generator, oldGenerator);
        Path metadata = world.resolve(WorldMetadata.FILE_NAME);
        byte[] oldMetadata = Files.readAllBytes(metadata);
        byte[] newMetadata = (Files.readString(metadata) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path journal = Files.createDirectory(world.resolve(FileTransaction.PREFIX + "interrupted-upgrade"));
        Files.createDirectory(journal.resolve("old"));
        Properties manifest = new Properties();
        manifest.setProperty("schema", "2");
        manifest.setProperty("count", "2");
        Path[] files = { generator, metadata };
        byte[][] previous = { oldGenerator, oldMetadata }, next = { replacement, newMetadata };
        for (int i = 0; i < files.length; i++) {
            Path backup = Files.write(journal.resolve("old/" + i), previous[i]);
            manifest.setProperty("path." + i, world.relativize(files[i]).toString());
            manifest.setProperty("existed." + i, "true");
            manifest.setProperty("sha256." + i, FileProof.sha256(backup));
            manifest.setProperty("new." + i, java.util.HexFormat.of().formatHex(FileProof.sha256().digest(next[i])));
        }
        try (var output = Files.newOutputStream(journal.resolve("prepared"))) { manifest.store(output, "interrupted migration fixture"); }
        Files.writeString(journal.resolve("manifest.sha256"), FileProof.sha256(journal.resolve("prepared")));
        Files.writeString(world.resolve(WorldRecovery.MARKER), "interrupted migration fixture");
        Files.write(generator, replacement); // Crash after the first replacement, before metadata.
        if (externalEdit) {
            String external = "{\"external_edit\":true}";
            Files.writeString(metadata, external);
            assertThrows(java.io.IOException.class, () -> MirrorMigrationCoordinator.recoverAndInspect(world));
            assertArrayEquals(replacement, Files.readAllBytes(generator));
            assertEquals(external, Files.readString(metadata));
            assertArrayEquals(oldGenerator, Files.readAllBytes(journal.resolve("old/0")));
            assertArrayEquals(oldMetadata, Files.readAllBytes(journal.resolve("old/1")));
            assertTrue(WorldRecovery.required(world));
            return;
        }
        assertTrue(MirrorMigrationCoordinator.recoverAndInspect(world).repairWorldgen());
        assertSnapshot(world, original);
        assertFalse(Files.exists(journal));
        assertFalse(WorldRecovery.required(world));
        assertTrue(MirrorMigrationCoordinator.inspect(world).repairWorldgen());
        assertTrue(MirrorMigrationCoordinator.migrateApproved(world).success());
    }

    private static void createOldWorld(Path world) throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(world, "test", true, true, WorldSettingsSnapshot.defaults()));
        WorldMetadata metadata = WorldMetadata.create("local:repair", "singleplayer", "synchronized");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), MirrorWorldgenAssets.ASSET_REVISION);
        assertTrue(metadata.save(world));
        CompoundTag root = read(world);
        CompoundTag flat = TagParser.parseCompoundFully("{type:'minecraft:flat',settings:{layers:[{block:'minecraft:air',height:1}],structure_overrides:[],biome:'minecraft:the_void'}}");
        CompoundTag dimensions = WorldStructureApi.worldgenData(root).getCompoundOrEmpty("dimensions");
        for (var dimension : MirrorWorldgenDefinition.DIMENSIONS) {
            dimensions.getCompoundOrEmpty(dimension.dimensionType()).put("generator", flat.copy());
        }
        write(world, root);
    }

    private static CompoundTag read(Path world) throws Exception {
        return NbtIo.readCompressed(WorldStructureApi.worldgenDataPath(world), NbtAccounter.unlimitedHeap());
    }

    private static void write(Path world, CompoundTag root) throws Exception {
        NbtIo.writeCompressed(root, WorldStructureApi.worldgenDataPath(world));
    }

    private static Map<Path, byte[]> snapshot(Path world) throws Exception {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (String relative : Arrays.asList("level.dat", WorldMetadata.FILE_NAME,
                "data/minecraft/world_gen_settings.dat", "data/minecraft/world_clocks.dat",
                "datapacks/worldmirror_environment/pack.mcmeta",
                "datapacks/worldmirror_environment/data/worldmirror/worldgen/biome/mirror_overworld.json")) {
            Path file = world.resolve(relative);
            if (Files.exists(file)) files.put(file, Files.readAllBytes(file));
        }
        return files;
    }

    private static void assertSnapshot(Path world, Map<Path, byte[]> expected) throws Exception {
        for (var file : expected.entrySet()) assertArrayEquals(file.getValue(), Files.readAllBytes(file.getKey()), world.relativize(file.getKey()).toString());
    }
}
