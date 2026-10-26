package io.github.billstark001.worldmirror.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.DataInput;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;

/** Finite allocation budgets for untrusted on-disk NBT, including external chunks. */
public final class NbtReadSupport {
    private static final long CHUNK_BYTES = 64L * 1024 * 1024;
    private static final long WORLD_DATA_BYTES = 128L * 1024 * 1024;

    private NbtReadSupport() {}

    public static CompoundTag readChunk(DataInput input) throws IOException {
        try {
            return NbtIo.read(input, NbtAccounter.create(CHUNK_BYTES));
        } catch (net.minecraft.nbt.NbtAccounterException limit) {
            throw new IOException("Chunk NBT exceeds allocation/depth policy", limit);
        } catch (RuntimeException invalid) {
            throw new FileReadFailures.InvalidData("Invalid chunk NBT", invalid);
        }
    }

    /** Checks decodability and exact serialized content without retaining another chunk tree. */
    static boolean verifyChunk(java.io.DataInputStream nativeInput, byte[] expected) throws IOException {
        var digest = io.github.billstark001.worldmirror.util.FileProof.sha256();
        CompoundTag decoded = readChunk(new java.io.DataInputStream(new java.security.DigestInputStream(nativeInput, digest)));
        return decoded != null && nativeInput.read() == -1 && java.util.Arrays.equals(expected, digest.digest());
    }

    public static CompoundTag readWorldData(Path file) throws IOException {
        var observed = FileReadFailures.check(file);
        if (observed != null && observed.size() > WORLD_DATA_BYTES) throw new IOException("World NBT exceeds compressed-input policy: " + file);
        try (var raw = new java.io.BufferedInputStream(Files.newInputStream(file), 64 * 1024);
             var decoded = net.minecraft.world.level.chunk.storage.RegionFileVersion.VERSION_GZIP.wrap(
                     com.google.common.io.ByteStreams.limit(raw, WORLD_DATA_BYTES));
             var input = new java.io.DataInputStream(decoded)) {
            CompoundTag result = NbtIo.read(input, NbtAccounter.create(WORLD_DATA_BYTES));
            if (input.read() != -1) throw new FileReadFailures.InvalidData("Trailing world NBT: " + file);
            return result;
        } catch (net.minecraft.nbt.NbtAccounterException limit) {
            throw new IOException("World NBT exceeds allocation/depth policy: " + file, limit);
        } catch (RuntimeException invalid) {
            var failure = new FileReadFailures.InvalidData("Invalid or oversized world NBT: " + file, invalid);
            FileReadFailures.record(file, observed, failure);
            throw failure;
        } catch (IOException failure) {
            IOException contextual = failure instanceof java.io.EOFException || failure instanceof java.util.zip.ZipException
                    ? new FileReadFailures.InvalidData("Invalid world NBT: " + file, failure)
                    : new IOException("Cannot read world NBT: " + file, failure);
            FileReadFailures.record(file, observed, contextual);
            throw contextual;
        }
    }
}
