package io.github.billstark001.worldmirror.conflict;

import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.TerrainChunkNbt;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.SeedCaptureReceipt;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * Persists incoming manual conflicts under {@code conflict_chunks/}, using the
 * classic region/DIM-1/DIM1/dimensions layout on every Minecraft target.
 * Vanilla region I/O preserves oversized chunks stored in external .mcc files.
 * Applying a conflict releases its source lock before acquiring the world lock;
 * only unchanged incoming entries are removed after the world write is verified.
 */
public final class ConflictManager {
    static final String CONFLICT_ROOT = "conflict_chunks";
    private static final int REGION_SIZE = 32;
    private static final long SNAPSHOT_BYTES = 32L * 1024 * 1024;
    private static final Object[] RESOLUTION_LOCKS = new Object[64];

    static {
        Arrays.setAll(RESOLUTION_LOCKS, ignored -> new Object());
    }

    private ConflictManager() {}

    /** Returns the conflict region directory without creating it. */
    public static Path getConflictDir(Path worldFolder, ResourceKey<Level> dimension) {
        Path root = worldFolder.resolve(CONFLICT_ROOT);
        if (dimension.equals(Level.OVERWORLD))
            return root.resolve("region");
        if (dimension.equals(Level.NETHER))
            return root.resolve("DIM-1/region");
        if (dimension.equals(Level.END))
            return root.resolve("DIM1/region");
        return WorldSaveLayout.namespacedDimensionDirectory(root, dimension).resolve("region");
    }

    /** Returns true only after the incoming chunk has been reopened and verified. */
    public static boolean saveConflict(
            Path worldFolder, ChunkPos pos, CompoundTag chunkNbt, ResourceKey<Level> dimension) {
        return saveConflicts(worldFolder, dimension, Map.of(pos, chunkNbt), null);
    }

    /** Writes a single region's pending captures and preserves equivalent or newer verified copies. */
    public static boolean saveConflicts(Path worldFolder, ResourceKey<Level> dimension,
            Map<ChunkPos, CompoundTag> incoming, String nodeId) {
        return stageConflicts(worldFolder, dimension, incoming, nodeId).size() == incoming.size();
    }

    /** Returns the captures represented by a verified pending copy. */
    public static Set<ChunkPos> stageConflicts(Path worldFolder, ResourceKey<Level> dimension,
            Map<ChunkPos, CompoundTag> incoming, String nodeId) {
        if (incoming.isEmpty())
            return Set.of();
        var region = regionFor(worldFolder, incoming.keySet().iterator().next(), dimension);
        var file = region.file();
        Set<ChunkPos> verified = new HashSet<>();
        try {
            FileProof.requirePlainPath(worldFolder, file);
            synchronized (McaWriteSupport.lockFor(file)) {
                var changed = changedPending(region, incoming, nodeId, verified);
                McaWriteSupport.writeChunks(file, dimension, changed);
                verified.addAll(changed.keySet());
            }
        } catch (Exception error) {
            WMLogger.warnRateLimited("pending-conflict-region:" + file, 30_000L,
                    "Conflict save failed file=" + file + " chunks=" + incoming.size(), error);
        }
        return verified;
    }

    private static Map<ChunkPos, CompoundTag> changedPending(ConflictRegion region,
            Map<ChunkPos, CompoundTag> incoming, String nodeId, Set<ChunkPos> verified) throws IOException {
        Map<ChunkPos, CompoundTag> changed = new HashMap<>();
        try (var reader = AnvilReader.open(region.file(), region.x(), region.z())) {
            for (var entry : incoming.entrySet())
                stagePending(region, nodeId, reader, entry.getKey(), entry.getValue(), changed, verified);
        }
        return changed;
    }

