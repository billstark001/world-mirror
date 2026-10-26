package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Reads Anvil containers without opening a repairing, writable RegionFile. */
public final class AnvilReader {
    public static final java.util.regex.Pattern REGION_NAME = java.util.regex.Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int SECTOR_BYTES = 4096;
    private static final long MAX_COMPRESSED_BYTES = 64L * 1024 * 1024;

    private AnvilReader() {}

    public record RegionCoordinates(int x, int z) {}

    public static RegionCoordinates coordinates(Path file) throws IOException {
        var match = REGION_NAME.matcher(file.getFileName().toString());
        if (!match.matches()) throw new IOException("Invalid region filename: " + file);
        try {
            int x = Integer.parseInt(match.group(1)), z = Integer.parseInt(match.group(2));
            if (x < (Integer.MIN_VALUE >> 5) || x > (Integer.MAX_VALUE >> 5)
                    || z < (Integer.MIN_VALUE >> 5) || z > (Integer.MAX_VALUE >> 5)) {
                throw new NumberFormatException("Region coordinates overflow chunk coordinates");
            }
            if (!file.getFileName().toString().equals("r." + x + "." + z + ".mca")) {
                throw new IOException("Noncanonical region filename: " + file);
            }
            return new RegionCoordinates(x, z);
        } catch (NumberFormatException invalid) { throw new IOException("Invalid region coordinates: " + file, invalid); }
    }

    @FunctionalInterface
    public interface ChunkVisitor { void accept(ChunkPos pos, CompoundTag chunk) throws IOException; }

    /** One header inspection and one data channel per region; callbacks must not retain unnecessary trees. */
    public static void visitRegion(Path file, int regionX, int regionZ, ChunkVisitor visitor) throws IOException {
        try (Reader reader = open(file, regionX, regionZ)) {
            for (ChunkPos pos : reader.positions()) visitor.accept(pos, reader.read(pos));
        }
    }

    public static CompoundTag readChunk(Path file, ChunkPos pos) throws IOException {
        try (Reader reader = open(file, pos.getRegionX(), pos.getRegionZ())) { return reader.read(pos); }
    }

    public static Reader open(Path file, int regionX, int regionZ) throws IOException { return new Reader(file, regionX, regionZ); }

    public static final class Reader implements AutoCloseable {
        private final Path file;
        private final java.util.Set<ChunkPos> positions;
        private final FileChannel channel;
        private final int[] locations;
        private final FileProof.Version fingerprint;

        private Reader(Path file, int regionX, int regionZ) throws IOException {
            this.file = file;
            fingerprint = FileReadFailures.check(file);
            FileChannel opened = null;
            try {
                if (!Files.notExists(file)) opened = FileChannel.open(file, StandardOpenOption.READ);
                var inspection = opened == null ? RegionFileIntegrity.inspect(file, regionX, regionZ)
                        : RegionFileIntegrity.inspect(opened, regionX, regionZ);
                inspection.requireUsable(file);
                positions = inspection.presentChunks();
                locations = inspection.locations();
                channel = opened;
            } catch (IOException | RuntimeException | Error failure) {
                if (opened != null) try { opened.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }

        public java.util.Set<ChunkPos> positions() { return positions; }

        public CompoundTag read(ChunkPos pos) throws IOException { return decode(pos, null); }

        boolean matches(ChunkPos pos, byte[] expected) throws IOException {
            var digest = FileProof.sha256();
            return decode(pos, digest) != null && java.util.Arrays.equals(expected, digest.digest());
        }

        private CompoundTag decode(ChunkPos pos, java.security.MessageDigest digest) throws IOException {
            if (!positions.contains(pos)) return null;
            Path payloadFile = file;
            FileProof.Version observed = fingerprint;
            try {
                int slot = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
                int packed = locations[slot];
                long offset = (long) (packed >>> 8) * SECTOR_BYTES;
                long capacity = (packed & 255) * (long) SECTOR_BYTES;
                if (offset < 8192 || capacity == 0 || offset + capacity > channel.size()) {
                    throw new FileReadFailures.InvalidData("Invalid chunk location: " + file + " " + pos);
                }
                ByteBuffer header = ByteBuffer.allocate(5);
                readFully(channel, header, offset);
                header.flip();
                int length = header.getInt(), codec = Byte.toUnsignedInt(header.get());
                RegionFileVersion version = RegionFileVersion.fromId(codec & 127);
                if (version == null || length < 1 || length > capacity - 4) {
                    throw new FileReadFailures.InvalidData("Invalid chunk length/codec: " + file + " " + pos);
                }
                if (version == RegionFileVersion.VERSION_CUSTOM) {
                    throw new IOException("Unsupported custom Anvil compression: " + file + " " + pos);
                }
                InputStream compressed;
                if ((codec & 128) != 0) {
                    if (length != 1) throw new FileReadFailures.InvalidData("Invalid external chunk stub: " + file);
                    payloadFile = externalFile(file.getParent(), pos);
                    observed = FileReadFailures.check(payloadFile);
                    if (observed != null && observed.size() > MAX_COMPRESSED_BYTES) {
                        throw new IOException("External chunk exceeds compressed-input policy: " + payloadFile);
                    }
                    compressed = new java.io.BufferedInputStream(Files.newInputStream(payloadFile), 64 * 1024);
                } else {
                    ByteBuffer bytes = ByteBuffer.allocate(length - 1);
                    readFully(channel, bytes, offset + 5);
                    compressed = new ByteArrayInputStream(bytes.array());
                }
                try (InputStream payload = compressed;
                     InputStream decoded = version.wrap(com.google.common.io.ByteStreams.limit(payload, MAX_COMPRESSED_BYTES));
                     DataInputStream input = new DataInputStream(digest == null ? decoded : new java.security.DigestInputStream(decoded, digest))) {
                    CompoundTag result = NbtReadSupport.readChunk(input);
                    // Reaching the codec's EOF checks gzip/LZ4 trailers too. Parsing
                    // only the NBT root could accept a damaged compression checksum.
                    if (input.read() != -1) throw new FileReadFailures.InvalidData("Trailing chunk NBT: " + payloadFile);
                    return result;
                }
            } catch (RuntimeException invalid) {
                var failure = new FileReadFailures.InvalidData("Cannot decode chunk: " + payloadFile + " " + pos, invalid);
                FileReadFailures.record(payloadFile, observed, failure);
                throw failure;
            } catch (IOException failure) {
                IOException contextual = failure instanceof FileReadFailures.InvalidData
                        || failure instanceof java.io.EOFException || failure instanceof java.util.zip.ZipException
                        ? new FileReadFailures.InvalidData("Invalid Anvil chunk: " + payloadFile + " " + pos, failure)
                        : new IOException("Cannot read Anvil chunk: " + payloadFile + " " + pos, failure);
                FileReadFailures.record(payloadFile, observed, contextual);
                throw contextual;
            }
        }

        @Override public void close() throws IOException { if (channel != null) channel.close(); }
    }

    static Path externalFile(Path directory, ChunkPos pos) {
        return directory.resolve("c." + (pos.getRegionX() * 32 + pos.getRegionLocalX())
                + "." + (pos.getRegionZ() * 32 + pos.getRegionLocalZ()) + ".mcc");
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) throw new EOFException("Truncated Anvil container");
            offset += read;
        }
    }
}
