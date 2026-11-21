package io.github.billstark001.worldmirror.io;

import java.util.HashMap;
import java.util.Map;

import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.util.ContainerNameSupport;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

@Environment(EnvType.CLIENT)
public final class BlockEntityNbtSupport {
    private static final String BLOCK_ENTITIES_KEY = "block_entities";

    private BlockEntityNbtSupport() {}

    public static CompoundTag serialize(BlockEntity blockEntity, HolderLookup.Provider registryLookup) {
        CompoundTag nbt = blockEntity.saveWithFullMetadata(registryLookup);
        applyContainerOverlay(blockEntity, nbt);
        return nbt;
    }

    public static BlockEntity deserialize(
            BlockPos pos, BlockState state, CompoundTag nbt, HolderLookup.Provider registryLookup) {
        return BlockEntity.loadStatic(pos, state, nbt, registryLookup);
    }

    /** Tests whether a known record cannot be used with this known block state. */
    public static boolean isIncompatible(CompoundTag record, BlockState state) {
        String type = record.getStringOr("id", "");
        if ("DUMMY".equals(type))
            return !record.getBooleanOr("keepPacked", false) || (state != null && !state.hasBlockEntity());
        var id = Identifier.tryParse(type);
        if (type.isEmpty() || id == null)
            return true;
        return state != null && incompatible(blockEntityType(id), state);
    }

