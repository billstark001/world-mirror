package io.github.billstark001.worldmirror.io;

import java.util.HashMap;
import java.util.Map;

import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.util.ContainerNameSupport;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Environment(EnvType.CLIENT)
public final class BlockEntityNbtSupport {
    private static final String BLOCK_ENTITIES_KEY = "block_entities";

    private BlockEntityNbtSupport() {}

    public static CompoundTag serialize(BlockEntity blockEntity, HolderLookup.Provider registryLookup) {
        CompoundTag nbt = blockEntity.saveWithFullMetadata(registryLookup);
        sanitizeBlockEntity(nbt);
        applyContainerOverlay(blockEntity, nbt);
        sanitizeBlockEntity(nbt);
        return nbt;
    }

    public static BlockEntity deserialize(
            BlockPos pos,
            BlockState state,
            CompoundTag nbt,
            HolderLookup.Provider registryLookup) {
        return BlockEntity.loadStatic(pos, state, nbt, registryLookup);
    }

    /**
     * Enriches client-known block entities with older fields the server omitted.
     * Position and type must match before fields are merged. Missing entries are
     * conservatively restored because an absent client payload is not proof of
     * block removal; repeated source positions never create duplicate entries.
     */
    public static void mergeChunkBlockEntities(CompoundTag targetChunk, CompoundTag sourceChunk) {
        ListTag sourceBlockEntities = sourceChunk.getListOrEmpty(BLOCK_ENTITIES_KEY);
        if (sourceBlockEntities.isEmpty()) {
            return;
        }

        ListTag targetBlockEntities = targetChunk.getList(BLOCK_ENTITIES_KEY).orElseGet(() -> {
            ListTag list = new ListTag();
            targetChunk.put(BLOCK_ENTITIES_KEY, list);
            return list;
        });

        Map<BlockPos, CompoundTag> targetByPos = indexBlockEntitiesByPos(targetBlockEntities);
        for (int i = 0; i < sourceBlockEntities.size(); i++) {
            java.util.Optional<CompoundTag> sourceEntry = sourceBlockEntities.getCompound(i);
            if (sourceEntry.isEmpty()) {
                continue;
            }

            CompoundTag sourceBlockEntity = sourceEntry.get();
            BlockPos pos = blockEntityPos(sourceBlockEntity);
            String sourceType = sourceBlockEntity.getStringOr("id", "");
            if (pos == null || sourceType.isBlank()) {
                continue;
            }

            CompoundTag targetBlockEntity = targetByPos.get(pos);
            if (targetBlockEntity == null) {
                CompoundTag restored = sanitizedCopy(sourceBlockEntity);
                targetBlockEntities.add(restored);
                targetByPos.put(pos, restored);
                continue;
            }
            if (!sourceType.equals(targetBlockEntity.getStringOr("id", ""))) continue;

            sanitizeBlockEntity(targetBlockEntity);
            mergePreservedKeys(sourceBlockEntity, targetBlockEntity);
        }

    }

    public static void applyContainerOverlays(ResourceKey<Level> dimension, CompoundTag chunkNbt) {
        applyContainerOverlays(dimension, chunkNbt, null);
    }

    public static void applyContainerOverlays(
            ResourceKey<Level> dimension,
            CompoundTag chunkNbt,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot) {
        ListTag blockEntities = chunkNbt.getListOrEmpty(BLOCK_ENTITIES_KEY);
        if (blockEntities.isEmpty()) {
            return;
        }

        for (int i = 0; i < blockEntities.size(); i++) {
            java.util.Optional<CompoundTag> entry = blockEntities.getCompound(i);
            if (entry.isEmpty()) {
                continue;
            }

            CompoundTag blockEntity = entry.get();
            BlockPos pos = blockEntityPos(blockEntity);
            if (pos == null) {
                continue;
            }

            sanitizeBlockEntity(blockEntity);
            applyContainerOverlay(dimension, pos, blockEntity, containerSnapshot);
            sanitizeBlockEntity(blockEntity);
        }
    }

