package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileTransaction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Streams a region rewrite, retaining content proofs instead of a second region of NBT trees. */
final class RegionWriteTransaction {
    @FunctionalInterface
    interface Transform { CompoundTag apply(ChunkPos pos, CompoundTag previous) throws IOException; }
    private RegionWriteTransaction() {}

    static void rewrite(Path target, ResourceKey<Level> dimension, int regionX, int regionZ,
                        Set<ChunkPos> requested, Transform transform) throws IOException {
        synchronized (McaWriteSupport.lockFor(target)) {
            try (FileTransaction transaction = new FileTransaction(target.getParent())) {
                Path staged = transaction.stagingDirectory().resolve(target.getFileName());
                Map<ChunkPos, byte[]> proofs = new HashMap<>();
                Set<ChunkPos> missing = new HashSet<>(requested);
                try (AnvilReader.Reader source = AnvilReader.open(target, regionX, regionZ);
                     RegionFile writer = new RegionFile(new RegionStorageInfo("world_mirror", dimension, "entity"),
                             staged, staged.getParent(), false)) {
                    for (ChunkPos pos : source.positions()) {
                        write(writer, proofs, pos, transform.apply(pos, source.read(pos)));
                        missing.remove(pos);
                    }
                    for (ChunkPos pos : missing) {
                        if (pos.getRegionX() != regionX || pos.getRegionZ() != regionZ) throw new IOException("Foreign region update: " + pos);
                        write(writer, proofs, pos, transform.apply(pos, null));
                    }
                }
                verify(staged, regionX, regionZ, proofs);
                Map<Path, Path> replacements = new HashMap<>();
                try (var files = Files.list(transaction.stagingDirectory())) {
                    for (Path file : files.toList()) replacements.put(file.getFileName(), file);
                }
                transaction.commit(replacements);
            }
        }
    }

    private static void write(RegionFile writer, Map<ChunkPos, byte[]> proofs, ChunkPos pos, CompoundTag chunk) throws IOException {
        if (chunk == null) return;
        proofs.put(pos, NbtWriteSupport.writeChunkAndDigest(chunk, writer.getChunkDataOutputStream(pos)));
    }

    private static void verify(Path file, int regionX, int regionZ, Map<ChunkPos, byte[]> proofs) throws IOException {
        try (AnvilReader.Reader reader = AnvilReader.open(file, regionX, regionZ)) {
            if (!reader.positions().equals(proofs.keySet())) throw new IOException("Region entries changed: " + file);
            for (var entry : proofs.entrySet()) {
                if (!reader.matches(entry.getKey(), entry.getValue())) throw new IOException("Region content verification failed: " + file + " " + entry.getKey());
            }
        }
    }
}
