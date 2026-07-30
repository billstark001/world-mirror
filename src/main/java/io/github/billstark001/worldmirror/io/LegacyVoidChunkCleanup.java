package io.github.billstark001.worldmirror.io;

import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.LoadFlags;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.mca.io.RandomAccessMcaFile;
import io.github.ensgijs.nbt.tag.CompoundTag;
import io.github.ensgijs.nbt.tag.ListTag;
import io.github.ensgijs.nbt.tag.StringTag;
import io.github.ensgijs.nbt.tag.Tag;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Removes only chunks produced by the pre-schema all-air void generator.
 *
 * <p>A chunk is eligible only when every saved biome palette is exclusively
 * {@code minecraft:the_void}, every saved block-state palette is air, and it
 * contains no block entities or scheduled ticks.  This deliberately preserves
 * nearby-export terrain and any player-built content, while letting Minecraft
 * regenerate the blank legacy chunks with the schema-1 generator.</p>
 */
public final class LegacyVoidChunkCleanup {
    private static final Path OVERWORLD_REGION_DIRECTORY = Path.of(
            "dimensions", "minecraft", "overworld", "region");
    private static final String VOID_BIOME = "minecraft:the_void";
    private static final int FILE_REPLACE_ATTEMPTS = 10;
    private static final long FILE_REPLACE_RETRY_MILLIS = 200L;

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

    private LegacyVoidChunkCleanup() {}

