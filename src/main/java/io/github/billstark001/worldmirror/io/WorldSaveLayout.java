package io.github.billstark001.worldmirror.io;

import java.nio.file.Path;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Native save paths for the selected Minecraft target. */
public final class WorldSaveLayout {
    private WorldSaveLayout() {}

    public static Path dimensionDirectory(Path worldFolder, ResourceKey<Level> dimension) {
#if MC_VERSION < 260102
        Identifier id = dimension.identifier();
        if (id.equals(Level.OVERWORLD.identifier())) {
            return worldFolder;
        }
        if (id.equals(Level.NETHER.identifier())) {
            return worldFolder.resolve("DIM-1");
        }
        if (id.equals(Level.END.identifier())) {
            return worldFolder.resolve("DIM1");
        }
#endif
        return namespacedDimensionDirectory(worldFolder, dimension);
    }

    /** Shared custom-dimension layout; rejects path aliases before touching the filesystem. */
    public static Path namespacedDimensionDirectory(Path worldFolder, ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
        requireDimensionSegment(id.getNamespace());
        for (String segment : id.getPath().split("/", -1)) requireDimensionSegment(segment);
        return worldFolder.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
    }

    private static void requireDimensionSegment(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("Dimension identifier contains an invalid path segment");
        }
    }

    public static Path regionDirectory(Path worldFolder, ResourceKey<Level> dimension) {
        return dimensionDirectory(worldFolder, dimension).resolve("region");
    }
}