    private static void stagePending(ConflictRegion region, String nodeId, AnvilReader.Reader reader,
            ChunkPos pos, CompoundTag incoming, Map<ChunkPos, CompoundTag> changed, Set<ChunkPos> verified) {
        try {
            if (pos.getRegionX() != region.x() || pos.getRegionZ() != region.z())
                throw new IOException("Pending captures span multiple region files");
            TerrainChunkNbt.requireCoordinates(incoming, pos);
            if (nodeId != null
                    && (!TerrainChunkNbt.coordinatesMatch(incoming, pos)
                            || !TerrainChunkNbt.hasSupportedDataVersion(incoming)))
                throw new IOException("Pending capture has invalid terrain coordinates or version");
            if (needsPendingWrite(reader.read(pos), incoming, nodeId)) {
                changed.put(pos, incoming);
            } else {
                verified.add(pos);
            }
        } catch (IOException | RuntimeException error) {
            WMLogger.warnRateLimited("pending-conflict-slot:" + region.file(), 30_000L,
                    "Could not stage pending terrain file=" + region.file() + " chunk=" + pos, error);
        }
    }

    private static boolean needsPendingWrite(CompoundTag existing, CompoundTag incoming, String nodeId)
            throws IOException {
        if (nodeId == null) {
            if (SeedCaptureReceipt.hasReceipt(incoming)
                    || (existing != null && SeedCaptureReceipt.hasReceipt(existing)))
                throw new IOException("A capture receipt requires its mirror node for verification");
            return existing == null || !TerrainChunkNbt.equivalent(existing, incoming);
        }
        var next = SeedCaptureReceipt.read(incoming, nodeId);
        if (next == null)
            throw new IOException("Incoming conflict is missing its capture receipt");
        if (existing == null)
            return true;
        var prior = SeedCaptureReceipt.read(existing, nodeId);
        if (prior == null) {
            if (SeedCaptureReceipt.hasReceipt(existing))
                throw new IOException("Pending conflict has an invalid or foreign capture receipt");
            return true;
        }
        if (!prior.source().equals(next.source()))
            throw new IOException("Pending conflict belongs to another capture source");
        return prior.time() < next.time()
                && !TerrainChunkNbt.equivalent(SeedCaptureReceipt.withoutReceipt(existing),
                        SeedCaptureReceipt.withoutReceipt(incoming));
    }

    /** Whether the region header contains a pending entry, including an external payload. */
    public static boolean hasConflict(Path worldFolder, ChunkPos pos, ResourceKey<Level> dimension)
            throws IOException {
        return listCoordinates(regionFor(worldFolder, pos, dimension)).contains(pos);
    }

    /** Returns pending chunk positions in the requested dimension. */
    public static Set<ChunkPos> listConflicts(Path worldFolder, ResourceKey<Level> dimension)
            throws IOException {
        Set<ChunkPos> result = new HashSet<>();
        Path directory = getConflictDir(worldFolder, dimension);
        for (ConflictRegion region : findRegions(directory, directory, dimension)) {
            result.addAll(listCoordinates(region));
        }
        return result;
    }

    /** Counts pending entries across dimensions without decoding their NBT payloads. */
    public static int countAllConflicts(Path worldFolder) throws IOException {
        Path root = worldFolder.resolve(CONFLICT_ROOT);
        int count = 0;
        for (ConflictRegion region : findRegions(root, root, null)) count += listCoordinates(region).size();
        return count;
    }

    /**
     * Applies a frozen incoming version, or discards the pending entry when
     * overwrite is false. Failed writes and newer conflicts remain available.
     */
    public static boolean resolveConflict(
            MirrorTarget target, ChunkPos pos, ResourceKey<Level> dimension, boolean overwrite) {
        Path worldFolder = target.folder();
        ConflictRegion region = regionFor(worldFolder, pos, dimension);
        synchronized (resolutionLock(region)) {
            try {
                target.requireCurrent();
                if (overwrite)
                    return applySnapshot(target, region, readSnapshot(region, Set.of(pos)));
                else
                    removeEntries(region, Set.of(pos), null);
                return true;
            } catch (Exception error) {
                WMLogger.warn("Conflict resolution failed chunk=" + pos + " overwrite=" + overwrite, error);
                return false;
            }
        }
    }

