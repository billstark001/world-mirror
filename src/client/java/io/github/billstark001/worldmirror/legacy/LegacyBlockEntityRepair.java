package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.io.BlockEntityNbtSupport;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Removes obsolete block-entity records in older World Mirror captures during native load. */
public final class LegacyBlockEntityRepair {
    private LegacyBlockEntityRepair() {}

    /** Keeps the verified source capture intact until it is explicitly applied. */
    public static CompoundTag sanitizedSavedCapture(CompoundTag chunk, ChunkPos owner) {
        if (!(chunk.get("DataVersion") instanceof IntTag version) || version.value() <= 0
                || version.value() > SharedConstants.getCurrentVersion().dataVersion().version()
                || !(chunk.get("xPos") instanceof IntTag x)
                || x.value() != owner.getRegionX() * ChunkPos.REGION_SIZE + owner.getRegionLocalX()
                || !(chunk.get("zPos") instanceof IntTag z)
                || z.value() != owner.getRegionZ() * ChunkPos.REGION_SIZE + owner.getRegionLocalZ()
                || !BlockEntityNbtSupport.containsIncompatible(chunk, owner))
            return chunk;
        var copy = chunk.copy();
        BlockEntityNbtSupport.removeIncompatible(copy, owner);
        return copy;
    }

    /** Retain vanilla's list when all records still belong to their blocks. */
    public static List<CompoundTag> filter(LevelChunk chunk, List<CompoundTag> stored) {
        var owner = chunk.getPos();
        var height = chunk.getHeightAccessorForGeneration();
        return filter(stored, tag -> {
            if (!(tag.get("x") instanceof IntTag x) || !(tag.get("y") instanceof IntTag y)
                    || !(tag.get("z") instanceof IntTag z))
                return true;
            var pos = new BlockPos(x.intValue(), y.intValue(), z.intValue());
            if ((pos.getX() >> 4) != (owner.getMinBlockX() >> 4)
                    || (pos.getZ() >> 4) != (owner.getMinBlockZ() >> 4) || height.isOutsideBuildHeight(pos))
                return true;
            return BlockEntityNbtSupport.isIncompatible(tag, chunk.getBlockState(pos));
        });
    }

    private static List<CompoundTag> filter(List<CompoundTag> stored, Predicate<CompoundTag> obsolete) {
        List<CompoundTag> retained = null;
        for (int i = 0; i < stored.size(); i++) {
            var tag = stored.get(i);
            if (obsolete.test(tag)) {
                if (retained == null)
                    retained = new ArrayList<>(stored.subList(0, i));
            } else if (retained != null) {
                retained.add(tag);
            }
        }
        return retained == null ? stored : retained;
    }
}