    /** Removes records whose coordinates or matching terrain prove they cannot belong to this chunk. */
    public static int removeIncompatible(CompoundTag chunk, ChunkPos owner) {
        ListTag entries = chunk.getListOrEmpty(BLOCK_ENTITIES_KEY);
        if (entries.isEmpty())
            return 0;
        var states = new ChunkBlockEntityStates(chunk);
        int removed = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!(entries.get(i) instanceof CompoundTag record)) {
                entries.remove(i);
                removed++;
                continue;
            }
            if (incompatibleRecord(owner, states, record)) {
                entries.remove(i);
                removed++;
            }
        }
        return removed;
    }

    public static boolean containsIncompatible(CompoundTag chunk, ChunkPos owner) {
        ListTag entries = chunk.getListOrEmpty(BLOCK_ENTITIES_KEY);
        if (entries.isEmpty())
            return false;
        var states = new ChunkBlockEntityStates(chunk);
        for (Tag entry : entries) {
            if (!(entry instanceof CompoundTag record) || incompatibleRecord(owner, states, record))
                return true;
        }
        return false;
    }

    private static boolean incompatibleRecord(
            ChunkPos owner, ChunkBlockEntityStates states, CompoundTag record) {
        BlockPos pos = blockEntityPos(record);
        return !belongsToChunk(owner, pos) || isIncompatible(record, states.at(pos));
    }

    /**
     * Enriches client-known block entities with older fields the server omitted.
     * The saved type must match the captured block. Missing client entries are
     * restored only when the captured block proves they still belong there.
     */
    public static void mergeChunkBlockEntities(
            CompoundTag targetChunk, CompoundTag sourceChunk, ChunkPos pos) {
        mergeChunkBlockEntities(targetChunk, sourceChunk, pos, null, Map.of());
    }

    /** The live cache can distinguish observed empty inventories from omitted client data. */
    public static void mergeChunkBlockEntities(
            CompoundTag targetChunk, CompoundTag sourceChunk, ChunkPos pos, ResourceKey<Level> dimension) {
        mergeChunkBlockEntities(targetChunk, sourceChunk, pos, dimension, null);
    }

    static void mergeChunkBlockEntities(CompoundTag targetChunk, CompoundTag sourceChunk, ChunkPos owner,
            ResourceKey<Level> dimension,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot) {
        ListTag sourceBlockEntities = sourceChunk.getListOrEmpty(BLOCK_ENTITIES_KEY);
        if (sourceBlockEntities.isEmpty())
            return;

        ListTag targetBlockEntities = targetChunk.getListOrEmpty(BLOCK_ENTITIES_KEY);
        Map<BlockPos, CompoundTag> targetByPos = indexBlockEntitiesByPos(targetBlockEntities);
        var states = new ChunkBlockEntityStates(targetChunk);
        var previousStates = new ChunkBlockEntityStates(sourceChunk);
        for (int i = 0; i < sourceBlockEntities.size(); i++) {
            if (!(sourceBlockEntities.get(i) instanceof CompoundTag sourceBlockEntity))
                continue;
            BlockPos pos = blockEntityPos(sourceBlockEntity);
            String sourceType = sourceBlockEntity.getStringOr("id", "");
            if (!belongsToChunk(owner, pos) || sourceType.isBlank())
                continue;

            var state = states.at(pos);
            var previousState = previousStates.at(pos);
            var id = Identifier.tryParse(sourceType);
            var registered = id == null ? null : blockEntityType(id);
            if (id == null || incompatible(registered, state) || incompatible(registered, previousState)
                    || (state != null && previousState != null
                            && state.getBlock() != previousState.getBlock()))
                continue;
            CompoundTag targetBlockEntity = targetByPos.get(pos);
            if (targetBlockEntity == null) {
                // A packed vanilla dummy has no data to merge and can be recreated from the captured block.
                if (registered == null || state == null)
                    continue;
                CompoundTag restored = sanitizedCopy(sourceBlockEntity);
                applyContainerData(restored, overlay(dimension, pos, containerSnapshot), state);
                targetBlockEntities.add(restored);
                targetChunk.put(BLOCK_ENTITIES_KEY, targetBlockEntities);
                targetByPos.put(pos, restored);
                continue;
            }
            if (!sourceType.equals(targetBlockEntity.getStringOr("id", "")))
                continue;

            sanitizeBlockEntity(targetBlockEntity);
            CompoundTag overlay = overlay(dimension, pos, containerSnapshot);
            // Frozen container observations may deliberately replace all Items with an empty list.
            boolean knownItems = acceptsOverlay(targetBlockEntity, overlay, state)
                    && overlay.get("Items") instanceof ListTag;
            mergePreservedKeys(sourceBlockEntity, targetBlockEntity, knownItems);
        }
    }

    private static boolean incompatible(BlockEntityType<?> type, BlockState state) {
        return type != null && state != null && !type.isValid(state);
    }

    private static BlockEntityType<?> blockEntityType(Identifier id) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
    }

    static boolean belongsToChunk(ChunkPos chunk, BlockPos block) {
        return block != null && (block.getX() >> 4) == (chunk.getMinBlockX() >> 4)
                && (block.getZ() >> 4) == (chunk.getMinBlockZ() >> 4);
    }

    public static void applyContainerOverlays(ResourceKey<Level> dimension, CompoundTag chunkNbt) {
        applyContainerOverlays(dimension, chunkNbt, null);
    }

    public static void applyContainerOverlays(ResourceKey<Level> dimension, CompoundTag chunkNbt,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot) {
        ListTag blockEntities = chunkNbt.getListOrEmpty(BLOCK_ENTITIES_KEY);
        if (blockEntities.isEmpty()) {
            return;
        }

        var states = new ChunkBlockEntityStates(chunkNbt);
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

            CompoundTag overlay = overlay(dimension, pos, containerSnapshot);
            applyCapturedContainerData(blockEntity, overlay,
                    overlay != null && overlay.contains(ContainerTracker.BLOCK_ID) ? states.at(pos) : null);
        }
    }

    public static void applyContainerOverlay(BlockEntity blockEntity, CompoundTag blockEntityNbt) {
        ResourceKey<Level> dimension =
                blockEntity.getLevel() != null ? blockEntity.getLevel().dimension() : null;
        applyCapturedContainerData(blockEntityNbt, overlay(dimension, blockEntity.getBlockPos(), null),
                blockEntity.getBlockState());
    }

    static void applyCapturedContainerData(
            ResourceKey<Level> dimension, BlockPos pos, CompoundTag record, BlockState state) {
        applyCapturedContainerData(record, overlay(dimension, pos, null), state);
    }

    private static void applyCapturedContainerData(
            CompoundTag record, CompoundTag overlay, BlockState state) {
        sanitizeBlockEntity(record);
        applyContainerData(record, overlay, state);
    }

    private static CompoundTag overlay(ResourceKey<Level> dimension, BlockPos pos,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot) {
        if (dimension == null)
            return null;
        return containerSnapshot != null ? containerSnapshot.getOrDefault(dimension, Map.of()).get(pos)
                                         : ContainerTracker.getContainerData(dimension, pos);
    }

    private static boolean acceptsOverlay(
            CompoundTag blockEntityNbt, CompoundTag containerData, BlockState state) {
        if (containerData == null)
            return false;
        String containerType = containerData.getStringOr("id", "");
        if (!containerType.isEmpty() && !containerType.equals(blockEntityNbt.getStringOr("id", "")))
            return false;
        if (!containerData.contains(ContainerTracker.BLOCK_ID))
            return true;
        return state != null
                && BuiltInRegistries.BLOCK.getKey(state.getBlock())
                           .toString()
                           .equals(containerData.getStringOr(ContainerTracker.BLOCK_ID, ""));
    }

    private static void applyContainerData(
            CompoundTag blockEntityNbt, CompoundTag containerData, BlockState state) {
        if (!acceptsOverlay(blockEntityNbt, containerData, state))
            return;

        if (containerData.get("Items") instanceof ListTag) {
            copyTagIfPresent(containerData, blockEntityNbt, "Items");
            blockEntityNbt.remove("LootTable");
            blockEntityNbt.remove("LootTableSeed");
        }
        copyCustomNameIfPresent(containerData, blockEntityNbt);
    }

    private static void copyTagIfPresent(CompoundTag source, CompoundTag target, String key) {
        Tag value = source.get(key);
        if (value != null && !value.equals(target.get(key))) {
            target.put(key, value.copy());
        }
    }

    private static void copyCustomNameIfPresent(CompoundTag source, CompoundTag target) {
        Tag value = source.get("CustomName");
        if (value != null && !ContainerNameSupport.isDefaultChestName(value)) {
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

    static BlockPos blockEntityPos(CompoundTag blockEntity) {
        if (blockEntity.get("x") instanceof IntTag x && blockEntity.get("y") instanceof IntTag y
                && blockEntity.get("z") instanceof IntTag z)
            return new BlockPos(x.value(), y.value(), z.value());
        return null;
    }

    private static CompoundTag sanitizedCopy(CompoundTag source) {
        CompoundTag copy = source.copy();
        sanitizeBlockEntity(copy);
        return copy;
    }

    private static void sanitizeBlockEntity(CompoundTag blockEntity) {
        Tag customName = blockEntity.get("CustomName");
        if (customName != null && ContainerNameSupport.isDefaultChestName(customName)) {
            blockEntity.remove("CustomName");
        }
    }

    private static void mergePreservedKeys(CompoundTag source, CompoundTag target, boolean knownItems) {
        mergeInventory(source, target, knownItems);
        for (String key : source.keySet()) {
            Tag value = source.get(key);
            if (value == null) {
                continue;
            }

            if ("CustomName".equals(key)) {
                if (!target.contains(key) && !ContainerNameSupport.isDefaultChestName(value)) {
                    target.put(key, value.copy());
                }
                continue;
            }

            if ("Items".equals(key) || "LootTable".equals(key) || "LootTableSeed".equals(key))
                continue;

            if (!target.contains(key)) {
                target.put(key, value.copy());
            }
        }
    }

    private static void mergeInventory(CompoundTag source, CompoundTag target, boolean knownItems) {
        if (knownItems || hasItems(target.get("Items")))
            return;
        Tag lootTable = target.get("LootTable");
        Tag previousTable = source.get("LootTable");
        if (lootTable != null) {
            if (lootTable.equals(previousTable) && !target.contains("LootTableSeed"))
                copyTagIfPresent(source, target, "LootTableSeed");
        } else if (previousTable != null) {
            copyTagIfPresent(source, target, "LootTable");
            copyTagIfPresent(source, target, "LootTableSeed");
        } else if (hasItems(source.get("Items"))) {
            copyTagIfPresent(source, target, "Items");
        }
    }

    private static boolean hasItems(Tag tag) {
        return tag instanceof ListTag list && !list.isEmpty();
    }
}