    /**
     * Applies conflicts in memory-bounded batches, retaining each batch until its
     * copied chunks verify. Versions arriving after their batch was read, and new
     * coordinates added during the operation, remain pending.
     */
    public static boolean clearAllConflicts(MirrorTarget target, boolean overwrite) throws IOException {
        target.requireCurrent();
        Path worldFolder = target.folder();
        boolean success = true;
        Path root = worldFolder.resolve(CONFLICT_ROOT);
        for (ConflictRegion region : findRegions(root, root, null)) {
            synchronized (resolutionLock(region)) {
                try {
                    if (overwrite)
                        success &= applyRegion(target, region);
                    else
                        removeEntries(region, listCoordinates(region), null);
                } catch (Exception error) {
                    success = false;
                    WMLogger.warn("Conflict region cleanup failed file=" + region.file()
                                    + " overwrite=" + overwrite + "; remaining entries retained",
                            error);
                }
            }
        }
        deleteEmptyDirectories(root);
        return success;
    }

    private static boolean applyRegion(MirrorTarget target, ConflictRegion region) throws IOException {
        Set<ChunkPos> pending = new HashSet<>(listCoordinates(region));
        boolean success = true;
        while (!pending.isEmpty()) {
            Map<ChunkPos, CompoundTag> batch = readSnapshot(region, pending);
            if (batch.isEmpty())
                break;
            success &= applySnapshot(target, region, batch);
            // Attempt each initial coordinate once without chasing newer arrivals.
            pending.removeAll(batch.keySet());
        }
        return success;
    }

    private static Object resolutionLock(ConflictRegion region) {
        // These decision locks are separate from file locks: exporters never
        // acquire one, so waiting for a world file cannot form a lock cycle.
        int hash = region.file().toAbsolutePath().normalize().hashCode();
        return RESOLUTION_LOCKS[Math.floorMod(hash, RESOLUTION_LOCKS.length)];
    }

