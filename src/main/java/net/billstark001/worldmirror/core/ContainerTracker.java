package net.billstark001.worldmirror.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

@Environment(EnvType.CLIENT)
public class ContainerTracker {
    private static final Map<Integer, ContainerData> openContainers = new ConcurrentHashMap<>();
    private static final Map<BlockPos, CompoundTag> savedContainerData = new ConcurrentHashMap<>();

    public static void onContainerOpened(int syncId, Component name) {
        Minecraft client = Minecraft.getInstance();
        HitResult HitResult = client.hitResult;
        if (HitResult instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            openContainers.put(syncId, new ContainerData(pos, name));
            WMLogger.debug("Container opened at " + pos + ": " + name.getString());
        }

    }

    public static void onSlotUpdate(int syncId, int slot, ItemStack stack) {
        ContainerData container = openContainers.get(syncId);
        if (container != null) {
            container.updateSlot(slot, stack);
        }
    }

    public static void onInventoryUpdate(int syncId, List<ItemStack> contents) {
        ContainerData container = openContainers.get(syncId);
        if (container != null) {
            int totalSlots = contents.size();
            int containerSlots = determineContainerSlots(totalSlots);

            WMLogger.debug("Processing container: " + totalSlots + " total slots, " + containerSlots + " container slots");


            if (containerSlots == 54) {
                handleDoubleChest(container, contents);
            } else {

                handleRegularContainer(container, contents, containerSlots);
            }
        }
    }

    private static void handleRegularContainer(ContainerData container, List<ItemStack> contents, int containerSlots) {
        container.setContainerInventory(contents, containerSlots);
        CompoundTag containerNbt = container.toNbt();
        savedContainerData.put(container.pos, containerNbt);

        int nonEmptySlots = countNonEmptySlots(contents, containerSlots);
        WMLogger.debug("Saved regular container at " + container.pos + " with " + nonEmptySlots + "/" + containerSlots + " filled slots");
    }

    private static void handleDoubleChest(ContainerData container, List<ItemStack> contents) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            handleRegularContainer(container, contents, 54);

            return;
        }
        BlockPos pos = container.pos;
        BlockState blockState = client.level.getBlockState(pos);


        if (!(blockState.getBlock() instanceof ChestBlock)) {
            handleRegularContainer(container, contents, 54);
            return;
        }
        try {
            BlockPos leftChestPos, rightChestPos;
            ChestType chestType = (ChestType) blockState.getValue((Property) ChestBlock.TYPE);
            Direction facing = (Direction) blockState.getValue((Property) ChestBlock.FACING);


            if (chestType == ChestType.SINGLE) {
                handleRegularContainer(container, contents, 27);


                return;
            }


            if (chestType == ChestType.LEFT) {
                leftChestPos = pos;
                rightChestPos = getAdjacentChestPos(pos, facing, true);
            } else {
                rightChestPos = pos;
                leftChestPos = getAdjacentChestPos(pos, facing, false);
            }


            List<ItemStack> leftChestItems = contents.subList(0, Math.min(27, contents.size()));
            List<ItemStack> rightChestItems = contents.subList(Math.min(27, contents.size()), Math.min(54, contents.size()));


            ContainerData leftContainer = new ContainerData(leftChestPos, container.name);
            leftContainer.setContainerInventory(leftChestItems, 27);
            leftContainer.setChestType(ChestType.LEFT, facing);
            CompoundTag leftChestNbt = leftContainer.toNbt();
            savedContainerData.put(leftChestPos, leftChestNbt);

            ContainerData rightContainer = new ContainerData(rightChestPos, container.name);
            rightContainer.setContainerInventory(rightChestItems, 27);
            rightContainer.setChestType(ChestType.RIGHT, facing);
            CompoundTag rightChestNbt = rightContainer.toNbt();
            savedContainerData.put(rightChestPos, rightChestNbt);

            int leftNonEmpty = countNonEmptySlots(leftChestItems, 27);
            int rightNonEmpty = countNonEmptySlots(rightChestItems, 27);

            WMLogger.debug("Saved double chest:");
            WMLogger.debug("  Left chest at " + leftChestPos + " with " + leftNonEmpty + "/27 items");
            WMLogger.debug("  Right chest at " + rightChestPos + " with " + rightNonEmpty + "/27 items");
        } catch (Exception e) {
            WMLogger.warn("Failed to handle double chest, falling back: " + e.getMessage());
            handleRegularContainer(container, contents, 54);
        }
    }


    private static BlockPos getAdjacentChestPos(BlockPos pos, Direction facing, boolean isLeft) {
        Direction adjacentDirection = Direction.WEST;
        return switch (facing) {
            case NORTH -> {
                adjacentDirection = isLeft ? Direction.WEST : Direction.EAST;
                yield pos.relative(adjacentDirection);
            }
            case SOUTH -> {
                adjacentDirection = isLeft ? Direction.EAST : Direction.WEST;
                yield pos.relative(adjacentDirection);
            }
            case EAST -> {
                adjacentDirection = isLeft ? Direction.NORTH : Direction.SOUTH;
                yield pos.relative(adjacentDirection);
            }
            case WEST -> {
                adjacentDirection = isLeft ? Direction.SOUTH : Direction.NORTH;
                yield pos.relative(adjacentDirection);
            }
            default -> pos.relative(adjacentDirection);
        };
    }

    private static int determineContainerSlots(int totalSlots) {
        switch (totalSlots) {
            case 63:
            case 90:
            case 45:
            case 41:
            case 36:
        }
        return


                Math.max(0, totalSlots - 36);
    }


    private static int countNonEmptySlots(List<ItemStack> items, int maxSlots) {
        int count = 0;
        for (int i = 0; i < maxSlots && i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public static void onContainerClosed(int syncId) {
        ContainerData container = openContainers.remove(syncId);
        if (container != null) {
            WMLogger.debug("Container closed at " + container.pos);
        }
    }

    public static CompoundTag getContainerData(BlockPos pos) {
        return savedContainerData.get(pos);
    }

    public static boolean hasContainerData(BlockPos pos) {
        return savedContainerData.containsKey(pos);
    }

    public static void clear() {
        openContainers.clear();
        savedContainerData.clear();
        WMLogger.info("Cleared all container data");
    }

    public static int getTotalSavedContainers() {
        return savedContainerData.size();
    }


    public static CompoundTag enhanceBlockEntityWithContainerData(BlockEntity blockEntity, CompoundTag originalNbt) {
        if (originalNbt == null) {
            return null;
        }
        BlockPos pos = blockEntity.getBlockPos();
        CompoundTag containerData = getContainerData(pos);

        if (containerData != null) {
            if (containerData.contains("Items")) {
                originalNbt.put("Items", containerData.get("Items"));
                ListTag items = (ListTag) containerData.get("Items");
                if (items != null) {
                    WMLogger.debug("Enhanced block entity at " + pos + " with " + items.size() + " items");
                } else {
                    WMLogger.debug("Enhanced block entity at " + pos + " with empty item list");
                }
            }
            if (containerData.contains("CustomName")) {
                originalNbt.put("CustomName", containerData.get("CustomName"));
            }
        }

        return originalNbt;
    }
}