    public static void applyContainerOverlay(BlockEntity blockEntity, CompoundTag blockEntityNbt) {
        ResourceKey<Level> dimension = blockEntity.getLevel() != null
                ? blockEntity.getLevel().dimension()
                : null;
        applyContainerOverlay(dimension, blockEntity.getBlockPos(), blockEntityNbt);
    }

    private static void applyContainerOverlay(
            ResourceKey<Level> dimension,
            BlockPos pos,
            CompoundTag blockEntityNbt) {
        applyContainerOverlay(dimension, pos, blockEntityNbt, null);
    }

    private static void applyContainerOverlay(
            ResourceKey<Level> dimension,
            BlockPos pos,
            CompoundTag blockEntityNbt,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot) {
        CompoundTag containerData = containerSnapshot != null
                ? containerSnapshot.getOrDefault(dimension, Map.of()).get(pos)
                : ContainerTracker.getContainerData(dimension, pos);
        if (containerData == null) {
            return;
        }
        String containerType = containerData.getStringOr("id", "");
        if (!containerType.isEmpty() && !containerType.equals(blockEntityNbt.getStringOr("id", ""))) return;

        copyTagIfPresent(containerData, blockEntityNbt, "Items");
        copyCustomNameIfPresent(containerData, blockEntityNbt);
    }

    private static void copyTagIfPresent(CompoundTag source, CompoundTag target, String key) {
        Tag value = source.get(key);
        if (value != null) {
            target.put(key, value.copy());
        }
    }

    private static void copyCustomNameIfPresent(CompoundTag source, CompoundTag target) {
        Tag value = source.get("CustomName");
        if (value != null && !isDefaultContainerName(value)) {
            target.put("CustomName", value.copy());
        }
    }

    private static Map<BlockPos, CompoundTag> indexBlockEntitiesByPos(ListTag blockEntities) {
        Map<BlockPos, CompoundTag> result = new HashMap<>();
        for (int i = 0; i < blockEntities.size(); i++) {
            java.util.Optional<CompoundTag> entry = blockEntities.getCompound(i);
            if (entry.isEmpty()) {
                continue;
            }
            CompoundTag blockEntity = entry.get();
            BlockPos pos = blockEntityPos(blockEntity);
            if (pos != null) {
                result.put(pos, blockEntity);
            }
        }
        return result;
    }

    private static BlockPos blockEntityPos(CompoundTag blockEntity) {
        Integer x = blockEntity.getInt("x").orElse(null);
        Integer y = blockEntity.getInt("y").orElse(null);
        Integer z = blockEntity.getInt("z").orElse(null);
        return x == null || y == null || z == null ? null : new BlockPos(x, y, z);
    }

    private static CompoundTag sanitizedCopy(CompoundTag source) {
        CompoundTag copy = source.copy();
        sanitizeBlockEntity(copy);
        return copy;
    }

    private static void sanitizeBlockEntity(CompoundTag blockEntity) {
        Tag customName = blockEntity.get("CustomName");
        if (customName != null && isDefaultContainerName(customName)) {
            blockEntity.remove("CustomName");
        }
    }

    private static void mergePreservedKeys(CompoundTag source, CompoundTag target) {
        for (String key : source.keySet()) {
            Tag value = source.get(key);
            if (value == null) {
                continue;
            }

            if ("CustomName".equals(key)) {
                if (!target.contains(key) && !isDefaultContainerName(value)) {
                    target.put(key, value.copy());
                }
                continue;
            }

            if ("Items".equals(key)) {
                if (hasItems(value) && !hasItems(target.get(key))) {
                    target.put(key, value.copy());
                }
                continue;
            }

            if (!target.contains(key)) {
                target.put(key, value.copy());
            }
        }
    }

    private static boolean hasItems(Tag tag) {
        return tag instanceof ListTag list && !list.isEmpty();
    }

    private static boolean isDefaultContainerName(Tag tag) {
        return ContainerNameSupport.isDefaultChestName(tag);
    }
}
