package io.github.billstark001.worldmirror.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Shared MCA file write support.
 *
 * <p>Region writes are read-modify-write operations. Keep all callers on the
 * same deterministic striped lock. Native in-place writes require verified
 * receipts before acknowledgement; whole-region replacements use a recovery
 * transaction. Fixed striping bounds the number of lock objects.</p>
 */
public final class McaWriteSupport {
    private static final Object[] FILE_LOCKS = new Object[256];

    static {
        java.util.Arrays.setAll(FILE_LOCKS, ignored -> new Object());
    }

    private McaWriteSupport() {}

    public static Object lockFor(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return FILE_LOCKS[Math.floorMod(normalized.hashCode(), FILE_LOCKS.length)];
    }

    /** Reads vanilla chunk NBT, including external .mcc payloads, without retaining a stream. */
    public static CompoundTag readChunk(RegionFile region, ChunkPos pos) throws IOException {
        try (DataInputStream input = region.getChunkDataInputStream(pos)) {
            if (input == null) {
                if (region.hasChunk(pos)) throw new IOException("Allocated chunk cannot be decoded: " + region.getPath() + " " + pos);
                return null;
            }
            CompoundTag chunk = NbtReadSupport.readChunk(input);
            if (input.read() != -1) throw new IOException("Trailing chunk data: " + region.getPath() + " " + pos);
            return chunk;
        }
    }

    /** Rejects damaged location tables before a writable vanilla reader can discard bad entries. */
    public static void validateRegion(Path file, int regionX, int regionZ) throws IOException {
        validatedInspection(file, regionX, regionZ);
    }

    /** Queries only the location table with read-only file access, including on Windows. */
    public static Set<ChunkPos> listChunkPositions(Path file, int regionX, int regionZ) throws IOException {
        return validatedInspection(file, regionX, regionZ).presentChunks();
    }

    private static RegionFileIntegrity.Inspection validatedInspection(Path file, int regionX, int regionZ)
            throws IOException {
        RegionFileIntegrity.Inspection inspection = RegionFileIntegrity.inspect(file, regionX, regionZ);
        inspection.requireUsable(file);
        return inspection;
    }

    /**
     * Writes a batch in one vanilla region, then reopens and compares every entry.
     * Unrelated entries and external payloads are preserved. A failed batch may
     * have written some chunks; callers must retain their input for retry until
     * this method returns successfully. When copying between region files, release
     * the source file lock before calling this method to avoid lock-order cycles.
     */
    public static void writeChunks(Path file, ResourceKey<Level> dimension,
                                   Map<ChunkPos, CompoundTag> chunks) throws IOException {
        if (chunks.isEmpty()) return;
        ChunkPos first = chunks.keySet().iterator().next();
        for (ChunkPos pos : chunks.keySet()) {
            if (pos.getRegionX() != first.getRegionX() || pos.getRegionZ() != first.getRegionZ()) {
                throw new IllegalArgumentException("Chunk batch spans multiple region files");
            }
        }
        for (CompoundTag chunk : chunks.values()) NbtWriteSupport.validateStrings(chunk);
        Path target = file.toAbsolutePath().normalize();
        String expectedName = "r." + first.getRegionX() + "." + first.getRegionZ() + ".mca";
        if (!target.getFileName().toString().equals(expectedName)) {
            throw new IllegalArgumentException("Region filename does not match chunk batch: " + target);
        }
        synchronized (lockFor(target)) {
            Files.createDirectories(target.getParent());
            validateRegion(target, first.getRegionX(), first.getRegionZ());
            RegionStorageInfo info = new RegionStorageInfo("world_mirror", dimension, "chunk");
            try (RegionFile region = new RegionFile(info, target, target.getParent(), false)) {
                for (Map.Entry<ChunkPos, CompoundTag> entry : chunks.entrySet()) {
                    try (DataOutputStream output = region.getChunkDataOutputStream(entry.getKey())) {
                        NbtIo.writeUnnamedTag(entry.getValue(), output);
                    }
                }
                region.flush();
            }
            validateRegion(target, first.getRegionX(), first.getRegionZ());
            try (RegionFile reader = new RegionFile(info, target, target.getParent(), false)) {
                for (Map.Entry<ChunkPos, CompoundTag> entry : chunks.entrySet()) {
                    if (!entry.getValue().equals(readChunk(reader, entry.getKey()))) {
                        throw new IOException("Region write verification failed file=" + target
                                + " chunk=" + entry.getKey());
                    }
                }
            }
        }
    }

}
