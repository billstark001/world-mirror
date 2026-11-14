package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.SeedConversion.Phase;
import io.github.billstark001.worldmirror.download.SeedConversion.Progress;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.ChunkAuxiliaryData;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.SeedChunkProvenance;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Classifies on-disk terrain before a generation change and describes only proven mutations. */
final class SeedTerrain {
    private static final List<ResourceKey<Level>> DIMENSIONS =
            List.of(Level.OVERWORLD, Level.NETHER, Level.END);
    private static final int SAMPLE_LIMIT = 128;

    record Region(ResourceKey<Level> dimension, Path file, int x, int z) {}
    record Edit(boolean delete, boolean remapBiome, boolean addBlend, boolean removeBlend) {}
    record Plan(Map<Region, Map<ChunkPos, Edit>> edits, Map<Path, String> proofs, int removed, int repaired,
            int blended, int protectedChunks, int unknown, int samples, double meanHeightDifference) {}

    private SeedTerrain() {}

    static Plan scan(Path root, WorldMetadata metadata, SeedConversion.Operation operation, long seed,
            boolean cleanup, boolean blending, Progress progress, Path indexRoot) throws Exception {
        Map<ResourceKey<Level>, List<Region>> dimensions = new LinkedHashMap<>();
        int total = 0;
        for (var dimension : DIMENSIONS) {
            var directory = WorldSaveLayout.regionDirectory(root, dimension);
            var regions = SeedTerrainFiles.regions(root, directory, dimension, progress);
            dimensions.put(dimension, regions);
            boolean overworld = dimension.equals(Level.OVERWORLD);
            int passes = 2;
            if (overworld && operation == SeedConversion.Operation.REGENERATE_BORDER)
                passes++;
            if (overworld && blending && operation != SeedConversion.Operation.REMOVE)
                passes++;
            total += regions.size() * passes;
        }
        var surface =
                operation == SeedConversion.Operation.SET || operation == SeedConversion.Operation.RESEED
                ? WorldStructureCreator.seedSurface(seed)
                : null;
        var scan =
                new Scan(root, metadata, operation, cleanup, blending, surface, progress, total, indexRoot);
        progress.update(Phase.SCANNING, 0, total);
        for (var dimension : dimensions.entrySet()) scan.dimension(dimension.getKey(), dimension.getValue());
        return new Plan(Map.copyOf(scan.edits), Map.copyOf(scan.proofs), scan.removed, scan.repaired,
                scan.blended, scan.protectedChunks, scan.unknown, scan.samples,
                scan.samples == 0 ? 0 : (double) scan.heightDifference / scan.samples);
    }

    private static final class Scan {
        final Path root;
        final WorldMetadata metadata;
        final SeedConversion.Operation operation;
        final boolean cleanup, blending;
        final WorldStructureCreator.SeedSurface surface;
        final Progress progress;
        final int total;
        final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        final Path indexRoot;
        final Map<Region, Map<ChunkPos, Edit>> edits = new LinkedHashMap<>();
        final Map<Path, String> proofs = new LinkedHashMap<>();
        int removed, repaired, blended, protectedChunks, unknown, samples;
        int completed;
        long heightDifference;

        Scan(Path root, WorldMetadata metadata, SeedConversion.Operation operation, boolean cleanup,
                boolean blending, WorldStructureCreator.SeedSurface surface, Progress progress, int total,
                Path indexRoot) {
            this.root = root;
            this.metadata = metadata;
            this.operation = operation;
            this.cleanup = cleanup;
            this.blending = blending;
            this.surface = surface;
            this.progress = progress;
            this.total = total;
            this.indexRoot = indexRoot;
        }

        private void regionScanned() {
            progress.update(Phase.SCANNING, ++completed, total);
        }

        private void loadIndexRows(String dimension, Map<ChunkPos, String> rows)
                throws java.sql.SQLException {
            for (var row : ChunkDatabase.queryAllReadOnly(indexRoot, metadata.sourceId, dimension)) {
                progress.checkpoint();
                rows.put(new ChunkPos(row.x(), row.z()), row.updateSource());
            }
        }

