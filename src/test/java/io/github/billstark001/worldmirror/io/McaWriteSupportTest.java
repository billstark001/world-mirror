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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McaWriteSupportTest {
    @Test
    void allocatedButUndecodableChunksAreNotTreatedAsMissing(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        var bytes = java.nio.ByteBuffer.allocate(3 * 4096);
        bytes.putInt(0, 2 << 8 | 1);
        Files.write(file, bytes.array());
        var info = new net.minecraft.world.level.chunk.storage.RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        try (var reader = new net.minecraft.world.level.chunk.storage.RegionFile(info, file, directory, false)) {
            assertThrows(IOException.class, () -> McaWriteSupport.readChunk(reader, new ChunkPos(0, 0)));
            org.junit.jupiter.api.Assertions.assertNull(McaWriteSupport.readChunk(reader, new ChunkPos(1, 0)));
        }
        assertArrayEquals(bytes.array(), Files.readAllBytes(file));
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void oversizedSerializedEntryUsesVerifiedExternalPayload(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        byte[] noise = new byte[1_200_000];
        new Random(46L).nextBytes(noise);
        CompoundTag nbt = chunk(noise);
        RegionWriteTransaction.rewrite(file, Level.OVERWORLD, 0, 0, java.util.Set.of(new ChunkPos(0, 0)), (pos, previous) -> nbt);
        assertEquals(nbt, AnvilReader.readChunk(file, new ChunkPos(0, 0)));
        org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(directory.resolve("c.0.0.mcc")));
    }

    @Test
    void lossyStringsLeaveTheLastGoodRegionUntouched(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        CompoundTag nbt = chunk(new byte[1]);
        RegionWriteTransaction.rewrite(file, Level.OVERWORLD, 0, 0, java.util.Set.of(new ChunkPos(0, 0)), (pos, previous) -> nbt);
        byte[] original = Files.readAllBytes(file);
        nbt.putString("value", "x".repeat(70_000));
        assertThrows(IOException.class, () -> RegionWriteTransaction.rewrite(file, Level.OVERWORLD,
                0, 0, java.util.Set.of(new ChunkPos(0, 0)), (pos, previous) -> nbt));
        assertArrayEquals(original, Files.readAllBytes(file));
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
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
