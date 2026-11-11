package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.util.Collection;

/** Optional, observational progress. A broken observer must never stop a native write. */
@FunctionalInterface
public interface ExportProgress {
    enum Part { TERRAIN, ENTITIES }
    ExportProgress NONE = (part, dimension, chunks) -> { };
    void writing(Part part, ResourceKey<Level> dimension, Collection<ChunkPos> chunks);

    default void start(Part part, ResourceKey<Level> dimension, Collection<ChunkPos> chunks) {
        try { writing(part, dimension, chunks); }
        catch (RuntimeException failure) {
            WMLogger.warnRateLimited("export-progress-observer", 30_000,
                    "Export progress observer failed; continuing native write", failure);
        }
    }
}
