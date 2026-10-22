package io.github.billstark001.worldmirror.io;

import io.github.ensgijs.nbt.mca.McaFileBase;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

/**
 * Shared MCA file write support.
 *
 * <p>Region writes are read-modify-write operations. Keep all callers on the
 * same deterministic striped lock and replace files through a same-directory
 * temp file so a failed write does not leave a partially overwritten region
 * behind. Fixed striping avoids retaining one lock object for every region ever
 * visited during a long session.</p>
 */
public final class McaWriteSupport {
    private static final int WRITE_ATTEMPTS = 3;
    private static final int REGION_SIZE = 32;
    private static final int REGION_ENTRIES = REGION_SIZE * REGION_SIZE;
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
            return input == null ? null : NbtIo.read(input);
        }
    }

    /** Rejects damaged location tables before a vanilla reader can repair them in place. */
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
        if (!inspection.usable() || !inspection.invalidChunks().isEmpty()) {
            throw new IOException("Invalid region file=" + file + " reason=" + inspection.failure()
                    + " invalidChunks=" + inspection.invalidChunks().size());
        }
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
                        NbtIo.write(entry.getValue(), output);
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

    public static int writeAtomically(McaFileBase<?> mcaFile, Path target) throws IOException {
        synchronized (lockFor(target)) {
            return writeAtomicallyLocked(mcaFile, target);
        }
    }

    public static int writeAtomicallyLocked(McaFileBase<?> mcaFile, Path target) throws IOException {
        target = target.toAbsolutePath().normalize();
        IOException lastError = null;
        Path parent = target.getParent();
        Files.createDirectories(parent);

        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            try {
                // The vendored writer allocates a second system-temp file when
                // its destination already exists. Keep this write in our directory.
                Files.deleteIfExists(temp);
                int chunks = McaFileHelpers.write(mcaFile, temp.toFile());
                if (chunks > 0) {
                    verifySerializedRegion(mcaFile, temp);
                    moveIntoPlace(temp, target);
                } else {
                    Files.deleteIfExists(temp);
                }
                return chunks;
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                if (failure instanceof IOException ioFailure) lastError = ioFailure;
                else throw failure;
                if (attempt < WRITE_ATTEMPTS) {
                    sleepBeforeRetry();
                }
            }
        }

        throw lastError != null ? lastError : new IOException("MCA write failed");
    }

    private static void verifySerializedRegion(McaFileBase<?> source, Path file) throws IOException {
        validateRegion(file, source.getRegionX(), source.getRegionZ());
        RegionStorageInfo info = new RegionStorageInfo("world_mirror_atomic", Level.OVERWORLD, "chunk");
        try (RegionFile reader = new RegionFile(info, file, file.getParent(), false)) {
            for (int index = 0; index < REGION_ENTRIES; index++) {
                if (source.getChunk(index) == null) continue;
                ChunkPos pos = new ChunkPos(source.getRegionX() * REGION_SIZE + index % REGION_SIZE,
                        source.getRegionZ() * REGION_SIZE + index / REGION_SIZE);
                if (readChunk(reader, pos) == null) {
                    throw new IOException("Serialized region entry cannot be decoded file=" + file
                            + " chunk=" + pos);
                }
            }
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepBeforeRetry() throws IOException {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying MCA write", e);
        }
    }
}
