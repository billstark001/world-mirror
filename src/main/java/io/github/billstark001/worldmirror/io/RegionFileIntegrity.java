package io.github.billstark001.worldmirror.io;

import net.minecraft.world.level.ChunkPos;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

/** Read-only validation for the location table in a vanilla Anvil region file. */
final class RegionFileIntegrity {
    private static final int HEADER_BYTES = 8192;
    private static final int LOCATION_BYTES = 4096;
    private static final int SECTOR_BYTES = 4096;
    private static final int LOCATION_COUNT = 1024;

    private RegionFileIntegrity() {}

    record Inspection(boolean usable, Set<ChunkPos> invalidChunks, Set<ChunkPos> presentChunks,
                      String failure, int[] locations) {
        static Inspection valid(Set<ChunkPos> invalidChunks, Set<ChunkPos> presentChunks, int[] locations) {
            return new Inspection(true, Set.copyOf(invalidChunks), Set.copyOf(presentChunks), null, locations);
        }

        static Inspection unusable(String failure) {
            return new Inspection(false, Set.of(), Set.of(), failure, new int[LOCATION_COUNT]);
        }

        void requireUsable(Path file) throws IOException {
            if (!usable || !invalidChunks.isEmpty()) {
                throw new IOException("Invalid region file=" + file + " reason=" + failure
                        + " invalidChunks=" + invalidChunks.size());
            }
        }
    }

    private record Allocation(int index, int first, int end) {}

    static Inspection inspect(Path regionFile, int regionX, int regionZ) throws IOException {
        if (Files.notExists(regionFile)) return Inspection.valid(Set.of(), Set.of(), new int[LOCATION_COUNT]);
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            return inspect(channel, regionX, regionZ);
        }
    }

    /** Uses the reader's channel, retaining the validated table instead of rereading each location. */
    static Inspection inspect(FileChannel channel, int regionX, int regionZ) throws IOException {
        long fileSize = channel.size();
        if (fileSize == 0) return Inspection.valid(Set.of(), Set.of(), new int[LOCATION_COUNT]);
        if (fileSize < HEADER_BYTES) {
            return Inspection.unusable("truncated header bytes=" + fileSize);
        }
        if (fileSize % SECTOR_BYTES != 0L) {
            return Inspection.unusable("file is not sector-aligned bytes=" + fileSize);
        }

        ByteBuffer locations = ByteBuffer.allocate(LOCATION_BYTES);
        long position = 0L;
        while (locations.hasRemaining()) {
            int read = channel.read(locations, position);
            if (read < 0) throw new EOFException("truncated location table");
            position += read;
        }
        locations.flip();

        Set<ChunkPos> invalid = new HashSet<>();
        Set<ChunkPos> present = new HashSet<>();
        int[] table = new int[LOCATION_COUNT];
        var allocations = new ArrayList<Allocation>();
        for (int index = 0; index < LOCATION_COUNT; index++) {
            int packed = locations.getInt();
            table[index] = packed;
            if (packed == 0) continue;

            int sectorOffset = packed >>> 8;
            int sectorCount = packed & 0xFF;
            ChunkPos chunk = chunkPos(regionX, regionZ, index);
            present.add(chunk);
            long endOffset = ((long) sectorOffset + sectorCount) * SECTOR_BYTES;
            if (sectorOffset < 2 || sectorCount == 0 || endOffset > fileSize) {
                invalid.add(chunk);
                continue;
            }

            allocations.add(new Allocation(index, sectorOffset, sectorOffset + sectorCount));
        }
        // At most 1024 intervals, independent of the number or absolute offsets of sectors.
        allocations.sort(Comparator.comparingInt(Allocation::first));
        Allocation furthest = null;
        for (Allocation allocation : allocations) {
            if (furthest != null && allocation.first() < furthest.end()) {
                invalid.add(chunkPos(regionX, regionZ, furthest.index()));
                invalid.add(chunkPos(regionX, regionZ, allocation.index()));
            }
            if (furthest == null || allocation.end() > furthest.end()) furthest = allocation;
        }
        return Inspection.valid(invalid, present, table);
    }

    private static ChunkPos chunkPos(int regionX, int regionZ, int index) {
        return new ChunkPos(regionX * 32 + (index & 31), regionZ * 32 + (index >>> 5));
    }
}
