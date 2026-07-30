package io.github.billstark001.worldmirror.io;

import java.nio.file.Path;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class WorldSaveLayout {
    private WorldSaveLayout() {}

    static Path dimensionDirectory(Path worldFolder, ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
        return worldFolder.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath());
    }
}
