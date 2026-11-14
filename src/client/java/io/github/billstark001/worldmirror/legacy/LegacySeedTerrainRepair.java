package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;
import io.github.billstark001.worldmirror.download.SeedTerrainBiomes;
import io.github.billstark001.worldmirror.download.SeedTerrainLighting;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.ChunkAuxiliaryData;
import io.github.billstark001.worldmirror.io.SeedCaptureReceipt;
import io.github.billstark001.worldmirror.io.SeedChunkProvenance;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Finds surviving placeholders using the alpha.1 conversion's own pre-conversion terrain. */
public final class LegacySeedTerrainRepair {
    private static final List<ResourceKey<Level>> DIMENSIONS =
            List.of(Level.OVERWORLD, Level.NETHER, Level.END);
    private static final int[][] CARDINAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    public record Changes(ResourceKey<Level> dimension, Map<ChunkPos, Path> remove,
            Map<ChunkPos, Path> relight, Set<ChunkPos> generated) {}

    private LegacySeedTerrainRepair() {}

    public static List<Changes> scan(Path world, Path before, Path index, WorldMetadata metadata,
            Map<Path, String> proofs, BiConsumer<Integer, Integer> progress)
            throws IOException, SQLException {
        var result = new java.util.ArrayList<Changes>();
        var regions = new LinkedHashMap<ResourceKey<Level>, List<Path>>();
        for (var dimension : DIMENSIONS) {
            var directory = WorldSaveLayout.regionDirectory(before, dimension);
            if (!Files.isDirectory(directory))
                continue;
            try (var paths = Files.list(directory)) {
                regions.put(dimension,
                        paths.filter(p
                                     -> AnvilReader.REGION_NAME.matcher(p.getFileName().toString()).matches())
                                .sorted()
                                .toList());
            }
        }
        int total = regions.values().stream().mapToInt(List::size).sum();
        int completed = 0;
        for (var entry : regions.entrySet()) {
            var scan = new Scan(world, before, entry.getKey(), metadata, index, proofs);
            for (Path path : entry.getValue()) {
                scan.compareRegion(path);
                progress.accept(++completed, total);
            }
            result.add(scan.result());
        }
        return List.copyOf(result);
    }

    private static final class Scan {
        private record SpillCandidate(Path file, boolean owned, SeedVoidRepairEvidence.Spill spill) {}

        private final Path world;
        private final ResourceKey<Level> dimension;
        private final WorldMetadata metadata;
        private final Path directory;
        private final Path archivedDirectory;
        private final Map<Path, String> proofs;
        private final Map<ChunkPos, ChunkRecord> sources = new HashMap<>();
        private final Map<ChunkPos, Path> remove = new LinkedHashMap<>();
        private final Set<ChunkPos> boundaries = new HashSet<>();
        private final Set<ChunkPos> archivedVoids = new HashSet<>();
        private final Map<ChunkPos, Path> nativeVoids = new HashMap<>();
        private final Map<ChunkPos, Path> generatedCandidates = new HashMap<>();
        private final Set<ChunkPos> badDonors = new HashSet<>();
        private final Map<ChunkPos, SpillCandidate> pendingSpills = new LinkedHashMap<>();
        private final Set<Path> verifiedRegions = new HashSet<>();
        private final Map<ChunkPos, CompoundTag> recentNeighbors = new LinkedHashMap<>(64, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkPos, CompoundTag> eldest) {
                return size() > 64;
            }
        };

        Scan(Path world, Path before, ResourceKey<Level> dimension, WorldMetadata metadata, Path index,
                Map<Path, String> proofs) throws SQLException {
            this.world = world;
            this.dimension = dimension;
            this.metadata = metadata;
            this.proofs = proofs;
            directory = WorldSaveLayout.regionDirectory(world, dimension);
            archivedDirectory = WorldSaveLayout.regionDirectory(before, dimension);
            if (index != null)
                for (var row : ChunkDatabase.queryAllReadOnly(
                             index, metadata.sourceId, dimension.identifier().toString()))
                    sources.put(new ChunkPos(row.x(), row.z()), row);
        }

        void compareRegion(Path before) throws IOException {
            var current = directory.resolve(before.getFileName());
            proof(current);
            if (proofs.get(world.relativize(current)) == null)
                return;
            var coordinates = AnvilReader.coordinates(current);
            proof(auxiliary(current, "entities"));
            proof(auxiliary(current, "poi"));
            try (var old = AnvilReader.open(before, coordinates.x(), coordinates.z());
                    var now = AnvilReader.open(current, coordinates.x(), coordinates.z());
                    var auxiliary =
                            new ChunkAuxiliaryData(world, current, coordinates.x(), coordinates.z())) {
                for (ChunkPos pos : old.positions())
                    if (now.positions().contains(pos))
                        compareChunk(current, pos, old, now, auxiliary);
            }
        }

