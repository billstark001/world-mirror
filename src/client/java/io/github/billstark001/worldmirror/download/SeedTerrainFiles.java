package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.SeedConversion.Progress;
import io.github.billstark001.worldmirror.download.SeedTerrain.Region;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.io.WorldSaveLayout;
import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SeedTerrainFiles {
    private SeedTerrainFiles() {}

    static boolean hasAuxiliary(Path root, Path entities, AnvilReader.Reader entityData, Path poi,
            AnvilReader.Reader poiData, ChunkPos pos) throws IOException {
        if (entityData.positions().contains(pos)) {
            FileProof.requirePlainPath(root, external(entities, pos));
            var entry = entityData.read(pos);
            if (entry == null || SeedTerrainBiomes.nonemptyList(entry, "Entities")
                    || SeedTerrainBiomes.nonemptyList(entry, "entities")
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
                if (!(sections.get(key) instanceof CompoundTag section)
                        || SeedTerrainBiomes.nonemptyList(section, "Records"))
                    return true;
            }
        }
        return false;
    }

    static Path sibling(Region region, String kind) {
        return region.file().getParent().resolveSibling(kind).resolve(region.file().getFileName());
    }

    static Path external(Path region, ChunkPos pos) {
        return region.getParent().resolve("c." + chunkX(pos) + "." + chunkZ(pos) + ".mcc");
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

    static List<Region> regions(Path root, Path directory, ResourceKey<Level> dimension, Progress progress)
            throws IOException {
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

    static int chunkX(ChunkPos pos) {
        return pos.getRegionX() * 32 + pos.getRegionLocalX();
    }
    static int chunkZ(ChunkPos pos) {
        return pos.getRegionZ() * 32 + pos.getRegionLocalZ();
    }
}
