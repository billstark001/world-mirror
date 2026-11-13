package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.SeedChunkProvenance;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SeedConversionTest {
    private static ChunkPos PLACEHOLDER;
    private static ChunkPos CAPTURE;
    private static ChunkPos GENERATED;
    private static int dataVersion;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        PLACEHOLDER = new ChunkPos(0, 0);
        CAPTURE = new ChunkPos(1, 0);
        GENERATED = new ChunkPos(2, 0);
    }

    @Test
    void setAndRemovePreserveCapturedTerrainAndRetireOnlyVerifiedGeneration(@TempDir Path world)
            throws Exception {
        var target = mirror(world);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var air = terrain("minecraft:air", "worldmirror:mirror_overworld");
        var captured = terrain("minecraft:gold_block", "worldmirror:mirror_overworld");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(PLACEHOLDER, air, CAPTURE, captured));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(index.recordUpdates("minecraft:overworld", Map.of(CAPTURE, 42L), "world_mirror"));
        }

        var set = SeedConversion.preview(target, SeedConversion.Request.set(25L, true));
        assertEquals(1, set.preview().removed());
        assertEquals(1, set.preview().repairedBiomes());
        assertEquals(1, set.preview().blended());
        assertTrue(Files.isRegularFile(SeedConversion.apply(set)));
        assertEquals(25L, WorldStructureCreator.readSeed(world));
        assertNull(AnvilReader.readChunk(region, PLACEHOLDER));
        var preserved = AnvilReader.readChunk(region, CAPTURE);
        assertEquals("minecraft:plains", biome(preserved));
        assertTrue(preserved.contains("blending_data"));
        assertFalse(Files.exists(world.resolve("datapacks")
                        .resolve(MirrorWorldgenAssets.PACK_DIRECTORY)
                        .resolve("pack.mcmeta")));

        var metadata = WorldMetadata.loadIfPresent(world).orElseThrow();
        var seedChunk = generated(metadata, false);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(GENERATED, seedChunk));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            index.recordSeedGeneratedIfMissing("minecraft:overworld", Set.of(GENERATED));
        }
        assertEquals(
                0, SeedConversion.preview(target, SeedConversion.Request.remove(false)).preview().removed());
        var remove = SeedConversion.preview(target, SeedConversion.Request.remove(true));
        assertEquals(1, remove.preview().removed());
        assertTrue(Files.isRegularFile(SeedConversion.apply(remove)));
        assertNull(AnvilReader.readChunk(region, GENERATED));
        assertFalse(AnvilReader.readChunk(region, CAPTURE).contains("blending_data"));
        var records = ChunkDatabase.queryAllReadOnly(world, target.sourceId(), "minecraft:overworld");
        assertEquals(1, records.size());
        assertNotEquals(ChunkIndexStore.SEED_GENERATED, records.getFirst().updateSource());
        assertEquals(WorldMetadata.GenerationMode.VOID,
                WorldMetadata.loadIfPresent(world).orElseThrow().generation.mode());
    }

    @Test
    void borderRegenerationAndReseedingKeepEditedAndOlderChunks(@TempDir Path world) throws Exception {
        var target = mirror(world);
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var edited = new ChunkPos(1, 1);
        var occupied = new ChunkPos(2, 1);
        var distant = new ChunkPos(9, 9);
        var secondRing = new ChunkPos(3, 0);
        McaWriteSupport.writeChunks(
                region, Level.OVERWORLD, Map.of(CAPTURE, terrain("minecraft:stone", "minecraft:plains")));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(index.recordUpdates("minecraft:overworld", Map.of(CAPTURE, 42L), "world_mirror"));
        }
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(11, true)));
        var original = WorldMetadata.loadIfPresent(world).orElseThrow();
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(GENERATED, generated(original, false), edited, generated(original, true), distant,
                        generated(original, false), occupied, generated(original, false), secondRing,
                        generated(original, false)));
        var entityData = new CompoundTag();
        var records = new ListTag();
        var entity = new CompoundTag();
        entity.putString("id", "minecraft:item");
        records.add(entity);
        entityData.put("Entities", records);
        Path entities = region.getParent().resolveSibling("entities").resolve(region.getFileName());
        McaWriteSupport.writeChunks(entities, Level.OVERWORLD, Map.of(occupied, entityData));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            index.recordSeedGeneratedIfMissing(
                    "minecraft:overworld", Set.of(GENERATED, distant, occupied, secondRing));
        }
        var border = SeedConversion.preview(target, SeedConversion.Request.regenerateBorder());
        assertEquals(2, border.preview().removed());
        SeedConversion.apply(border);
        assertNull(AnvilReader.readChunk(region, GENERATED));
        assertNull(AnvilReader.readChunk(region, secondRing));
        assertNotNull(AnvilReader.readChunk(region, edited));
        assertNotNull(AnvilReader.readChunk(region, distant));
        assertNotNull(AnvilReader.readChunk(region, occupied));
        assertEquals(entityData, AnvilReader.readChunk(entities, occupied));
        var reseed = SeedConversion.preview(target, SeedConversion.Request.reseed(22, false));
        assertEquals(0, reseed.preview().removed());
        SeedConversion.apply(reseed);
        var latest = WorldMetadata.loadIfPresent(world).orElseThrow();
        assertEquals(22, WorldStructureCreator.readSeed(world));
        assertNotEquals(original.generation.seedEpoch(), latest.generation.seedEpoch());
        assertFalse(AnvilReader.readChunk(region, CAPTURE).contains("blending_data"));
        assertEquals(
                0, SeedConversion.preview(target, SeedConversion.Request.remove(true)).preview().removed());
        assertTrue(SeedChunkProvenance.isUnmodified(AnvilReader.readChunk(region, distant),
                SeedChunkProvenance.read(AnvilReader.readChunk(region, distant))));
    }

    @Test
    void borderRegenerationRequiresAUsableCapturedDonor(@TempDir Path world) throws Exception {
        var target = mirror(world);
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        var partialPos = new ChunkPos(1, 1);
        var emptyPos = new ChunkPos(1, 12);
        var malformedPos = new ChunkPos(12, 1);
        var validPos = new ChunkPos(20, 20);
        var partial = terrain("minecraft:stone", "minecraft:plains");
        partial.putString("Status", "minecraft:biomes");
        var empty = terrain("minecraft:air", "minecraft:plains");
        var malformed = terrain("minecraft:stone", "minecraft:plains");
        malformed.putString(SeedTerrainDigest.BLENDING_DATA, "invalid");
        var valid = terrain("minecraft:stone", "minecraft:plains");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(partialPos, partial, emptyPos, empty, malformedPos, malformed, validPos, valid));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            assertTrue(index.recordUpdates("minecraft:overworld",
                    Map.of(partialPos, 42L, emptyPos, 43L, malformedPos, 44L, validPos, 45L),
                    "world_mirror"));
        }
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(11, true)));
        var metadata = WorldMetadata.loadIfPresent(world).orElseThrow();
        var partialNeighbor = new ChunkPos(2, 1);
        var emptyNeighbor = new ChunkPos(2, 12);
        var malformedNeighbor = new ChunkPos(13, 1);
        var validNeighbor = new ChunkPos(21, 20);
        McaWriteSupport.writeChunks(region, Level.OVERWORLD,
                Map.of(partialNeighbor, generated(metadata, false), emptyNeighbor, generated(metadata, false),
                        malformedNeighbor, generated(metadata, false), validNeighbor,
                        generated(metadata, false)));
        try (var index = ChunkDatabase.open(world, target.sourceId())) {
            index.recordSeedGeneratedIfMissing("minecraft:overworld",
                    Set.of(partialNeighbor, emptyNeighbor, malformedNeighbor, validNeighbor));
        }

        var preview = SeedConversion.preview(target, SeedConversion.Request.regenerateBorder());
        assertEquals(1, preview.preview().removed());
        SeedConversion.apply(preview);
        assertNotNull(AnvilReader.readChunk(region, partialNeighbor));
        assertNotNull(AnvilReader.readChunk(region, emptyNeighbor));
        assertNotNull(AnvilReader.readChunk(region, malformedNeighbor));
        assertNull(AnvilReader.readChunk(region, validNeighbor));
    }

    @Test
    void rejectsAStaleApproval(@TempDir Path world) throws Exception {
        var target = mirror(world);
        var plan = SeedConversion.preview(target, SeedConversion.Request.set(9, false));
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(
                region, Level.OVERWORLD, Map.of(PLACEHOLDER, terrain("minecraft:stone", "minecraft:plains")));
        assertThrows(java.io.IOException.class, () -> SeedConversion.apply(plan));
        assertEquals(WorldMetadata.GenerationMode.VOID,
                WorldMetadata.loadIfPresent(world).orElseThrow().generation.mode());
    }

    @Test
    void seedApplyMaterializesAnEmptyReservationWithoutChangingItsIdentity(@TempDir Path world)
            throws Exception {
        String node = "mirror:reserved-seed";
        String parent = "remote:reserved-seed-source";
        MirrorClaim.create(world, node, parent, "physical:reserved-seed");
        var initial = MirrorClaim.read(world);
        Path previousBackup = Files.createDirectory(world.resolve("backups")).resolve("existing.zip");
        Files.writeString(previousBackup, "stand-in for an earlier failed publication's backup");

        assertFalse(SeedConversion.inspectTarget(world, node).seeded());
        var preview = SeedConversion.preview(world, node, SeedConversion.Request.set(31, false));
        assertEquals(node, preview.nodeId());
        assertFalse(Files.exists(world.resolve(WorldMetadata.FILE_NAME)));
        assertFalse(Files.exists(world.resolve("level.dat")));
        assertEquals(initial, MirrorClaim.read(world));
        assertThrows(java.io.IOException.class,
                ()
                        -> SeedConversion.preview(
                                world, "mirror:not-this-one", SeedConversion.Request.set(31, false)));

        assertTrue(Files.isRegularFile(SeedConversion.apply(preview)));
        var persisted = WorldMetadata.loadIfPresent(world).orElseThrow();
        assertEquals(node, persisted.nodeId);
        assertEquals(parent, persisted.parentNodeId);
        assertEquals(initial.sourceId(), persisted.sourceId);
        assertEquals(31, WorldStructureCreator.readSeed(world));
        assertFalse(Files.exists(world.resolve(MirrorClaim.FILE_NAME)));
        assertTrue(Files.isRegularFile(previousBackup));
        assertTrue(SeedConversion.inspectTarget(world, node).seeded());
    }

    @Test
    void orphanedOwnedVoidAdoptsItsSoleIndexNamespaceOnlyWhenApplied(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createVoidWorld(
                world, "Orphan fixture", WorldSettingsSnapshot.defaults()));
        var region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(
                region, Level.OVERWORLD, Map.of(CAPTURE, terrain("minecraft:stone", "minecraft:plains")));
        String namespace = "original namespace";
        try (var index = ChunkDatabase.open(world, namespace)) {
            assertTrue(index.recordUpdates("minecraft:overworld", Map.of(CAPTURE, 42L), "world_mirror"));
        }
        assertTrue(SeedConversion.isAdoptableVoid(world));
        assertFalse(SeedConversion.inspectTarget(world, null).seeded());
        var preview = SeedConversion.preview(world, null, SeedConversion.Request.set(71, true));
        assertFalse(Files.exists(world.resolve(WorldMetadata.FILE_NAME)));
        assertFalse(Files.exists(world.resolve(LocalWorldIdentity.FILE_NAME)));
        assertEquals(1, preview.preview().blended());

        assertTrue(Files.isRegularFile(SeedConversion.apply(preview)));
        var persisted = WorldMetadata.loadIfPresent(world).orElseThrow();
        assertEquals(preview.nodeId(), persisted.nodeId);
        assertEquals(WorldGraph.Kind.SNAPSHOT, persisted.kind);
        assertNull(persisted.parentNodeId);
        assertEquals(namespace, persisted.sourceId);
        assertEquals(42L,
                ChunkDatabase.queryAllReadOnly(world, namespace, "minecraft:overworld")
                        .getFirst()
                        .updateTime());
        assertFalse(Files.exists(world.resolve(LocalWorldIdentity.FILE_NAME)));
    }

    @Test
    void orphanAdoptionRejectsModifiedAssetsAndExistingLocalIdentity(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createVoidWorld(
                world, "Modified fixture", WorldSettingsSnapshot.defaults()));
        Path pack = world.resolve("datapacks").resolve(MirrorWorldgenAssets.PACK_DIRECTORY);
        Path extra = Files.writeString(pack.resolve("custom.json"), "{}");
        assertTrue(SeedConversion.hasOrphanVoidGenerator(world));
        assertFalse(SeedConversion.isAdoptableVoid(world));
        assertThrows(java.io.IOException.class,
                () -> SeedConversion.preview(world, null, SeedConversion.Request.set(9, false)));
        Files.delete(extra);
        try (var ignored = io.github.billstark001.worldmirror.io.WorldWriteLock.acquire(world)) {
            LocalWorldIdentity.ensure(world);
        }
        assertFalse(SeedConversion.hasOrphanVoidGenerator(world));
        assertFalse(SeedConversion.isAdoptableVoid(world));
        assertThrows(java.io.IOException.class,
                () -> SeedConversion.preview(world, null, SeedConversion.Request.set(9, false)));
        assertFalse(Files.exists(world.resolve(WorldMetadata.FILE_NAME)));
    }

    @Test
    void orphanAdoptionRejectsAnAmbiguousIndex(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createVoidWorld(
                world, "Ambiguous fixture", WorldSettingsSnapshot.defaults()));
        for (String source : Set.of("old namespace A", "old namespace B")) {
            try (var index = ChunkDatabase.open(world, source)) {
                assertTrue(index.recordUpdates("minecraft:overworld", Map.of(CAPTURE, 42L), "world_mirror"));
            }
        }
        assertTrue(SeedConversion.hasOrphanVoidGenerator(world));
        assertFalse(SeedConversion.isAdoptableVoid(world));
        assertThrows(java.sql.SQLException.class,
                () -> SeedConversion.preview(world, null, SeedConversion.Request.set(9, false)));
        assertFalse(Files.exists(world.resolve(WorldMetadata.FILE_NAME)));
    }

    private static MirrorTarget mirror(Path world) {
        assertTrue(WorldStructureCreator.createVoidWorld(
                world, "Seed fixture", WorldSettingsSnapshot.defaults()));
        var metadata = WorldMetadata.create("mirror:seed-fixture", WorldGraph.Kind.MIRROR,
                "local:seed-source", WorldGraph.Role.SYNC, "local:seed-source");
        metadata.markWorldgenCurrent(dataVersion, MirrorWorldgenAssets.ASSET_REVISION);
        assertTrue(metadata.save(world));
        return new MirrorTarget(world, metadata.nodeId, metadata.sourceId);
    }

    private static CompoundTag generated(WorldMetadata metadata, boolean edited) {
        var chunk = terrain("minecraft:stone", "minecraft:plains");
        var entry = SeedChunkProvenance.baseline(chunk, metadata.generation.seedEpoch(), dataVersion);
        var marker = new CompoundTag();
        marker.putInt(SeedTerrainDigest.SEED_SCHEMA, SeedTerrainDigest.SEED_SCHEMA_VERSION);
        marker.putString(SeedTerrainDigest.EPOCH, entry.epoch().toString());
        marker.putInt(SeedTerrainDigest.DATA_VERSION, dataVersion);
        marker.putString(SeedTerrainDigest.BASELINE, entry.baseline());
        marker.putBoolean(SeedTerrainDigest.SEED_EDITED, edited);
        var attachments = new CompoundTag();
        attachments.put(SeedTerrainDigest.ATTACHMENT, marker);
        chunk.put("fabric:attachments", attachments);
        return chunk;
    }

    private static CompoundTag terrain(String blockId, String biomeId) {
        var blocks = new ListTag();
        var block = new CompoundTag();
        block.putString("Name", blockId);
        blocks.add(block);
        var biomes = new ListTag();
        biomes.add(StringTag.valueOf(biomeId));
        var blockData = new CompoundTag();
        blockData.put("palette", blocks);
        var biomeData = new CompoundTag();
        biomeData.put("palette", biomes);
        var section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.put("block_states", blockData);
        section.put("biomes", biomeData);
        var sections = new ListTag();
        sections.add(section);
        var chunk = new CompoundTag();
        chunk.put("sections", sections);
        chunk.putInt("DataVersion", dataVersion);
        chunk.putString("Status", "minecraft:full");
        return chunk;
    }

    private static String biome(CompoundTag chunk) {
        return chunk.getListOrEmpty("sections")
                .getCompoundOrEmpty(0)
                .getCompoundOrEmpty("biomes")
                .getListOrEmpty("palette")
                .getStringOr(0, "");
    }
}