        private void compareChunk(Path file, ChunkPos pos, AnvilReader.Reader old, AnvilReader.Reader now,
                ChunkAuxiliaryData auxiliary) throws IOException {
            var row = sources.get(pos);
            String source = row == null ? null : row.updateSource();
            if (source != null && !ChunkIndexStore.SEED_GENERATED.equals(source))
                return;
            var before = old.read(pos);
            if (!supported(before) || !atPosition(before, pos))
                return;
            boolean owned = SeedTerrainBiomes.isOwnedVoid(before, dimension);
            if (!owned && !SeedTerrainBiomes.isNativeVoid(before, dimension))
                return;
            if (owned)
                archivedVoids.add(pos);
            proof(AnvilReader.externalFile(file.getParent(), pos));
            var current = now.read(pos);
            if (!supported(current) || !atPosition(current, pos))
                return;
            var generation = SeedChunkProvenance.read(current);
            if (generation != null && metadata.generation.seedEpoch().equals(generation.epoch())) {
                boundaries.add(pos);
                inspectGenerated(file, pos, current, generation, auxiliary);
                return;
            }
            if (source != null)
                return;
            var spill = SeedVoidRepairEvidence.matchArchivedVoid(before, current, dimension);
            if (spill == null || protectedAuxiliary(file, pos, auxiliary))
                return;
            if (current.contains(SeedTerrainDigest.BLENDING_DATA)
                    || before.contains(SeedTerrainDigest.BLENDING_DATA))
                badDonors.add(pos);
            var candidate = new SpillCandidate(file, owned, spill);
            if (spill.reaches(this::neighbor))
                admitSpill(pos, candidate);
            else
                pendingSpills.put(pos, candidate);
        }

        private void admitSpill(ChunkPos pos, SpillCandidate candidate) {
            (candidate.owned() ? remove : nativeVoids).put(pos, candidate.file());
        }

