package io.github.billstark001.worldmirror.io;

import java.nio.file.Path;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class WorldSaveLayout {
    private WorldSaveLayout() {}

    static Path dimensionDirectory(Path worldFolder, ResourceKey<Level> dimension) {
        return ChunkExporter.namespacedDimensionDirectory(worldFolder, dimension);
    }
}
