package io.github.billstark001.worldmirror.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.DataResult;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.ContainerNameSupport;
import io.github.billstark001.worldmirror.download.DownloadManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

@Environment(EnvType.CLIENT)
public final class ContainerTracker {
    private record ContainerKey(ResourceKey<Level> dimension, BlockPos pos) { }

    private record OpenContainer(
            ClientLevel world,
            BlockPos pos,
            String blockEntityType,
            Component name,
            Map<Integer, ItemStack> slots,
            int containerSize
    ) {
        OpenContainer withSlots(Map<Integer, ItemStack> slots, int size) {
            return new OpenContainer(world, pos, blockEntityType, name, new ConcurrentHashMap<>(slots), size);
        }
    }

    private record DoubleChestPositions(BlockPos first, BlockPos second) { }

    private static final Map<Integer, OpenContainer> openContainers = new ConcurrentHashMap<>();
    private static final Map<ContainerKey, CompoundTag> savedContainerData = new ConcurrentHashMap<>();

    private ContainerTracker() { }

    /** Binds inventory packets to the exact client world and block-entity type opened by the player. */
    public static void onContainerOpened(int syncId, Component name) {
        openContainers.remove(syncId);
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        HitResult hitResult = client.hitResult;
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockEntity blockEntity = client.level.getBlockEntity(pos);
            // Ender-chest contents belong to the player, not to this world block.
            if (blockEntity == null || blockEntity instanceof EnderChestBlockEntity) return;
            openContainers.put(syncId, new OpenContainer(client.level, pos.immutable(),
                    typeId(blockEntity), name == null ? null : name.copy(), new ConcurrentHashMap<>(), 0));
            WMLogger.debug("Container opened position=" + pos + " syncId=" + syncId);
        }
    }

    public static void onSlotUpdate(int syncId, int slot, ItemStack stack) {
        OpenContainer container = currentContainer(syncId);
        if (container == null || slot < 0) {
            return;
        }
        if (container.containerSize() <= 0 || slot >= container.containerSize()) {
            return;
        }

        if (stack.isEmpty()) {
            container.slots().remove(slot);
        } else {
            container.slots().put(slot, stack.copy());
        }

        if (container.containerSize() == 54
                && saveDoubleChestOverlay(container, container.slots())) {
            return;
        }
        saveOverlay(container, container.pos(), container.slots());
    }

    public static void onInventoryUpdate(int syncId, List<ItemStack> contents) {
        OpenContainer container = currentContainer(syncId);
        if (container == null) {
            return;
        }

        int totalSlots = contents.size();
        int containerSlots = determineContainerSlots(totalSlots);

        if (containerSlots == 54) {
            handleDoubleChest(syncId, container, contents);
        } else {
            handleRegularContainer(syncId, container, contents, containerSlots);
        }
    }

    private static void handleRegularContainer(
            int syncId,
            OpenContainer container,
            List<ItemStack> contents,
            int containerSlots) {
        Map<Integer, ItemStack> slots = copyContainerSlots(contents, containerSlots);
        openContainers.replace(syncId, container, container.withSlots(slots, containerSlots));
        saveOverlay(container, container.pos(), slots);

        int nonEmptySlots = slots.size();
        WMLogger.debug("Saved regular container at " + container.pos()
                + " with " + nonEmptySlots + "/" + containerSlots + " filled slots");
    }

    private static void handleDoubleChest(int syncId, OpenContainer container, List<ItemStack> contents) {
        BlockPos pos = container.pos();
        BlockState blockState = container.world().getBlockState(pos);
        if (!(blockState.getBlock() instanceof ChestBlock)) {
            handleRegularContainer(syncId, container, contents, 54);
            return;
        }

        try {
            ChestType chestType = blockState.getValue(ChestBlock.TYPE);
            if (chestType == ChestType.SINGLE) {
                handleRegularContainer(syncId, container, contents, 27);
                return;
            }

            DoubleChestPositions positions = doubleChestPositions(pos, blockState, chestType);

            Map<Integer, ItemStack> firstSlots = copyContainerSlots(
                    contents.subList(0, Math.min(27, contents.size())), 27);
            Map<Integer, ItemStack> secondSlots = copyContainerSlots(
                    contents.subList(Math.min(27, contents.size()), Math.min(54, contents.size())), 27);

            saveOverlay(container, positions.first(), firstSlots);
            saveOverlay(container, positions.second(), secondSlots);

            Map<Integer, ItemStack> combinedSlots = copyContainerSlots(contents, 54);
            openContainers.replace(syncId, container, container.withSlots(combinedSlots, 54));

            WMLogger.debug("Saved double chest at " + positions.first() + "/"
                    + positions.second() + " with " + firstSlots.size() + "+"
                    + secondSlots.size() + " filled slots");
        } catch (Exception e) {
            WMLogger.warnRateLimited("container-double-chest", 30_000L,
                    "Double-chest capture failed; falling back to a single container", e);
            handleRegularContainer(syncId, container, contents, 54);
        }
    }

    private static Map<Integer, ItemStack> copyContainerSlots(List<ItemStack> contents, int containerSlots) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (int i = 0; i < containerSlots && i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (!stack.isEmpty()) {
                result.put(i, stack.copy());
            }
        }
        return result;
    }

    private static boolean saveDoubleChestOverlay(
            OpenContainer container,
            Map<Integer, ItemStack> combinedSlots) {
        BlockPos pos = container.pos();
        BlockState blockState = container.world().getBlockState(pos);
        if (!(blockState.getBlock() instanceof ChestBlock)) {
            return false;
        }

        ChestType chestType = blockState.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) {
            return false;
        }

        DoubleChestPositions positions = doubleChestPositions(pos, blockState, chestType);
        saveOverlay(container, positions.first(), copySlotRange(combinedSlots, 0, 27));
        saveOverlay(container, positions.second(), copySlotRange(combinedSlots, 27, 27));
        return true;
    }

    private static DoubleChestPositions doubleChestPositions(
            BlockPos pos,
            BlockState blockState,
            ChestType chestType) {
        BlockPos connectedPos = ChestBlock.getConnectedBlockPos(pos, blockState);
        return chestType == ChestType.RIGHT
                ? new DoubleChestPositions(pos, connectedPos)
                : new DoubleChestPositions(connectedPos, pos);
    }

    private static Map<Integer, ItemStack> copySlotRange(
            Map<Integer, ItemStack> slots,
            int startSlot,
            int slotCount) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            int slot = entry.getKey();
            if (slot < startSlot || slot >= startSlot + slotCount) {
                continue;
            }

            ItemStack stack = entry.getValue();
            if (!stack.isEmpty()) {
                result.put(slot - startSlot, stack.copy());
            }
        }
        return result;
    }

    private static void saveOverlay(
            OpenContainer container,
            BlockPos pos,
            Map<Integer, ItemStack> slots) {
        BlockEntity blockEntity = container.world().getBlockEntity(pos);
        if (blockEntity == null || !container.blockEntityType().equals(typeId(blockEntity))) return;
        ResourceKey<Level> dimension = container.world().dimension();
        CompoundTag overlay = new CompoundTag();
        overlay.putString("id", container.blockEntityType());
        overlay.put("Items", serializeItems(container.world(), slots));
        ContainerNameSupport.encodeCustomName(container.name()).ifPresent(customName -> overlay.put("CustomName", customName));
        rememberOverlay(dimension, pos, overlay);
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.level.dimension().equals(dimension)) {
            DownloadManager.queueChunkCapture(client.level,
                    new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), "container-update");
        }
    }

    private static ListTag serializeItems(ClientLevel world, Map<Integer, ItemStack> slots) {
        ListTag items = new ListTag();
        var ops = world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            ItemStack stack = entry.getValue();
            if (stack.isEmpty()) {
                continue;
            }

            DataResult<Tag> result = ItemStack.CODEC.encodeStart(ops, stack);
            result.resultOrPartial(error -> WMLogger.warnRateLimited(
                            "container-item-encode", 30_000L,
                            "Failed to encode a container item stack: " + error))
                    .ifPresent(tag -> {
                        if (tag instanceof CompoundTag itemNbt) {
                            itemNbt.putByte("Slot", entry.getKey().byteValue());
                            items.add(itemNbt);
                        }
                    });
        }
        return items;
    }

    private static int determineContainerSlots(int totalSlots) {
        return Math.max(0, totalSlots - 36);
    }

    public static void onContainerClosed(int syncId) {
        OpenContainer container = openContainers.remove(syncId);
        if (container != null) {
            WMLogger.debug("Container closed at " + container.pos());
        }
    }

    /** Returns a caller-owned copy so inventory overlays cannot be changed outside this tracker. */
    public static CompoundTag getContainerData(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) return null;
        CompoundTag data = savedContainerData.get(new ContainerKey(dimension, pos));
        return data == null ? null : data.copy();
    }

    public static Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> snapshotSavedData() {
        Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> snapshot = new HashMap<>();
        for (Map.Entry<ContainerKey, CompoundTag> entry : savedContainerData.entrySet()) {
            ContainerKey key = entry.getKey();
            snapshot.computeIfAbsent(key.dimension(), ignored -> new HashMap<>())
                    .put(key.pos(), entry.getValue().copy());
        }
        return snapshot;
    }

    public static boolean hasContainerData(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) return false;
        return savedContainerData.containsKey(new ContainerKey(dimension, pos));
    }

    public static void clear() {
        openContainers.clear();
        savedContainerData.clear();
        WMLogger.debug("Cleared all container data");
    }

    public static int getTotalSavedContainers() {
        return savedContainerData.size();
    }

    /** Takes ownership of a freshly serialized overlay on the game thread. */
    static void rememberOverlay(ResourceKey<Level> dimension, BlockPos pos, CompoundTag overlay) {
        savedContainerData.put(new ContainerKey(dimension, pos.immutable()), overlay);
        ChunkListener.markChunkDirty(dimension, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public static void evictForChunks(Map<ResourceKey<Level>, ? extends java.util.Collection<ChunkPos>> evictedByDim) {
        evictForChunks(evictedByDim, null);
    }

    /** Export cleanup removes only the overlay version actually included in that export. */
    public static void evictForChunks(
            Map<ResourceKey<Level>, ? extends java.util.Collection<ChunkPos>> evictedByDim,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> exported) {
        if (evictedByDim == null || evictedByDim.isEmpty()) return;

        Map<ResourceKey<Level>, java.util.Set<ChunkPos>> normalized = new java.util.HashMap<>();
        for (Map.Entry<ResourceKey<Level>, ? extends java.util.Collection<ChunkPos>> entry : evictedByDim.entrySet()) {
            java.util.Collection<ChunkPos> chunks = entry.getValue();
            if (chunks == null || chunks.isEmpty()) continue;
            normalized.put(entry.getKey(), new java.util.HashSet<>(chunks));
        }
        if (normalized.isEmpty()) return;

        int removed = 0;
        for (var entry : savedContainerData.entrySet()) {
            ContainerKey key = entry.getKey();
            java.util.Set<ChunkPos> chunkSet = normalized.get(key.dimension());
            if (chunkSet == null || !chunkSet.contains(new ChunkPos(key.pos().getX() >> 4, key.pos().getZ() >> 4))) continue;
            CompoundTag expected = exported == null ? entry.getValue()
                    : exported.getOrDefault(key.dimension(), Map.of()).get(key.pos());
            if (entry.getValue().equals(expected) && savedContainerData.remove(key, entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            WMLogger.debug("Evicted " + removed + " container entries across "
                    + normalized.size() + " dimension(s).");
        }
    }

    private static String typeId(BlockEntity blockEntity) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
    }

    private static OpenContainer currentContainer(int syncId) {
        OpenContainer container = openContainers.get(syncId);
        if (container == null) return null;
        if (Minecraft.getInstance().level == container.world()) {
            BlockEntity blockEntity = container.world().getBlockEntity(container.pos());
            if (blockEntity != null && container.blockEntityType().equals(typeId(blockEntity))) return container;
        }
        openContainers.remove(syncId, container);
        return null;
    }
}
