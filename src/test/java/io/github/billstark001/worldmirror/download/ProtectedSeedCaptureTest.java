package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.conflict.IgnoreResolver;
import io.github.billstark001.worldmirror.conflict.ManualResolver;
import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.ExportProgress;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedSeedCaptureTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static ChunkPos pos;

    @BeforeAll
    static void bootstrap() {
        SeedConversionTest.bootstrap();
        pos = new ChunkPos(0, 0);
    }

    @Test
    void editedSeedRetainsTheDownloadUntilExplicitlyApproved(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        Files.createDirectories(world.resolve("data"));
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(25, false)));
        var metadata = WorldMetadata.loadIfPresent(world).orElseThrow();
        var local = atPosition(SeedConversionTest.generated(metadata, true));
        var incoming = atPosition(SeedConversionTest.terrain("minecraft:sand", "minecraft:ocean"));
        Path region = WorldSaveLayout.regionDirectory(world, Level.OVERWORLD).resolve("r.0.0.mca");
        McaWriteSupport.writeChunks(region, Level.OVERWORLD, Map.of(pos, local));

        assertTrue(export(target, incoming, 100, new IgnoreResolver()).successful());
        assertTrue(ConflictManager.listConflicts(world, Level.OVERWORLD).isEmpty());

        var staged = export(target, incoming, 200, new OverwriteResolver());
        assertTrue(staged.successful());
        assertFalse(staged.remaining().hasTerrain());
        assertEquals(world, staged.changedSavedData());
        assertEquals(1, staged.result().pendingConflicts());
        assertEquals(local, AnvilReader.readChunk(region, pos));
        assertEquals(Set.of(pos), ConflictManager.listConflicts(world, Level.OVERWORLD));
        try (var db = ChunkDatabase.open(world, target.sourceId())) {
            assertNull(db.queryChunk(DIMENSION, pos));
        }

        Path pending = ConflictManager.getConflictDir(world, Level.OVERWORLD).resolve("r.0.0.mca");
        byte[] pendingBytes = Files.readAllBytes(pending);
        var repeat = export(target, incoming, 300, new OverwriteResolver());
        assertTrue(repeat.successful());
        assertArrayEquals(pendingBytes, Files.readAllBytes(pending));
        assertTrue(ConflictManager.resolveConflict(target, pos, Level.OVERWORLD, true));

        assertEquals(incoming.get("sections"), AnvilReader.readChunk(region, pos).get("sections"));
        assertTrue(ConflictManager.listConflicts(world, Level.OVERWORLD).isEmpty());
        try (var db = ChunkDatabase.open(world, target.sourceId())) {
            var row = db.queryChunk(DIMENSION, pos);
            assertEquals(200, row.updateTime());
            assertEquals("world_mirror", row.updateSource());
        }

        var manual = atPosition(SeedConversionTest.terrain("minecraft:dirt", "minecraft:plains"));
        assertEquals(1, export(target, manual, 400, new ManualResolver()).result().pendingConflicts());
        assertTrue(ConflictManager.resolveConflict(target, pos, Level.OVERWORLD, true));
        try (var db = ChunkDatabase.open(world, target.sourceId())) {
            assertEquals(400, db.queryChunk(DIMENSION, pos).updateTime());
        }
    }

    private static CompoundTag atPosition(CompoundTag chunk) {
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        return chunk;
    }

    private static MirrorExportTransaction.ExportOutcome export(
            MirrorTarget target, CompoundTag chunk, long timestamp, ConflictResolver resolver) {
        var capture = new ChunkListener.CapturedChunk(chunk, timestamp, timestamp, false);
        var snapshot = ChunkListener.snapshotOf(Map.of(Level.OVERWORLD, Map.of(pos, capture)));
        var session = ExportTestTargets.session(target.nodeId(), target.sourceId(), target.sourceId());
        var work = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.MANUAL, false, session),
                snapshot, Map.of(), Map.of(), 0, OptionalLong.empty());
        return MirrorExportTransaction.write(work, target::folder, resolver, -1L, ExportProgress.NONE);
    }
}
