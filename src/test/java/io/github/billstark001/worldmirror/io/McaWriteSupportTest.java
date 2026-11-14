package io.github.billstark001.worldmirror.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McaWriteSupportTest {
    @Test
    void allocatedButUndecodableChunksAreNotTreatedAsMissing(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        var bytes = java.nio.ByteBuffer.allocate(3 * 4096);
        bytes.putInt(0, 2 << 8 | 1);
        Files.write(file, bytes.array());
        var info = new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                "test", Level.OVERWORLD, "chunk");
        try (var reader = new net.minecraft.world.level.chunk.storage.RegionFile(
                     info, file, directory, false)) {
            assertThrows(IOException.class, () -> McaWriteSupport.readChunk(reader, new ChunkPos(0, 0)));
            org.junit.jupiter.api.Assertions.assertNull(
                    McaWriteSupport.readChunk(reader, new ChunkPos(1, 0)));
        }
        assertArrayEquals(bytes.array(), Files.readAllBytes(file));
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void oversizedSerializedEntryUsesVerifiedExternalPayload(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        byte[] noise = new byte[1_200_000];
        new Random(46L).nextBytes(noise);
        CompoundTag nbt = chunk(noise);
        RegionWriteTransaction.rewrite(
                file, Level.OVERWORLD, 0, 0, java.util.Set.of(new ChunkPos(0, 0)), (pos, previous) -> nbt);
        assertEquals(nbt, AnvilReader.readChunk(file, new ChunkPos(0, 0)));
        org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(directory.resolve("c.0.0.mcc")));
    }

    @Test
    void lossyStringsLeaveTheLastGoodRegionUntouched(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        CompoundTag nbt = chunk(new byte[1]);
        RegionWriteTransaction.rewrite(
                file, Level.OVERWORLD, 0, 0, java.util.Set.of(new ChunkPos(0, 0)), (pos, previous) -> nbt);
        byte[] original = Files.readAllBytes(file);
        nbt.putString("value", "x".repeat(70_000));
        assertThrows(IOException.class,
                ()
                        -> RegionWriteTransaction.rewrite(file, Level.OVERWORLD, 0, 0,
                                java.util.Set.of(new ChunkPos(0, 0)), (pos, previous) -> nbt));
        assertArrayEquals(original, Files.readAllBytes(file));
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void sparseStagingPreservesUneditedSectorsAndExternalChunks(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        Path destination = directory.resolve("staged/r.0.0.mca");
        var retained = new ChunkPos(0, 0);
        var removed = new ChunkPos(1, 0);
        var edited = new ChunkPos(2, 0);
        var external = new ChunkPos(3, 0);
        var externalized = new ChunkPos(4, 0);
        var toRemove = new byte[1_200_000];
        new Random(46L).nextBytes(toRemove);
        var preserved = chunk(new byte[] {42});
        var updated = chunk(new byte[] {88});
        McaWriteSupport.writeChunks(file, Level.OVERWORLD,
                Map.of(retained, preserved, removed, chunk(toRemove), edited, preserved, external,
                        chunk(toRemove), externalized, preserved));
        byte[] original = Files.readAllBytes(file);
        byte[] removedPayload = Files.readAllBytes(AnvilReader.externalFile(directory, removed));
        byte[] retainedPayload = Files.readAllBytes(AnvilReader.externalFile(directory, external));

        RegionWriteTransaction.stageSparse(file, destination, Level.OVERWORLD, 0, 0,
                Set.of(edited, removed, externalized), (pos, before) -> {
                    if (pos.equals(removed)) {
                        assertEquals(chunk(toRemove), before);
                        return null;
                    }
                    assertEquals(preserved, before);
                    return pos.equals(externalized) ? chunk(toRemove) : updated;
                });

        assertEquals(Set.of(retained, edited, external, externalized),
                McaWriteSupport.listChunkPositions(destination, 0, 0));
        assertNull(AnvilReader.readChunk(destination, removed));
        assertEquals(updated, AnvilReader.readChunk(destination, edited));
        assertEquals(preserved, AnvilReader.readChunk(destination, retained));
        assertEquals(chunk(toRemove), AnvilReader.readChunk(destination, externalized));
        assertFalse(Files.exists(AnvilReader.externalFile(destination.getParent(), external)));
        assertFalse(Files.exists(AnvilReader.externalFile(destination.getParent(), removed)));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertArrayEquals(removedPayload, Files.readAllBytes(AnvilReader.externalFile(directory, removed)));
        assertArrayEquals(retainedPayload, Files.readAllBytes(AnvilReader.externalFile(directory, external)));
        var before = ByteBuffer.wrap(original);
        var after = ByteBuffer.wrap(Files.readAllBytes(destination));
        for (int slot : new int[] {0, 3}) {
            assertEquals(before.getInt(slot * 4), after.getInt(slot * 4));
            assertEquals(before.getInt(4096 + slot * 4), after.getInt(4096 + slot * 4));
        }
        Files.copy(AnvilReader.externalFile(directory, external),
                AnvilReader.externalFile(destination.getParent(), external));
        assertEquals(chunk(toRemove), AnvilReader.readChunk(destination, external));
    }

    private static CompoundTag chunk(byte[] payload) {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        nbt.putInt("xPos", 0);
        nbt.putInt("zPos", 0);
        nbt.putString("Status", "minecraft:full");
        nbt.putByteArray("payload", payload);
        return nbt;
    }
}
