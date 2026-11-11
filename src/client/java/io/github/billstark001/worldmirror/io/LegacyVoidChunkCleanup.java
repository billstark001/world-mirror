package io.github.billstark001.worldmirror.io;


import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Removes only chunks produced by the pre-schema all-air void generator.
 *
 * <p>A chunk is eligible only when every saved biome palette is exclusively
 * {@code minecraft:the_void} and every saved block-state palette is air.
 * This deliberately preserves any chunk containing blocks, while letting
 * Minecraft regenerate the blank legacy chunks with the schema-1 generator.
 * The same legacy generator affected all three vanilla dimensions, so each
 * dimension's region directory is inspected. Saved block entities, legacy
 * entities, scheduled ticks, and malformed terrain sections are preserved.</p>
 */
public final class LegacyVoidChunkCleanup {
    private static final List<Path> REGION_DIRECTORIES = List.of(
            // Minecraft 1.21.11's vanilla dimension layout.
            Path.of("region"),
            Path.of("DIM-1", "region"),
            Path.of("DIM1", "region"),
            // Minecraft 26.x's namespaced dimension layout.
            Path.of("dimensions", "minecraft", "overworld", "region"),
            Path.of("dimensions", "minecraft", "the_nether", "region"),
            Path.of("dimensions", "minecraft", "the_end", "region"));
    private static final String VOID_BIOME = "minecraft:the_void";
    private static final List<String> PRESERVED_LISTS = List.of(
            "block_entities", "TileEntities", "Entities", "entities",
            "block_ticks", "fluid_ticks", "TileTicks", "LiquidTicks");

    public record RegionPlan(Path regionFile, List<Integer> chunkIndices) {
        public RegionPlan {
            chunkIndices = List.copyOf(chunkIndices);
        }
    }

    public record Plan(List<RegionPlan> regions) {
        public Plan {
            regions = List.copyOf(regions);
        }

        public static Plan empty() {
            return new Plan(List.of());
        }

        public int chunkCount() {
            return regions.stream().mapToInt(region -> region.chunkIndices().size()).sum();
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (completed, total) -> {};

        void update(int completed, int total);
    }

    private LegacyVoidChunkCleanup() {}

    /** Read-only scan used before backup creation. */
    public static Plan plan(Path worldFolder) throws IOException {
        return plan(worldFolder, ProgressListener.NONE);
    }

    /** Read-only scan used before backup creation, reporting non-empty region files scanned. */
    public static Plan plan(Path worldFolder, ProgressListener progress) throws IOException {
        List<Path> regionFiles = findRegionFiles(worldFolder);
        progress.update(0, regionFiles.size());

        List<RegionPlan> regions = new ArrayList<>();
        int completed = 0;
        int lastPercent = 0;
        for (Path regionFile : regionFiles) {
            List<Integer> chunks = findLegacyVoidChunks(regionFile);
            if (!chunks.isEmpty()) regions.add(new RegionPlan(regionFile, chunks));
            completed++;
            lastPercent = reportProgress(progress, completed, regionFiles.size(), lastPercent);
        }
        return new Plan(regions);
    }

    /** Applies a previously scanned plan through same-directory atomic replacement. */
    public static int apply(Plan plan) throws IOException {
        return apply(plan, ProgressListener.NONE);
    }

    /** Applies a previously scanned plan, reporting region files rewritten. */
    public static int apply(Plan plan, ProgressListener progress) throws IOException {
        int removed = 0;
        progress.update(0, plan.regions().size());
        int completed = 0;
        int lastPercent = 0;
        for (RegionPlan region : plan.regions()) {
            removed += removeChunks(region);
            completed++;
            lastPercent = reportProgress(progress, completed, plan.regions().size(), lastPercent);
        }
        return removed;
    }

    private static List<Integer> findLegacyVoidChunks(Path file) throws IOException {
        AnvilReader.RegionCoordinates region = AnvilReader.coordinates(file);
        List<Integer> chunks = new ArrayList<>();
        AnvilReader.visitRegion(file, region.x(), region.z(), (pos, chunk) -> {
            if (isLegacyVoidChunk(chunk)) chunks.add(pos.getRegionLocalX() + pos.getRegionLocalZ() * 32);
        });
        return chunks;
    }