    private static boolean applySnapshot(MirrorTarget mirror, ConflictRegion region,
            Map<ChunkPos, CompoundTag> snapshot) throws IOException {
        if (snapshot.isEmpty())
            return true;
        Path worldFolder = mirror.folder();
        Path target = WorldSaveLayout.regionDirectory(worldFolder, region.dimension())
                              .resolve(region.file().getFileName());
        // Never hold the conflict lock while waiting for the world lock: an
        // exporter can hold that world lock while persisting another conflict.
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            mirror.requireCurrent();
            FileProof.requirePlainPath(worldFolder, target);
            var publication = ConflictPublication.prepare(mirror, snapshot);
            var protectedPositions = ConflictPublication.write(mirror, region, target, publication);
            var published = new HashSet<>(snapshot.keySet());
            published.removeAll(protectedPositions);
            if (!published.isEmpty())
                removeEntries(region, published, snapshot);
            return protectedPositions.isEmpty();
        }
    }

    private static Map<ChunkPos, CompoundTag> readSnapshot(ConflictRegion region, Set<ChunkPos> selected)
            throws IOException {
        synchronized (McaWriteSupport.lockFor(region.file())) {
            if (Files.notExists(region.file()))
                return Map.of();
            Map<ChunkPos, CompoundTag> chunks = new HashMap<>();
            long retained = 0;
            try (AnvilReader.Reader reader = AnvilReader.open(region.file(), region.x(), region.z())) {
                for (ChunkPos pos : reader.positions()) {
                    if (!selected.contains(pos))
                        continue;
                    CompoundTag chunk = reader.read(pos);
                    if (chunk == null)
                        throw new IOException("Unreadable conflict chunk=" + pos);
                    chunks.put(pos, chunk);
                    retained += chunk.sizeInBytes();
                    if (retained >= SNAPSHOT_BYTES)
                        break;
                }
            }
            return chunks;
        }
    }

    private static Set<ChunkPos> listCoordinates(ConflictRegion region) throws IOException {
        synchronized (McaWriteSupport.lockFor(region.file())) {
            if (Files.notExists(region.file()))
                return Set.of();
            return McaWriteSupport.listChunkPositions(region.file(), region.x(), region.z());
        }
    }

    private static Set<ChunkPos> coordinates(RegionFile reader, ConflictRegion region) {
        Set<ChunkPos> result = new HashSet<>();
        for (int z = 0; z < REGION_SIZE; z++) {
            for (int x = 0; x < REGION_SIZE; x++) {
                ChunkPos pos = new ChunkPos(region.x() * REGION_SIZE + x, region.z() * REGION_SIZE + z);
                if (reader.hasChunk(pos))
                    result.add(pos);
            }
        }
        return result;
    }

    private static void removeEntries(ConflictRegion region, Set<ChunkPos> selected,
            Map<ChunkPos, CompoundTag> expected) throws IOException {
        synchronized (McaWriteSupport.lockFor(region.file())) {
            if (!Files.isRegularFile(region.file()))
                return;
            boolean empty;
            try (RegionFile file = openRegion(region)) {
                for (ChunkPos pos : selected) {
                    if (expected == null || expected.get(pos).equals(McaWriteSupport.readChunk(file, pos))) {
                        file.clear(pos);
                    }
                }
                empty = coordinates(file, region).isEmpty();
            }
            if (empty)
                Files.deleteIfExists(region.file());
        }
    }

    private static RegionFile openRegion(ConflictRegion region) throws IOException {
        McaWriteSupport.validateRegion(region.file(), region.x(), region.z());
        return new RegionFile(new RegionStorageInfo("world_mirror_conflicts", region.dimension(), "chunk"),
                region.file(), region.file().getParent(), false);
    }

    private static ConflictRegion regionFor(Path world, ChunkPos pos, ResourceKey<Level> dimension) {
        return new ConflictRegion(getConflictDir(world, dimension)
                                          .resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca"),
                pos.getRegionX(), pos.getRegionZ(), dimension);
    }

    private static List<ConflictRegion> findRegions(Path directory, Path root, ResourceKey<Level> dimension)
            throws IOException {
        if (Files.notExists(directory))
            return List.of();
        List<ConflictRegion> result = new ArrayList<>();
        try (Stream<Path> paths = dimension == null ? Files.walk(directory) : Files.list(directory)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                Matcher name = io.github.billstark001.worldmirror.io.AnvilReader.REGION_NAME.matcher(
                        file.getFileName().toString());
                if (!name.matches())
                    continue;
                var attributes = Files.readAttributes(
                        file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile())
                    throw new IOException("Invalid conflict entry: " + file);
                try {
                    ResourceKey<Level> key =
                            dimension == null ? dimensionFor(root.relativize(file)) : dimension;
                    var coordinates = io.github.billstark001.worldmirror.io.AnvilReader.coordinates(file);
                    if (key != null)
                        result.add(new ConflictRegion(file, coordinates.x(), coordinates.z(), key));
                } catch (IllegalArgumentException error) {
                    WMLogger.warnRateLimited(
                            "conflict-region-name", 30_000L, "Invalid conflict region path=" + file, error);
                }
            }
        } catch (IOException | UncheckedIOException error) {
            throw new IOException("Conflict directory read failed: " + directory, error);
        }
        return result;
    }

    private static ResourceKey<Level> dimensionFor(Path relative) {
        int count = relative.getNameCount();
        if (count == 2 && relative.getName(0).toString().equals("region"))
            return Level.OVERWORLD;
        if (count == 3 && relative.getName(1).toString().equals("region")) {
            return switch (relative.getName(0).toString()) {
                case "DIM-1" -> Level.NETHER;
                case "DIM1" -> Level.END;
                default -> null;
            };
        }
        if (count >= 5 && relative.getName(0).toString().equals("dimensions")
                && relative.getName(count - 2).toString().equals("region")) {
            String path = relative.subpath(2, count - 2).toString().replace('\\', '/');
            return ResourceKey.create(Registries.DIMENSION,
                    Identifier.fromNamespaceAndPath(relative.getName(1).toString(), path));
        }
        return null;
    }

    private static void deleteEmptyDirectories(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                try {
                    Files.deleteIfExists(path);
                } catch (DirectoryNotEmptyException ignored) {
                    // New conflicts, retained failures, and unrelated files keep their directory.
                }
            }
        } catch (IOException | UncheckedIOException error) {
            WMLogger.warnRateLimited("conflict-cleanup-directories", 30_000L,
                    "Conflict directory cleanup failed path=" + root, error);
        }
    }

    record ConflictRegion(Path file, int x, int z, ResourceKey<Level> dimension) {}
}