    /** Read-only scan used before backup creation. */
    public static Plan plan(Path worldFolder) throws IOException {
        Path regionDirectory = worldFolder.resolve(OVERWORLD_REGION_DIRECTORY);
        if (!Files.isDirectory(regionDirectory)) return Plan.empty();

        List<Path> regionFiles;
        try (var files = Files.list(regionDirectory)) {
            regionFiles = files.filter(Files::isRegularFile)
                    .filter(path -> McaFileHelpers.isValidMcaFileName(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        List<RegionPlan> regions = new ArrayList<>();
        for (Path regionFile : regionFiles) {
            List<Integer> chunks = findLegacyVoidChunks(regionFile);
            if (!chunks.isEmpty()) regions.add(new RegionPlan(regionFile, chunks));
        }
        return new Plan(regions);
    }

    /** Applies a previously scanned plan through same-directory atomic replacement. */
    public static int apply(Plan plan) throws IOException {
        int removed = 0;
        for (RegionPlan region : plan.regions()) {
            removed += removeChunks(region);
        }
        return removed;
    }

    private static List<Integer> findLegacyVoidChunks(Path regionFile) throws IOException {
        List<Integer> chunks = new ArrayList<>();
        try (RandomAccessMcaFile<TerrainChunk> region = new RandomAccessMcaFile<>(
                TerrainChunk.class, regionFile, "r").setLoadFlags(LoadFlags.RAW)) {
            for (int index = 0; index < 1024; index++) {
                TerrainChunk chunk = region.read(index);
                if (chunk != null && isLegacyVoidChunk(chunk.getHandle())) chunks.add(index);
            }
        }
        return chunks;
    }

    private static int removeChunks(RegionPlan plan) throws IOException {
        Path regionFile = plan.regionFile();
        synchronized (McaWriteSupport.lockFor(regionFile)) {
            IOException lastFailure = null;
            for (int attempt = 1; attempt <= FILE_REPLACE_ATTEMPTS; attempt++) {
                Path temporaryFile = null;
                IOException failure = null;
                try {
                    temporaryFile = createTemporaryRegionFile(regionFile);
                    Files.copy(regionFile, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
                    int removed = removePlannedChunks(temporaryFile, plan);
                    moveIntoPlace(temporaryFile, regionFile);
                    return removed;
                } catch (IOException exception) {
                    failure = exception;
                } finally {
                    IOException cleanupFailure = deleteTemporaryFile(temporaryFile);
                    if (cleanupFailure != null) {
                        if (failure == null) failure = cleanupFailure;
                        else failure.addSuppressed(cleanupFailure);
                    }
                }
                lastFailure = failure;
                if (attempt < FILE_REPLACE_ATTEMPTS) {
                    waitBeforeRetry(regionFile);
                }
            }
            throw new IOException("Could not replace legacy void region after "
                    + FILE_REPLACE_ATTEMPTS + " attempts: " + regionFile, lastFailure);
        }
    }

    static Path createTemporaryRegionFile(Path regionFile) throws IOException {
        // RandomAccessMcaFile derives the region coordinates from its filename.
        // Keep the original r.X.Z.mca suffix rather than using a generic .tmp.
        return Files.createTempFile(regionFile.getParent(), "worldmirror-",
                "." + regionFile.getFileName());
    }

    private static IOException deleteTemporaryFile(Path temporaryFile) {
        if (temporaryFile == null) return null;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= FILE_REPLACE_ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(temporaryFile);
                return null;
            } catch (IOException failure) {
                lastFailure = failure;
                if (attempt < FILE_REPLACE_ATTEMPTS) {
                    try {
                        waitBeforeRetry(temporaryFile);
                    } catch (IOException interrupted) {
                        return interrupted;
                    }
                }
            }
        }
        return lastFailure;
    }

    private static int removePlannedChunks(Path temporaryFile, RegionPlan plan) throws IOException {
        int removed = 0;
        try (RandomAccessMcaFile<TerrainChunk> region = new RandomAccessMcaFile<>(
                TerrainChunk.class, temporaryFile, "rw").setLoadFlags(LoadFlags.RAW)) {
            for (int index : plan.chunkIndices()) {
                TerrainChunk chunk = region.read(index);
                if (chunk == null || !isLegacyVoidChunk(chunk.getHandle())) {
                    throw new IOException("Legacy void chunk changed during migration: " + plan.regionFile());
                }
                if (region.removeChunk(index)) removed++;
            }
        }
        return removed;
    }

    private static void waitBeforeRetry(Path regionFile) throws IOException {
        try {
            Thread.sleep(FILE_REPLACE_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to replace legacy void region: " + regionFile,
                    interrupted);
        }
    }

    private static void moveIntoPlace(Path temporaryFile, Path target) throws IOException {
        try {
            Files.move(temporaryFile, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean isLegacyVoidChunk(CompoundTag chunk) {
        return hasOnlyVoidBiomePalettes(chunk)
                && hasOnlyAirBlockStatePalettes(chunk)
                && hasNoSavedContent(chunk);
    }

    private static boolean hasOnlyVoidBiomePalettes(Tag<?> tag) {
        PaletteCheck check = new PaletteCheck();
        visit(tag, (key, value) -> {
            if ("biomes".equals(key) && value instanceof CompoundTag biomes) {
                check.found = true;
                if (!paletteContainsOnly(biomes, VOID_BIOME, false)) check.valid = false;
            }
        });
        return check.found && check.valid;
    }

    private static boolean hasOnlyAirBlockStatePalettes(Tag<?> tag) {
        PaletteCheck check = new PaletteCheck();
        visit(tag, (key, value) -> {
            if ("block_states".equals(key) && value instanceof CompoundTag blocks) {
                if (!paletteContainsOnly(blocks, null, true)) check.valid = false;
            }
        });
        return check.valid;
    }

    private static boolean hasNoSavedContent(CompoundTag chunk) {
        for (String key : new String[] {"block_entities", "entities", "block_ticks", "fluid_ticks"}) {
            Tag<?> value = chunk.get(key);
            if (value instanceof ListTag<?> list && !list.isEmpty()) return false;
        }
        return true;
    }

    private static boolean paletteContainsOnly(CompoundTag container, String exactName, boolean airOnly) {
        Tag<?> paletteTag = container.get("palette");
        if (!(paletteTag instanceof ListTag<?> palette) || palette.isEmpty()) return false;
        for (Tag<?> entry : palette) {
            String name = paletteName(entry);
            if (name == null || (airOnly ? !isAir(name) : !exactName.equals(name))) return false;
        }
        return true;
    }

    private static String paletteName(Tag<?> entry) {
        if (entry instanceof StringTag string) return string.getValue();
        if (entry instanceof CompoundTag compound) {
            StringTag name = compound.getStringTag("Name");
            return name == null ? null : name.getValue();
        }
        return null;
    }

    private static boolean isAir(String block) {
        return "minecraft:air".equals(block)
                || "minecraft:cave_air".equals(block)
                || "minecraft:void_air".equals(block);
    }

    private static void visit(Tag<?> tag, TagVisitor visitor) {
        if (tag instanceof CompoundTag compound) {
            for (var entry : compound.entrySet()) {
                visitor.visit(entry.getKey(), entry.getValue());
                visit(entry.getValue(), visitor);
            }
        } else if (tag instanceof ListTag<?> list) {
            for (Tag<?> child : list) visit(child, visitor);
        }
    }

    private interface TagVisitor {
        void visit(String key, Tag<?> value);
    }

    private static final class PaletteCheck {
        private boolean found;
        private boolean valid = true;
    }
}
