package io.github.billstark001.worldmirror.io;

import java.nio.file.Path;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class WorldSaveLayout {
    private WorldSaveLayout() {}

    static Path dimensionDirectory(Path worldFolder, ResourceKey<Level> dimension) {
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
        return worldFolder.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath());
    }
}
