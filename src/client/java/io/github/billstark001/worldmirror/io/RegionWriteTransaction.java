package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Streams a region rewrite, retaining content proofs instead of a second region of NBT trees. */
public final class RegionWriteTransaction {
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 2 * SECTOR_BYTES;

    @FunctionalInterface
    public interface Transform {
        CompoundTag apply(ChunkPos pos, CompoundTag previous) throws IOException;
    }
    private RegionWriteTransaction() {}

    public static void rewrite(Path target, ResourceKey<Level> dimension, int regionX, int regionZ,
            Set<ChunkPos> requested, Transform transform) throws IOException {
        synchronized (McaWriteSupport.lockFor(target)) {
            try (FileTransaction transaction = new FileTransaction(target.getParent())) {
                Path staged = transaction.stagingDirectory().resolve(target.getFileName());
                stage(target, staged, dimension, regionX, regionZ, requested, transform);
                Map<Path, Path> replacements = new HashMap<>();
                try (var files = Files.list(transaction.stagingDirectory())) {
                    for (Path file : files.toList()) replacements.put(file.getFileName(), file);
                }
                transaction.commit(replacements);
            }
        }
    }

    /** Writes and verifies a region in the caller's staging tree without publishing it. */
    public static void stage(Path sourceFile, Path staged, ResourceKey<Level> dimension, int regionX,
            int regionZ, Set<ChunkPos> requested, Transform transform) throws IOException {
        Files.createDirectories(staged.getParent());
        Map<ChunkPos, byte[]> proofs = new HashMap<>();
        Set<ChunkPos> missing = new HashSet<>(requested);
        try (AnvilReader.Reader source = AnvilReader.open(sourceFile, regionX, regionZ);
                RegionFile writer = new RegionFile(new RegionStorageInfo("world_mirror", dimension, "chunk"),
                        staged, staged.getParent(), false)) {
            for (ChunkPos pos : source.positions()) {
                write(writer, proofs, pos, transform.apply(pos, source.read(pos)));
                missing.remove(pos);
            }
            for (ChunkPos pos : missing) {
                if (pos.getRegionX() != regionX || pos.getRegionZ() != regionZ)
                    throw new IOException("Foreign region update: " + pos);
                write(writer, proofs, pos, transform.apply(pos, null));
            }
        }
        verify(staged, regionX, regionZ, proofs);
    }

    /** Clones a region and verifies sparse edits while preserving untouched payloads and timestamps. */
    public static void stageSparse(Path sourceFile, Path staged, ResourceKey<Level> dimension, int regionX,
            int regionZ, Set<ChunkPos> changed, Transform transform) throws IOException {
        FileProof.requirePlainFile(sourceFile);
        Files.createDirectories(staged.getParent());
        var before = RegionFileIntegrity.inspect(sourceFile, regionX, regionZ);
        before.requireUsable(sourceFile);
        if (changed.isEmpty() || !before.presentChunks().containsAll(changed))
            throw new IOException("Sparse region edits must refer to existing chunks: " + sourceFile);

        Files.copy(sourceFile, staged);
        var expected = new HashSet<>(before.presentChunks());
        Map<ChunkPos, byte[]> proofs = new HashMap<>();
        try (var source = AnvilReader.open(sourceFile, regionX, regionZ);
                var writer = new RegionFile(new RegionStorageInfo("world_mirror", dimension, "chunk"), staged,
                        staged.getParent(), false)) {
            for (ChunkPos pos : changed) {
                var replacement = transform.apply(pos, source.read(pos));
                if (replacement == null) {
                    writer.clear(pos);
                    expected.remove(pos);
                } else {
                    write(writer, proofs, pos, replacement);
                }
            }
        }
        try (var reader = AnvilReader.open(staged, regionX, regionZ)) {
            verify(reader, staged, expected, proofs);
        }
        verifyUnchanged(sourceFile, staged, before.locations(), changed);
    }

    private static void verifyUnchanged(Path source, Path staged, int[] locations, Set<ChunkPos> changed)
            throws IOException {
        var edited = new boolean[locations.length];
        for (ChunkPos pos : changed) edited[pos.getRegionLocalX() + pos.getRegionLocalZ() * 32] = true;
        try (var old = FileChannel.open(source, StandardOpenOption.READ);
                var now = FileChannel.open(staged, StandardOpenOption.READ)) {
            var oldHeader = read(old, ByteBuffer.allocate(HEADER_BYTES), 0, HEADER_BYTES);
            var newHeader = read(now, ByteBuffer.allocate(HEADER_BYTES), 0, HEADER_BYTES);
            var oldBytes = ByteBuffer.allocate(64 * 1024);
            var newBytes = ByteBuffer.allocate(64 * 1024);
            for (int i = 0; i < locations.length; i++) {
                if (edited[i])
                    continue;
                int location = locations[i];
                if (oldHeader.getInt(i * 4) != location || newHeader.getInt(i * 4) != location
                        || oldHeader.getInt(SECTOR_BYTES + i * 4) != newHeader.getInt(SECTOR_BYTES + i * 4)
                        || !sameSectors(old, now, location, oldBytes, newBytes))
                    throw new IOException("Untouched region entry changed: " + source + " slot=" + i);
            }
        }
    }

    private static boolean sameSectors(FileChannel old, FileChannel now, int location, ByteBuffer oldBytes,
            ByteBuffer newBytes) throws IOException {
        long offset = (long) (location >>> 8) * SECTOR_BYTES;
        int remaining = (location & 255) * SECTOR_BYTES;
        while (remaining > 0) {
            int size = Math.min(oldBytes.capacity(), remaining);
            if (!read(old, oldBytes, offset, size).equals(read(now, newBytes, offset, size)))
                return false;
            offset += size;
            remaining -= size;
        }
        return true;
    }

    private static ByteBuffer read(FileChannel channel, ByteBuffer target, long offset, int bytes)
            throws IOException {
        target.clear().limit(bytes);
        while (target.hasRemaining()) {
            int count = channel.read(target, offset);
            if (count < 0)
                throw new EOFException("Truncated Anvil region");
            offset += count;
        }
        return target.flip();
    }

    private static void write(RegionFile writer, Map<ChunkPos, byte[]> proofs, ChunkPos pos,
            CompoundTag chunk) throws IOException {
        if (chunk == null)
            return;
        proofs.put(pos, NbtWriteSupport.writeChunkAndDigest(chunk, writer.getChunkDataOutputStream(pos)));
    }

    private static void verify(Path file, int regionX, int regionZ, Map<ChunkPos, byte[]> proofs)
            throws IOException {
        try (AnvilReader.Reader reader = AnvilReader.open(file, regionX, regionZ)) {
            verify(reader, file, proofs.keySet(), proofs);
        }
    }

    private static void verify(AnvilReader.Reader reader, Path file, Set<ChunkPos> expected,
            Map<ChunkPos, byte[]> proofs) throws IOException {
        if (!reader.positions().equals(expected))
            throw new IOException("Region entries changed: " + file);
        for (var entry : proofs.entrySet()) {
            if (!reader.matches(entry.getKey(), entry.getValue()))
                throw new IOException("Region content verification failed: " + file + " " + entry.getKey());
        }
    }
}
