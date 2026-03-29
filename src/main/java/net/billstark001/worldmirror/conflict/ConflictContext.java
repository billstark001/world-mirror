package net.billstark001.worldmirror.conflict;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.nbt.NbtCompound;

import java.nio.file.Path;

/**
 * Data object passed to a {@link ConflictResolver} when a chunk is about to be
 * written to the mirror world.
 *
 * @param pos           Chunk coordinates.
 * @param existsLocally True when a chunk at this position already exists in the on-disk region
 *                      file (i.e. a previously downloaded version is present).
 * @param chunkNbt      The incoming (server-side) chunk NBT data.
 * @param dimension     The dimension this chunk belongs to.
 * @param worldFolder   Root directory of the mirror world.
 */
public record ConflictContext(
        ChunkPos pos,
        boolean existsLocally,
        NbtCompound chunkNbt,
        RegistryKey<World> dimension,
        Path worldFolder
) {
}

