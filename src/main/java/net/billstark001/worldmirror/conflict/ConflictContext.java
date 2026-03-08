package net.billstark001.worldmirror.conflict;

import net.minecraft.util.math.ChunkPos;

/**
 * Data object passed to a {@link ConflictResolver} when a chunk is about to be
 * written to the mirror world.
 *
 * @param pos           Chunk coordinates.
 * @param existsLocally True when a chunk at this position already exists in the on-disk region
 *                      file (i.e. a previously downloaded version is present).
 */
public record ConflictContext(ChunkPos pos, boolean existsLocally) {

}