    private static int removeChunks(RegionPlan plan) throws IOException {
        Path file = plan.regionFile();
        if (Files.size(file) == 0) return 0;
        synchronized (McaWriteSupport.lockFor(file)) {
            AnvilReader.RegionCoordinates region = AnvilReader.coordinates(file);
            java.util.Set<ChunkPos> removed = new java.util.HashSet<>();
            for (int index : plan.chunkIndices()) {
                removed.add(new ChunkPos(region.x() * 32 + index % 32, region.z() * 32 + index / 32));
            }
            RegionWriteTransaction.rewrite(file, Level.OVERWORLD, region.x(), region.z(), removed, (pos, chunk) -> {
                if (!removed.contains(pos)) return chunk;
                if (!isLegacyVoidChunk(chunk)) throw new IOException("Legacy void chunk changed during migration: " + file + " " + pos);
                return null;
            });
            return plan.chunkIndices().size();
        }
    }

    static boolean isLegacyVoidChunk(CompoundTag chunk) {
        if (chunk == null) return false;
        for (String key : PRESERVED_LISTS) {
            Tag value = chunk.get(key);
            if (value != null && (!(value instanceof ListTag list) || !list.isEmpty())) return false;
        }
        if (!(chunk.get("sections") instanceof ListTag sections) || sections.isEmpty()) return false;
        boolean foundTerrain = false;
        for (Tag entry : sections) {
            if (!(entry instanceof CompoundTag section)) return false;
            Tag biomes = section.get("biomes");
            Tag blocks = section.get("block_states");
            // Light-only sections outside the build height carry no terrain.
            if (biomes == null && blocks == null) continue;
            if (!(biomes instanceof CompoundTag biomeData) || !(blocks instanceof CompoundTag blockData)
                    || !paletteContainsOnly(biomeData, VOID_BIOME, false)
                    || !paletteContainsOnly(blockData, null, true)) return false;
            foundTerrain = true;
        }
        return foundTerrain;
    }

    private static List<Path> findRegionFiles(Path worldFolder) throws IOException {
        List<Path> regionFiles = new ArrayList<>();
        for (Path relativeDirectory : REGION_DIRECTORIES) {
            Path regionDirectory = worldFolder.resolve(relativeDirectory);
            if (Files.notExists(regionDirectory)) continue;
            try (var files = Files.list(regionDirectory)) {
                for (Path file : files.toList()) {
                    if (!AnvilReader.REGION_NAME.matcher(file.getFileName().toString()).matches()) continue;
                    var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
                    if (!attributes.isRegularFile()) throw new IOException("Not a regular region file: " + file);
                    if (attributes.size() > 0) regionFiles.add(file);
                }
            }
        }
        regionFiles.sort(Comparator.comparing(Path::toString));
        return regionFiles;
    }

    private static int reportProgress(ProgressListener progress, int completed, int total, int lastPercent) {
        int percent = total <= 0 ? 100 : completed * 100 / total;
        if (percent != lastPercent || completed == total) {
            progress.update(completed, total);
        }
        return percent;
    }

    private static boolean paletteContainsOnly(CompoundTag container, String exactName, boolean airOnly) {
        Tag paletteTag = container.get("palette");
        if (!(paletteTag instanceof ListTag palette) || palette.isEmpty()) return false;
        for (Tag entry : palette) {
            String name = paletteName(entry);
            if (name == null || (airOnly ? !isAir(name) : !exactName.equals(name))) return false;
        }
        return true;
    }

    private static String paletteName(Tag entry) {
        if (entry instanceof StringTag string) return string.asString().orElse(null);
        if (entry instanceof CompoundTag compound) {
            return compound.getString("Name").orElse(null);
        }
        return null;
    }

    private static boolean isAir(String block) {
        return "minecraft:air".equals(block)
                || "minecraft:cave_air".equals(block)
                || "minecraft:void_air".equals(block);
    }

}
