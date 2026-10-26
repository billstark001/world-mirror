package io.github.billstark001.worldmirror.io;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
}