        private void inspectGenerated(Path file, ChunkPos pos, CompoundTag chunk,
                SeedChunkProvenance.Entry generation, ChunkAuxiliaryData auxiliary) throws IOException {
            if (!dimension.equals(Level.OVERWORLD) || metadata.generation.blendTerrain()
                    || generation.generationDataVersion() != chunk.getIntOr("DataVersion", 0)
                    || chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS).size() != 1
                    || SeedTerrainBiomes.hasStructures(chunk) || protectedAuxiliary(file, pos, auxiliary))
                return;
            if (SeedChunkProvenance.isUnmodified(chunk, generation))
                generatedCandidates.put(pos, file);
        }

        private boolean protectedAuxiliary(Path file, ChunkPos pos, ChunkAuxiliaryData auxiliary)
                throws IOException {
            proof(AnvilReader.externalFile(auxiliary(file, "entities").getParent(), pos));
            proof(AnvilReader.externalFile(auxiliary(file, "poi").getParent(), pos));
            return auxiliary.hasData(pos);
        }

        private boolean generated(CompoundTag chunk) {
            var marker = SeedChunkProvenance.read(chunk);
            return marker != null && metadata.generation.seedEpoch().equals(marker.epoch());
        }

        private CompoundTag neighbor(int x, int z) throws IOException {
            var pos = new ChunkPos(x, z);
            if (!indexedCapture(pos) && !remove.containsKey(pos) && pendingSpills.containsKey(pos))
                return null;
            if (!recentNeighbors.containsKey(pos))
                recentNeighbors.put(pos, read(pos));
            var chunk = recentNeighbors.get(pos);
            if (!supported(chunk) || !atPosition(chunk, pos))
                return null;
            return indexedCapture(pos) || generated(chunk) || remove.containsKey(pos) ? chunk : null;
        }

        Changes result() throws IOException {
            admitConnectedSpills();
            addDistortedGeneration();
            boundaries.addAll(remove.keySet());
            var relight = new LinkedHashMap<ChunkPos, Path>();
            var candidates = new HashSet<ChunkPos>();
            for (ChunkPos pos : boundaries) addNeighbors(pos, candidates);
            candidates.removeAll(remove.keySet());
            var regions = new LinkedHashMap<Path, Set<ChunkPos>>();
            for (ChunkPos pos : candidates)
                regions.computeIfAbsent(region(pos), ignored -> new HashSet<>()).add(pos);
            for (var entry : regions.entrySet()) collectRelight(entry.getKey(), entry.getValue(), relight);
            var generated = new HashSet<>(generatedCandidates.keySet());
            generated.retainAll(remove.keySet());
            return new Changes(dimension, Map.copyOf(remove), Map.copyOf(relight), Set.copyOf(generated));
        }

        private void addDistortedGeneration() {
            badDonors.retainAll(remove.keySet());
            if (badDonors.isEmpty())
                return;
            for (var entry : generatedCandidates.entrySet()) {
                ChunkPos pos = entry.getKey();
                if (hasNearbyBadDonor(pos))
                    remove.put(pos, entry.getValue());
            }
        }

        private boolean hasNearbyBadDonor(ChunkPos pos) {
            int x = pos.getRegionX() * 32 + pos.getRegionLocalX();
            int z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
            for (int dz = -7; dz <= 7; dz++)
                for (int dx = -7; dx <= 7; dx++)
                    if (dx * dx + dz * dz <= 49 && badDonors.contains(new ChunkPos(x + dx, z + dz)))
                        return true;
            return false;
        }

        private void admitConnectedSpills() throws IOException {
            var inspectedAnchors = new HashSet<ChunkPos>();
            var captures = new HashSet<ChunkPos>();
            boolean admitted;
            do {
                int previousSize = remove.size();
                for (ChunkPos pos : nativeVoids.keySet()) inspectAnchors(pos, inspectedAnchors, captures);
                for (ChunkPos pos : SeedTerrainBiomes.corroboratedNativeVoids(
                             nativeVoids.keySet(), archivedVoids, captures))
                    remove.put(pos, nativeVoids.remove(pos));
                var pending = pendingSpills.entrySet().iterator();
                admitted = false;
                while (pending.hasNext()) {
                    var entry = pending.next();
                    if (entry.getValue().spill().reaches(this::neighbor)) {
                        admitSpill(entry.getKey(), entry.getValue());
                        pending.remove();
                        admitted = true;
                    }
                }
                admitted |= remove.size() != previousSize;
            } while (admitted && (!pendingSpills.isEmpty() || !nativeVoids.isEmpty()));
        }

        private void inspectAnchors(ChunkPos pos, Set<ChunkPos> inspected, Set<ChunkPos> captures)
                throws IOException {
            int x = pos.getRegionX() * 32 + pos.getRegionLocalX();
            int z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
            for (int[] offset : CARDINAL) {
                int neighborX = x + offset[0], neighborZ = z + offset[1];
                var next = new ChunkPos(neighborX, neighborZ);
                if (!inspected.add(next))
                    continue;
                if (indexedCapture(next)) {
                    if (neighbor(neighborX, neighborZ) != null)
                        captures.add(next);
                } else if (!sources.containsKey(next) && !archivedVoids.contains(next)) {
                    var archived = AnvilReader.readChunk(archivedDirectory.resolve(regionName(next)), next);
                    if (supported(archived) && atPosition(archived, next)
                            && SeedTerrainBiomes.isOwnedVoid(archived, dimension))
                        archivedVoids.add(next);
                }
            }
        }

        private void collectRelight(Path file, Set<ChunkPos> candidates, Map<ChunkPos, Path> relight)
                throws IOException {
            proof(file);
            var coordinates = AnvilReader.coordinates(file);
            try (var region = AnvilReader.open(file, coordinates.x(), coordinates.z())) {
                for (ChunkPos pos : candidates) {
                    if (!region.positions().contains(pos))
                        continue;
                    proof(AnvilReader.externalFile(file.getParent(), pos));
                    var chunk = region.read(pos);
                    if (supported(chunk) && atPosition(chunk, pos) && SeedTerrainLighting.hasSavedLight(chunk)
                            && !SeedCaptureReceipt.isUnsettled(chunk, metadata.nodeId, sources.get(pos)))
                        relight.put(pos, file);
                }
            }
        }

        private boolean indexedCapture(ChunkPos pos) {
            var row = sources.get(pos);
            return row != null
                    && ("world_mirror".equals(row.updateSource())
                            || ChunkIndexStore.REPLAY_IMPORT.equals(row.updateSource()));
        }

        private CompoundTag read(ChunkPos pos) throws IOException {
            var file = region(pos);
            if (verifiedRegions.add(file))
                proof(file);
            proof(AnvilReader.externalFile(file.getParent(), pos));
            return AnvilReader.readChunk(file, pos);
        }

        private Path region(ChunkPos pos) {
            return directory.resolve(regionName(pos));
        }

        private void proof(Path path) throws IOException {
            FileProof.requirePlainPath(world, path);
            var relative = world.relativize(path);
            if (!proofs.containsKey(relative))
                proofs.put(relative,
                        Files.notExists(path, LinkOption.NOFOLLOW_LINKS) ? null : FileProof.sha256(path));
        }
    }

    private static String regionName(ChunkPos pos) {
        return "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
    }

    private static boolean supported(CompoundTag chunk) {
        return chunk != null && chunk.getIntOr("DataVersion", 0) > 0
                && chunk.getIntOr("DataVersion", Integer.MAX_VALUE)
                <= SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static boolean atPosition(CompoundTag chunk, ChunkPos pos) {
        return chunk.getIntOr("xPos", Integer.MIN_VALUE) == pos.getRegionX() * 32 + pos.getRegionLocalX()
                && chunk.getIntOr("zPos", Integer.MIN_VALUE) == pos.getRegionZ() * 32 + pos.getRegionLocalZ();
    }

    private static void addNeighbors(ChunkPos pos, Set<ChunkPos> result) {
        int x = pos.getRegionX() * 32 + pos.getRegionLocalX();
        int z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) result.add(new ChunkPos(x + dx, z + dz));
    }

    private static Path auxiliary(Path region, String kind) {
        return region.getParent().resolveSibling(kind).resolve(region.getFileName());
    }
}
