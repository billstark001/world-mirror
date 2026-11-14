package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.SeedConversion.Progress;
import io.github.billstark001.worldmirror.download.SeedTerrain.Region;
import io.github.billstark001.worldmirror.io.AnvilReader;
import io.github.billstark001.worldmirror.util.FileProof;
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

final class SeedTerrainFiles {
    private SeedTerrainFiles() {}

    static Path sibling(Region region, String kind) {
        return region.file().getParent().resolveSibling(kind).resolve(region.file().getFileName());
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

    static int chunkX(ChunkPos pos) {
        return pos.getRegionX() * 32 + pos.getRegionLocalX();
    }
    static int chunkZ(ChunkPos pos) {
        return pos.getRegionZ() * 32 + pos.getRegionLocalZ();
    }
}
