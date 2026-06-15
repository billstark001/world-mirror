package net.billstark001.worldmirror.conflict;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;

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

