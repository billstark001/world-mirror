package io.github.billstark001.worldmirror.io;

import io.github.ensgijs.nbt.io.CompressionType;
import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.tag.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McaWriteSupportTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void oversizedSerializedEntryCannotReplaceTheLastGoodRegion(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        McaRegionFile region = new McaRegionFile(0, 0);
        region.setChunk(0, 0, chunk(new byte[1]));
        McaWriteSupport.writeAtomically(region, file);
        byte[] original = Files.readAllBytes(file);
        byte[] noise = new byte[1_200_000];
        new Random(46L).nextBytes(noise);
        region.setChunk(0, 0, chunk(noise));

        assertThrows(IOException.class, () -> McaWriteSupport.writeAtomically(region, file));

        assertArrayEquals(original, Files.readAllBytes(file));
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void uncheckedSerializationFailuresAlsoCleanUpTemporaryFiles(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("r.0.0.mca");
        McaRegionFile region = new McaRegionFile(0, 0);
        region.setChunk(0, 0, new TerrainChunk() {
            @Override
            public int serialize(DataOutput sink, int x, int z, CompressionType compression, boolean prefix) {
                throw new IllegalStateException("fixture serialization failure");
            }
        });

        assertThrows(IllegalStateException.class, () -> McaWriteSupport.writeAtomically(region, file));

        try (var files = Files.list(directory)) {
            assertEquals(0L, files.count());
        }
    }

    private static TerrainChunk chunk(byte[] payload) {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        nbt.putInt("xPos", 0);
        nbt.putInt("zPos", 0);
        nbt.putString("Status", "minecraft:full");
        nbt.putByteArray("payload", payload);
        return new TerrainChunk(nbt);
    }
}
