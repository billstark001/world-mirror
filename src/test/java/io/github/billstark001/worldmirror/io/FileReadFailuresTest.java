package io.github.billstark001.worldmirror.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class FileReadFailuresTest {
    @Test
    void aCorrectedFileCannotBeCachedUnderAnEarlierFailedRead(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("data"), "bad");
        var observed = FileReadFailures.check(file);
        Path correction = Files.writeString(root.resolve("replacement"), "good");
        Files.move(correction, file, StandardCopyOption.REPLACE_EXISTING);
        FileReadFailures.record(file, observed, new FileReadFailures.InvalidData("old read"));
        assertDoesNotThrow(() -> FileReadFailures.check(file));
    }

    @Test
    void unchangedMalformedNbtUsesTheNegativeCache(@TempDir Path root) throws Exception {
        Path file = Files.write(root.resolve("data.dat"), new byte[] {1, 2, 3});
        IOException first = assertThrows(IOException.class, () -> NbtReadSupport.readWorldData(file));
        assertSame(first, assertThrows(IOException.class, () -> NbtReadSupport.readWorldData(file)));
        Files.delete(file);
        assertNull(FileReadFailures.check(file));
    }

    @Test
    void gzipTrailerCorruptionCannotAuthorizeARead(@TempDir Path root) throws Exception {
        Path file = root.resolve("r.0.0.mca");
        CompoundTag tag = new CompoundTag(); tag.putString("value", "valid root");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(RegionFileVersion.VERSION_GZIP.wrap(payload))) {
            NbtIo.writeUnnamedTag(tag, output);
        }
        byte[] compressed = payload.toByteArray();
        compressed[compressed.length - 8] ^= 1;
        ByteBuffer container = ByteBuffer.allocate(3 * 4096);
        container.putInt(0, 2 << 8 | 1);
        container.position(8192); container.putInt(compressed.length + 1); container.put((byte) 1); container.put(compressed);
        Files.write(file, container.array());
        byte[] original = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> AnvilReader.readChunk(file, new ChunkPos(0, 0)));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void allocationPolicyFailuresAreNotCachedAsCorruption(@TempDir Path root) throws Exception {
        Path file = root.resolve("r.0.0.mca");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(payload)) {
            output.writeByte(10); output.writeUTF("");
            output.writeByte(7); output.writeUTF("huge"); output.writeInt(Integer.MAX_VALUE);
        }
        ByteBuffer container = ByteBuffer.allocate(3 * 4096);
        container.putInt(0, 2 << 8 | 1);
        container.position(8192); container.putInt(payload.size() + 1); container.put((byte) 3); container.put(payload.toByteArray());
        Files.write(file, container.array());
        assertThrows(IOException.class, () -> AnvilReader.readChunk(file, new ChunkPos(0, 0)));
        assertDoesNotThrow(() -> FileReadFailures.check(file));
    }
}
