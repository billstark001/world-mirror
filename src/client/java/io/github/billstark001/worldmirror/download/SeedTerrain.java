package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.SeedConversion.Phase;
import io.github.billstark001.worldmirror.download.SeedConversion.Progress;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.io.SeedChunkProvenance;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.blending.BlendingData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** Classifies on-disk terrain before a generation change and describes only proven mutations. */
public final class SeedTerrain {
    private static final List<ResourceKey<Level>> DIMENSIONS =
            List.of(Level.OVERWORLD, Level.NETHER, Level.END);
    private static final List<String> CHUNK_CONTENT =
            List.of("block_entities", "entities", "Entities", "block_ticks", "fluid_ticks");
    // BlendingData only recognizes these vanilla blocks as surfaces for its height samples.
    private static final Set<String> BLENDING_SURFACES =
            Set.of("minecraft:podzol", "minecraft:gravel", "minecraft:grass_block", "minecraft:stone",
                    "minecraft:coarse_dirt", "minecraft:sand", "minecraft:red_sand", "minecraft:mycelium",
                    "minecraft:snow_block", "minecraft:terracotta", "minecraft:dirt");
    private static final int[] BLENDING_EDGE_SAMPLES = {0, 4, 8, 12, 15};
    // Vanilla height samples reach roughly seven chunks. Axial and diagonal
    // steps approximate that radius while keeping protected gaps out of the rewrite.
    private static final int BLENDING_REACH = 70;
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
            var regions = regions(root, directory, dimension, progress);
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
            Set<ChunkPos> nearDonors = regenerationBand(donors, present, progress);
            Set<ChunkPos> removedHere = new HashSet<>();
            for (Region region : regions) {
                Path entities = sibling(region, "entities");
                Path poi = sibling(region, "poi");
                proof(root, region.file(), proofs, progress);
                proof(root, entities, proofs, progress);
                proof(root, poi, proofs, progress);
                Map<ChunkPos, Edit> changes = new HashMap<>();
                try (var terrain = AnvilReader.open(region.file(), region.x(), region.z());
                        var entityData = AnvilReader.open(entities, region.x(), region.z());
                        var poiData = AnvilReader.open(poi, region.x(), region.z())) {
                    for (ChunkPos pos : terrain.positions()
                                    .stream()
                                    .sorted(java.util.Comparator.comparingInt(SeedTerrain::x)
                                                    .thenComparingInt(SeedTerrain::z))
                                    .toList()) {
                        proof(root, external(region.file(), pos), proofs, progress);
                        proof(root, external(entities, pos), proofs, progress);
                        proof(root, external(poi, pos), proofs, progress);
                        CompoundTag chunk = terrain.read(pos);
                        String source = rows.get(pos);
                        boolean ownedBlend = hasOwnedBlend(chunk);
                        boolean delete = false;
                        boolean remap = false;
                        if (operation == SeedConversion.Operation.SET) {
                            boolean placeholder = source == null && isOwnedVoid(chunk, dimension);
                            delete = placeholder
                                    && !hasAuxiliary(root, entities, entityData, poi, poiData, pos);
                            remap = !delete && hasMirrorBiomes(chunk, dimension);
                            if (!delete) {
                                if (placeholder || source != null || SeedChunkProvenance.read(chunk) != null)
                                    protectedChunks++;
                                else
                                    unknown++;
                            }
                        } else if (operation == SeedConversion.Operation.RESEED) {
                            if (source == null && SeedChunkProvenance.read(chunk) == null)
                                unknown++;
                            else
                                protectedChunks++;
                        } else if (source == null || ChunkIndexStore.SEED_GENERATED.equals(source)) {
                            var entry = SeedChunkProvenance.read(chunk);
                            if (entry == null) {
                                unknown++;
                            } else if ((operation == SeedConversion.Operation.REMOVE && cleanup
                                               || nearDonors.contains(pos))
                                    && metadata.generation.seedEpoch().equals(entry.epoch())
                                    && SeedChunkProvenance.isUnmodified(chunk, entry)) {
                                if (!hasAuxiliary(root, entities, entityData, poi, poiData, pos)) {
                                    if (regenerate)
                                        regeneration.put(pos, region);
                                    else
                                        delete = true;
                                }
                                if (!delete)
                                    protectedChunks++;
                            } else {
                                protectedChunks++;
                            }
                        } else {
                            protectedChunks++;
                        }
                        if (delete) {
                            removed++;
                            removedHere.add(pos);
                        }
                        if (remap)
                            repaired++;
                        if (surface != null && !delete && dimension.equals(Level.OVERWORLD)
                                && captured.contains(pos))
                            sample(pos, chunk);
                        boolean clearBlend = !delete && ownedBlend
                                && (operation == SeedConversion.Operation.REMOVE
                                        || operation == SeedConversion.Operation.RESEED);
                        if (delete || remap || clearBlend)
                            changes.put(pos, new Edit(delete, remap, false, clearBlend));
                    }
                }
                if (!changes.isEmpty())
                    edits.put(region, changes);
                regionScanned();
            }
            for (ChunkPos pos : regenerationBand(donors, regeneration.keySet(), progress)) {
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
                Set<ChunkPos> candidates = regenerate ? donors : captured;
                for (Region region : regions) {
                    progress.checkpoint();
                    try (var terrain = AnvilReader.open(region.file(), region.x(), region.z())) {
                        for (ChunkPos pos : terrain.positions()) {
                            progress.checkpoint();
                            if (!candidates.contains(pos) || !hasGap(pos, present))
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
            }
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
                proof(root, region.file(), proofs, progress);
                try (var terrain = AnvilReader.open(region.file(), region.x(), region.z())) {
                    for (ChunkPos pos : terrain.positions()) {
                        progress.checkpoint();
                        if (!captured.contains(pos))
                            continue;
                        proof(root, external(region.file(), pos), proofs, progress);
                        if (canDonateBlend(terrain.read(pos)))
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
                    Integer existing = surfaceHeight(chunk, dx, dz);
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
            replaceBiomes(chunk, dimension);
        if (edit.removeBlend())
            removeBlend(chunk);
        if (edit.addBlend())
            markBlend(chunk, blendEpoch);
        return chunk;
    }

    static boolean isCaptured(String source) {
        return "world_mirror".equals(source) || ChunkIndexStore.REPLAY_IMPORT.equals(source);
    }

    static boolean hasAuxiliary(Path root, Path entities, AnvilReader.Reader entityData, Path poi,
            AnvilReader.Reader poiData, ChunkPos pos) throws IOException {
        if (entityData.positions().contains(pos)) {
            FileProof.requirePlainPath(root, external(entities, pos));
            var entry = entityData.read(pos);
            if (entry == null || nonemptyList(entry, "Entities") || nonemptyList(entry, "entities")
                    || !(entry.get("Entities") instanceof ListTag
                            || entry.get("entities") instanceof ListTag))
                return true;
        }
        if (poiData.positions().contains(pos)) {
            FileProof.requirePlainPath(root, external(poi, pos));
            var entry = poiData.read(pos);
            if (entry == null || !(entry.get("Sections") instanceof CompoundTag sections))
                return true;
            for (String key : sections.keySet()) {
                if (!(sections.get(key) instanceof CompoundTag section) || nonemptyList(section, "Records"))
                    return true;
            }
        }
        return false;
    }

    static Path sibling(Region region, String kind) {
        return region.file().getParent().resolveSibling(kind).resolve(region.file().getFileName());
    }

    static Path external(Path region, ChunkPos pos) {
        return region.getParent().resolve("c." + x(pos) + "." + z(pos) + ".mcc");
    }

    static void proof(Path root, Path path, Map<Path, String> proofs, Progress progress) throws IOException {
        progress.checkpoint();
        FileProof.requirePlainPath(root, path);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
            return;
        Path relative = root.relativize(path);
        if (!proofs.containsKey(relative))
            proofs.put(relative, FileProof.sha256(path, progress::checkpoint));
    }

    private static List<Region> regions(
            Path root, Path directory, ResourceKey<Level> dimension, Progress progress) throws IOException {
        progress.checkpoint();
        FileProof.requirePlainPath(root, directory);
        FileProof.requirePlainDirectory(directory);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS))
            return List.of();
        List<Region> files = new ArrayList<>();
        try (var entries = Files.list(directory)) {
            var sorted = entries.peek(ignored -> progress.checkpoint()).sorted().iterator();
            while (sorted.hasNext()) {
                Path file = sorted.next();
                progress.checkpoint();
                if (!AnvilReader.REGION_NAME.matcher(file.getFileName().toString()).matches())
                    continue;
                if (FileProof.requirePlainFile(file).size() == 0)
                    continue;
                var coordinates = AnvilReader.coordinates(file);
                files.add(new Region(dimension, file, coordinates.x(), coordinates.z()));
            }
        }
        return files;
    }

    private record Distance(ChunkPos pos, int cost) {}

    private static Set<ChunkPos> regenerationBand(
            Set<ChunkPos> donors, Set<ChunkPos> eligible, Progress progress) {
        if (donors.isEmpty() || eligible.isEmpty())
            return Set.of();
        Map<ChunkPos, Integer> distances = new HashMap<>();
        var frontier = new PriorityQueue<Distance>(java.util.Comparator.comparingInt(Distance::cost));
        for (ChunkPos donor : donors) {
            progress.checkpoint();
            if (hasGap(donor, donors))
                frontier.add(new Distance(donor, 0));
        }
        while (!frontier.isEmpty()) {
            progress.checkpoint();
            Distance from = frontier.remove();
            if (from.cost() > distances.getOrDefault(from.pos(), from.cost()))
                continue;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0)
                        continue;
                    int cost = from.cost() + (dx == 0 || dz == 0 ? 10 : 14);
                    if (cost > BLENDING_REACH)
                        continue;
                    var neighbor = new ChunkPos(x(from.pos()) + dx, z(from.pos()) + dz);
                    if (!donors.contains(neighbor) && eligible.contains(neighbor)
                            && cost < distances.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                        distances.put(neighbor, cost);
                        frontier.add(new Distance(neighbor, cost));
                    }
                }
            }
        }
        return distances.keySet();
    }

    private static boolean hasGap(ChunkPos pos, Set<ChunkPos> present) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                if ((dx != 0 || dz != 0) && !present.contains(new ChunkPos(x(pos) + dx, z(pos) + dz)))
                    return true;
        return false;
    }

    private static int x(ChunkPos pos) {
        return pos.getRegionX() * 32 + pos.getRegionLocalX();
    }
    private static int z(ChunkPos pos) {
        return pos.getRegionZ() * 32 + pos.getRegionLocalZ();
    }

    private static boolean nonemptyList(CompoundTag tag, String key) {
        return tag.contains(key) && (!(tag.get(key) instanceof ListTag list) || !list.isEmpty());
    }

    private static boolean isOwnedVoid(CompoundTag chunk, ResourceKey<Level> dimension) {
        for (String key : CHUNK_CONTENT)
            if (nonemptyList(chunk, key))
                return false;
        var structures = chunk.getCompoundOrEmpty("structures");
        if (!structures.getCompoundOrEmpty("starts").isEmpty()
                || !structures.getCompoundOrEmpty("References").isEmpty()
                || chunk.contains("below_zero_retrogen") || chunk.contains("UpgradeData")
                || chunk.contains(SeedTerrainDigest.BLENDING_DATA)
                || chunk.contains(SeedTerrainDigest.ATTACHMENTS))
            return false;
        boolean hasTerrain = false;
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            if (!(tag instanceof CompoundTag section))
                return false;
            if (!section.contains("biomes") && !section.contains("block_states"))
                continue;
            var blocks = section.getCompoundOrEmpty("block_states");
            var biomes = section.getCompoundOrEmpty("biomes");
            var blockPalette = blocks.getListOrEmpty("palette");
            var biomePalette = biomes.getListOrEmpty("palette");
            if (blockPalette.size() != 1 || biomePalette.size() != 1 || !isVanillaAir(blockPalette.getFirst())
                    || !biomePalette.getStringOr(0, "").equals(mirrorBiome(dimension))
                    || blocks.contains("data") || biomes.contains("data"))
                return false;
            hasTerrain = true;
        }
        return hasTerrain;
    }

    private static boolean isVanillaAir(Tag state) {
        if (state instanceof StringTag name)
            return name.asString().orElse("").equals("minecraft:air");
        return state instanceof CompoundTag compound && compound.size() == 1
                && compound.getStringOr("Name", "").equals("minecraft:air");
    }

    private static boolean canDonateBlend(CompoundTag chunk) {
        if (!chunk.getStringOr("Status", "").matches("(minecraft:)?full")
                || chunk.getIntOr("DataVersion", 0)
                        > SharedConstants.getCurrentVersion().dataVersion().version())
            return false;
        int minSection = SeedTerrainDigest.BLENDING_MIN_SECTION;
        int maxSection = SeedTerrainDigest.BLENDING_MAX_SECTION;
        if (chunk.contains(SeedTerrainDigest.BLENDING_DATA)) {
            var data = BlendingData.Packed.CODEC
                               .parse(NbtOps.INSTANCE, chunk.get(SeedTerrainDigest.BLENDING_DATA))
                               .result()
                               .orElse(null);
            if (data == null || data.minSection() < minSection || data.maxSection() > maxSection
                    || data.minSection() >= data.maxSection())
                return false;
            minSection = data.minSection();
            maxSection = data.maxSection();
        }
        boolean hasSurface = false;
        for (Tag value : chunk.getListOrEmpty("sections")) {
            if (!(value instanceof CompoundTag section))
                return false;
            int y = section.getIntOr("Y", Integer.MIN_VALUE);
            if (y < minSection || y >= maxSection)
                continue;
            var states = section.getCompoundOrEmpty("block_states");
            var palette = states.getListOrEmpty("palette");
            if (!section.contains("block_states"))
                continue;
            if (palette.isEmpty())
                return false;
            int bits = Math.max(4, bits(palette.size()));
            var data = states.getLongArray("data").orElse(null);
            if (palette.size() == 1) {
                if (states.contains("data"))
                    return false;
                hasSurface |= BLENDING_SURFACES.contains(blockId(palette.getFirst()));
                continue;
            }
            if (data == null || data.length != wordCount(4096, bits))
                return false;
            for (int dy = 0; dy < 16; dy++) {
                for (int offset : BLENDING_EDGE_SAMPLES) {
                    int layer = dy * 256;
                    hasSurface |= hasBlendingSurface(palette, data, bits, layer + offset)
                            || hasBlendingSurface(palette, data, bits, layer + 15 * 16 + offset)
                            || hasBlendingSurface(palette, data, bits, layer + offset * 16)
                            || hasBlendingSurface(palette, data, bits, layer + offset * 16 + 15);
                }
            }
        }
        return hasSurface;
    }

    private static boolean hasBlendingSurface(ListTag palette, long[] data, int bits, int position) {
        int index = cell(data, bits, position);
        return index < palette.size() && BLENDING_SURFACES.contains(blockId(palette.get(index)));
    }

    private static String blockId(Tag state) {
        return state instanceof CompoundTag compound ? compound.getStringOr("Name", "")
                                                     : state.asString().orElse("");
    }

    private static String mirrorBiome(ResourceKey<Level> dimension) {
        return dimension.equals(Level.NETHER) ? "worldmirror:mirror_nether"
                : dimension.equals(Level.END) ? "worldmirror:mirror_end"
                                              : "worldmirror:mirror_overworld";
    }

    private static String normalBiome(ResourceKey<Level> dimension) {
        return dimension.equals(Level.NETHER) ? "minecraft:nether_wastes"
                : dimension.equals(Level.END) ? "minecraft:the_end"
                                              : "minecraft:plains";
    }

    private static boolean hasMirrorBiomes(CompoundTag chunk, ResourceKey<Level> dimension)
            throws IOException {
        boolean result = false;
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            if (!(tag instanceof CompoundTag section))
                throw new IOException("Invalid saved terrain section");
            for (Tag biome : section.getCompoundOrEmpty("biomes").getListOrEmpty("palette")) {
                String key = biome.asString().orElseThrow(() -> new IOException("Invalid saved biome name"));
                if (key.startsWith("worldmirror:")) {
                    if (!key.equals(mirrorBiome(dimension)))
                        throw new IOException("Unexpected owned biome " + key);
                    result = true;
                }
            }
        }
        return result;
    }

    /** PalettedContainer uses non-spanning packed cells. Collapse the palette after replacing our biome. */
    static void replaceBiomes(CompoundTag chunk, ResourceKey<Level> dimension) throws IOException {
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            var section = (CompoundTag) tag;
            var container = section.getCompoundOrEmpty("biomes");
            var previous = container.getListOrEmpty("palette");
            if (!previous.contains(StringTag.valueOf(mirrorBiome(dimension))))
                continue;
            ListTag palette = new ListTag();
            Map<String, Integer> indices = new HashMap<>();
            int[] remap = new int[previous.size()];
            for (int i = 0; i < previous.size(); i++) {
                String key = previous.getStringOr(i, "");
                if (key.equals(mirrorBiome(dimension)))
                    key = normalBiome(dimension);
                Integer existing = indices.get(key);
                if (existing == null) {
                    existing = palette.size();
                    indices.put(key, existing);
                    palette.add(StringTag.valueOf(key));
                }
                remap[i] = existing;
            }
            int oldBits = bits(previous.size());
            long[] oldData = container.getLongArray("data").orElse(new long[0]);
            if (oldBits != 0 && oldData.length != wordCount(64, oldBits))
                throw new IOException("Invalid biome packed array");
            int newBits = bits(palette.size());
            long[] data = newBits == 0 ? null : new long[wordCount(64, newBits)];
            for (int i = 0; i < 64; i++) {
                int index = oldBits == 0 ? 0 : cell(oldData, oldBits, i);
                if (index >= remap.length)
                    throw new IOException("Invalid biome palette index");
                if (data != null)
                    data[i / (64 / newBits)] |= (long) remap[index] << ((i % (64 / newBits)) * newBits);
            }
            container.put("palette", palette);
            if (data == null)
                container.remove("data");
            else
                container.putLongArray("data", data);
        }
    }

    /** Terrain captured from a void mirror can still include its datapack's biome identifiers. */
    public static void repairCapturedBiomes(CompoundTag chunk, ResourceKey<Level> dimension)
            throws IOException {
        if (DIMENSIONS.contains(dimension) && hasMirrorBiomes(chunk, dimension))
            replaceBiomes(chunk, dimension);
    }

    private static int bits(int size) {
        return 32 - Integer.numberOfLeadingZeros(size - 1);
    }
    private static int wordCount(int cells, int bits) {
        return (cells + 64 / bits - 1) / (64 / bits);
    }
    private static int cell(long[] data, int bits, int index) {
        return (int) ((data[index / (64 / bits)] >>> ((index % (64 / bits)) * bits)) & ((1L << bits) - 1));
    }

    private static Integer surfaceHeight(CompoundTag chunk, int dx, int dz) {
        var values = chunk.getCompoundOrEmpty("Heightmaps").getLongArray("WORLD_SURFACE").orElse(null);
        if (values == null || values.length != wordCount(256, 9))
            return null;
        int height = cell(values, 9, dx + 16 * dz);
        // Both the persisted heightmap and the native seed sampler report the first free Y.
        return height == 0 ? null : height - 64;
    }

    private static boolean hasOwnedBlend(CompoundTag chunk) {
        String owner = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS)
                               .getStringOr(SeedTerrainDigest.BLENDING_ATTACHMENT, "");
        try {
            return UUID.fromString(owner).toString().equals(owner);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Whether this chunk's blending marker belongs to the current seed epoch. */
    public static boolean ownsBlend(CompoundTag chunk, UUID epoch) {
        return chunk != null
                && epoch.toString().equals(chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS)
                                .getStringOr(SeedTerrainDigest.BLENDING_ATTACHMENT, ""));
    }

    public static void markBlend(CompoundTag chunk, UUID epoch) {
        var data = new CompoundTag();
        data.putInt("min_section", SeedTerrainDigest.BLENDING_MIN_SECTION);
        data.putInt("max_section", SeedTerrainDigest.BLENDING_MAX_SECTION);
        chunk.put(SeedTerrainDigest.BLENDING_DATA, data);
        var attachments = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS);
        attachments.putString(SeedTerrainDigest.BLENDING_ATTACHMENT, epoch.toString());
        chunk.put(SeedTerrainDigest.ATTACHMENTS, attachments);
    }

    /** Do not overwrite generated terrain that owns separate entity or POI data. */
    public static boolean hasNoAuxiliaryData(Path root, ResourceKey<Level> dimension, ChunkPos pos)
            throws IOException {
        Path directory = WorldSaveLayout.dimensionDirectory(root, dimension);
        String filename = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
        Path entities = directory.resolve("entities").resolve(filename);
        Path poi = directory.resolve("poi").resolve(filename);
        FileProof.requirePlainPath(root, entities);
        FileProof.requirePlainPath(root, poi);
        try (var entityData = AnvilReader.open(entities, pos.getRegionX(), pos.getRegionZ());
                var poiData = AnvilReader.open(poi, pos.getRegionX(), pos.getRegionZ())) {
            return !hasAuxiliary(root, entities, entityData, poi, poiData, pos);
        }
    }

    private static void removeBlend(CompoundTag chunk) {
        var attachments = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS);
        attachments.remove(SeedTerrainDigest.BLENDING_ATTACHMENT);
        if (attachments.isEmpty())
            chunk.remove(SeedTerrainDigest.ATTACHMENTS);
        chunk.remove(SeedTerrainDigest.BLENDING_DATA);
    }
}