        void dimension(ResourceKey<Level> dimension, List<Region> regions) throws Exception {
            progress.checkpoint();
            var dim = dimension.identifier().toString();
            Map<ChunkPos, String> rows = new HashMap<>();
            if (indexRoot != null)
                loadIndexRows(dim, rows);
            Set<ChunkPos> present = new HashSet<>();
            for (Region region : regions) {
                progress.checkpoint();
                present.addAll(McaWriteSupport.listChunkPositions(region.file(), region.x(), region.z()));
                regionScanned();
            }
            Set<ChunkPos> captured = new HashSet<>();
            for (var entry : rows.entrySet()) {
                progress.checkpoint();
                if (present.contains(entry.getKey()) && isCaptured(entry.getValue()))
                    captured.add(entry.getKey());
            }
            boolean regenerate = operation == SeedConversion.Operation.REGENERATE_BORDER;
            Set<ChunkPos> donors = regenerate && dimension.equals(Level.OVERWORLD)
                    ? findRegenerationDonors(regions, captured)
                    : Set.of();
            Map<ChunkPos, Region> regeneration = new HashMap<>();
            Set<ChunkPos> nearDonors = SeedTerrainBlending.regenerationBand(donors, present, progress);
            Set<ChunkPos> removedHere = new HashSet<>();
            for (Region region : regions)
                inspectRegion(region, rows, captured, nearDonors, regeneration, removedHere);
            for (ChunkPos pos :
                    SeedTerrainBlending.regenerationBand(donors, regeneration.keySet(), progress)) {
                progress.checkpoint();
                edits.computeIfAbsent(regeneration.get(pos), ignored -> new HashMap<>())
                        .put(pos, new Edit(true, false, false, false));
                removedHere.add(pos);
                removed++;
                protectedChunks--;
            }
            if (blending && operation != SeedConversion.Operation.REMOVE
                    && dimension.equals(Level.OVERWORLD)) {
                present.removeAll(removedHere);
                for (Region region : regions)
                    markBlendDonors(region, regenerate ? donors : captured, present);
            }
        }

        private void inspectRegion(Region region, Map<ChunkPos, String> rows, Set<ChunkPos> captured,
                Set<ChunkPos> nearDonors, Map<ChunkPos, Region> regeneration, Set<ChunkPos> removedHere)
                throws IOException {
            Path entities = SeedTerrainFiles.sibling(region, "entities");
            Path poi = SeedTerrainFiles.sibling(region, "poi");
            proof(region.file());
            proof(entities);
            proof(poi);
            Map<ChunkPos, Edit> changes = new HashMap<>();
            try (var terrain = AnvilReader.open(region.file(), region.x(), region.z());
                    var auxiliary = new ChunkAuxiliaryData(root, region.file(), region.x(), region.z())) {
                auxiliary.inspectRegions();
                for (ChunkPos pos : terrain.positions()
                                .stream()
                                .sorted(java.util.Comparator.comparingInt(SeedTerrainFiles::chunkX)
                                                .thenComparingInt(SeedTerrainFiles::chunkZ))
                                .toList()) {
                    proof(AnvilReader.externalFile(region.file().getParent(), pos));
                    proof(AnvilReader.externalFile(entities.getParent(), pos));
                    proof(AnvilReader.externalFile(poi.getParent(), pos));
                    CompoundTag chunk = terrain.read(pos);
                    if (chunk.getIntOr("DataVersion", 0) > dataVersion)
                        throw new IOException("Terrain was saved by a newer Minecraft version: " + pos
                                + " in " + region.file());
                    Edit edit = inspectChunk(region.dimension(), chunk, rows.get(pos),
                            nearDonors.contains(pos), () -> auxiliary.hasData(pos));
                    if (edit.delete()) {
                        if (operation == SeedConversion.Operation.REGENERATE_BORDER) {
                            regeneration.put(pos, region);
                            protectedChunks++;
                            continue;
                        }
                        removed++;
                        removedHere.add(pos);
                    }
                    if (edit.remapBiome())
                        repaired++;
                    if (surface != null && !edit.delete() && region.dimension().equals(Level.OVERWORLD)
                            && captured.contains(pos))
                        sample(pos, chunk);
                    if (edit.delete() || edit.remapBiome() || edit.removeBlend())
                        changes.put(pos, edit);
                }
            }
            if (!changes.isEmpty())
                edits.put(region, changes);
            regionScanned();
        }

