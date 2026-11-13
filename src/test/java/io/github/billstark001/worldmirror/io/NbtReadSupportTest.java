package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NbtReadSupportTest {
    @Test
    void deletingADamagedFileDoesNotPermanentlyBlockItsDirectory(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Path file = java.nio.file.Files.write(root.resolve("damaged.dat"), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> NbtReadSupport.readWorldData(file));
        java.nio.file.Files.delete(file);
        assertDoesNotThrow(() -> FileReadFailures.check(file));
    }

    @Test
    void declaredHugeArraysAreRejectedBeforeAllocating() throws Exception {
        for (int type : new int[] {7, 11, 12}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(10); output.writeUTF("");
                output.writeByte(type); output.writeUTF("huge"); output.writeInt(Integer.MAX_VALUE);
            }
            assertThrows(IOException.class, () -> NbtReadSupport.readChunk(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        }
    }

    @Test
    void bufferedProofCoversExactlyTheBytesOfOneChunk() throws IOException {
        var chunk = new CompoundTag();
        chunk.putByteArray("data", new byte[16_384]);
        var bytes = new ByteArrayOutputStream();
        byte[] expected = NbtWriteSupport.writeChunkAndDigest(chunk, new DataOutputStream(bytes));
        byte[] serialized = bytes.toByteArray();
        assertArrayEquals(FileProof.sha256().digest(serialized), expected);
        assertTrue(NbtReadSupport.verifyChunk(new DataInputStream(new ByteArrayInputStream(serialized)), expected));

        byte[] trailing = Arrays.copyOf(serialized, serialized.length + 1);
        assertFalse(NbtReadSupport.verifyChunk(new DataInputStream(new ByteArrayInputStream(trailing)),
                FileProof.sha256().digest(trailing)));
    }

    @Test
    void bufferedProofIncludesTheRootNameEvenWhenTheNbtReaderSkipsIt() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeByte(10);
            output.writeUTF("name".repeat(10_000));
            output.writeByte(0);
        }
        byte[] serialized = bytes.toByteArray();
        assertTrue(NbtReadSupport.verifyChunk(new DataInputStream(new ByteArrayInputStream(serialized)),
                FileProof.sha256().digest(serialized)));
    }
}
