package net.billstark001.worlddownloader.conflict;

import net.minecraft.util.math.ChunkPos;

/**
 * Data object passed to a {@link ConflictResolver} when a chunk is about to be
 * written to the mirror world.
 */
public class ConflictContext {

    /** Chunk coordinates. */
    public final ChunkPos pos;

    /**
     * True when a chunk at this position already exists in the on-disk region
     * file (i.e. a previously downloaded version is present).
     */
    public final boolean existsLocally;

    public ConflictContext(ChunkPos pos, boolean existsLocally) {
        this.pos = pos;
        this.existsLocally = existsLocally;
    }
}