        private Edit inspectChunk(ResourceKey<Level> dimension, CompoundTag chunk, String source,
                boolean nearDonor, AuxiliaryCheck auxiliary) throws IOException {
            boolean delete = false;
            boolean remap = false;
            if (operation == SeedConversion.Operation.SET) {
                boolean placeholder = source == null && SeedTerrainBiomes.isOwnedVoid(chunk, dimension);
                delete = placeholder && !auxiliary.hasData();
                remap = !delete && SeedTerrainBiomes.hasMirrorBiomes(chunk, dimension);
                if (!delete)
                    countRetained(placeholder || source != null || SeedChunkProvenance.read(chunk) != null);
            } else if (operation == SeedConversion.Operation.RESEED) {
                countRetained(source != null || SeedChunkProvenance.read(chunk) != null);
            } else if (source != null && !ChunkIndexStore.SEED_GENERATED.equals(source)) {
                protectedChunks++;
            } else {
                var entry = SeedChunkProvenance.read(chunk);
                delete = entry != null
                        && ((operation == SeedConversion.Operation.REMOVE && cleanup) || nearDonor)
                        && metadata.generation.seedEpoch().equals(entry.epoch())
                        && SeedChunkProvenance.isUnmodified(chunk, entry) && !auxiliary.hasData();
                if (!delete)
                    countRetained(entry != null);
            }
            boolean clearBlend = !delete
                    && (operation == SeedConversion.Operation.REMOVE
                            || operation == SeedConversion.Operation.RESEED)
                    && SeedTerrainBlending.hasOwnedBlend(chunk);
            return new Edit(delete, remap, false, clearBlend);
        }

        private void countRetained(boolean recognized) {
            if (recognized)
                protectedChunks++;
            else
                unknown++;
        }

        @FunctionalInterface
        private interface AuxiliaryCheck {
            boolean hasData() throws IOException;
        }

        private void markBlendDonors(Region region, Set<ChunkPos> candidates, Set<ChunkPos> present)
                throws IOException {
            progress.checkpoint();
            try (var terrain = AnvilReader.open(region.file(), region.x(), region.z())) {
                for (ChunkPos pos : terrain.positions()) {
                    progress.checkpoint();
                    if (!candidates.contains(pos) || !SeedTerrainBlending.hasGap(pos, present))
                        continue;
                    CompoundTag chunk = terrain.read(pos);
                    var changes = edits.get(region);
                    var old = changes == null ? null : changes.get(pos);
                    if (!chunk.getStringOr("Status", "").matches("(minecraft:)?full")
                            || (chunk.contains(SeedTerrainDigest.BLENDING_DATA)
                                    && (old == null || !old.removeBlend())))
                        continue;
                    edits.computeIfAbsent(region, ignored -> new HashMap<>())
                            .put(pos,
                                    new Edit(false, old != null && old.remapBiome(), true,
                                            old != null && old.removeBlend()));
                    blended++;
                }
            }
            regionScanned();
        }

        private void proof(Path path) throws IOException {
            SeedTerrainFiles.proof(root, path, proofs, progress);
        }

        private Set<ChunkPos> findRegenerationDonors(List<Region> regions, Set<ChunkPos> captured)
                throws IOException {
            Set<ChunkPos> donors = new HashSet<>();
            if (captured.isEmpty()) {
                completed += regions.size();
                progress.update(Phase.SCANNING, completed, total);
                return donors;
            }
            for (Region region : regions) {
                proof(region.file());
                try (var terrain = AnvilReader.open(region.file(), region.x(), region.z())) {
                    for (ChunkPos pos : terrain.positions()) {
                        progress.checkpoint();
                        if (!captured.contains(pos))
                            continue;
                        proof(AnvilReader.externalFile(region.file().getParent(), pos));
                        if (SeedTerrainBlending.canDonateBlend(terrain.read(pos)))
                            donors.add(pos);
                    }
                }
                regionScanned();
            }
            return donors;
        }

        void sample(ChunkPos pos, CompoundTag chunk) {
            if (samples >= SAMPLE_LIMIT)
                return;
            int[] offsets = {3, 12};
            for (int dz : offsets) {
                for (int dx : offsets) {
                    Integer existing = TerrainBitStorage.surfaceHeight(chunk, dx, dz);
                    if (existing == null || samples >= SAMPLE_LIMIT)
                        continue;
                    int proposed = surface.height(pos.getMinBlockX() + dx, pos.getMinBlockZ() + dz);
                    heightDifference += Math.abs((long) existing - proposed);
                    samples++;
                }
            }
        }
    }

    static CompoundTag transform(ChunkPos pos, CompoundTag chunk, Edit edit, ResourceKey<Level> dimension,
            UUID blendEpoch) throws IOException {
        if (edit == null)
            return chunk;
        if (chunk == null)
            throw new IOException("Terrain changed before conversion at " + pos);
        if (edit.delete())
            return null;
        if (edit.remapBiome())
            SeedTerrainBiomes.replaceBiomes(chunk, dimension);
        if (edit.removeBlend())
            SeedTerrainBlending.removeBlend(chunk);
        if (edit.addBlend())
            SeedTerrainBlending.markBlend(chunk, blendEpoch);
        return chunk;
    }

    private static boolean isCaptured(String source) {
        return "world_mirror".equals(source) || ChunkIndexStore.REPLAY_IMPORT.equals(source);
    }
}
