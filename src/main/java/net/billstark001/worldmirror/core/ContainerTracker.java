package net.billstark001.worldmirror.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.item.ItemStack;
import net.minecraft.block.ChestBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.HitResult;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.ChestType;
import net.minecraft.state.property.Property;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;

@Environment(EnvType.CLIENT)
public class ContainerTracker {
    private static final Map<Integer, ContainerData> openContainers = new ConcurrentHashMap<>();
    private static final Map<BlockPos, NbtCompound> savedContainerData = new ConcurrentHashMap<>();

    public static void onContainerOpened(int syncId, Text name) {
        MinecraftClient client = MinecraftClient.getInstance();
        HitResult HitResult = client.crosshairTarget;
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
        NbtCompound containerNbt = container.toNbt();
        savedContainerData.put(container.pos, containerNbt);

        int nonEmptySlots = countNonEmptySlots(contents, containerSlots);
        WMLogger.debug("Saved regular container at " + container.pos + " with " + nonEmptySlots + "/" + containerSlots + " filled slots");
    }

    private static void handleDoubleChest(ContainerData container, List<ItemStack> contents) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            handleRegularContainer(container, contents, 54);

            return;
        }
        BlockPos pos = container.pos;
        BlockState blockState = client.world.getBlockState(pos);


        if (!(blockState.getBlock() instanceof ChestBlock)) {
            handleRegularContainer(container, contents, 54);
            return;
        }
        try {
            BlockPos leftChestPos, rightChestPos;
            ChestType chestType = (ChestType) blockState.get((Property) ChestBlock.CHEST_TYPE);
            Direction facing = (Direction) blockState.get((Property) ChestBlock.FACING);


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
            NbtCompound leftChestNbt = leftContainer.toNbt();
            savedContainerData.put(leftChestPos, leftChestNbt);

            ContainerData rightContainer = new ContainerData(rightChestPos, container.name);
            rightContainer.setContainerInventory(rightChestItems, 27);
            rightContainer.setChestType(ChestType.RIGHT, facing);
            NbtCompound rightChestNbt = rightContainer.toNbt();
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
                yield pos.offset(adjacentDirection);
            }
            case SOUTH -> {
                adjacentDirection = isLeft ? Direction.EAST : Direction.WEST;
                yield pos.offset(adjacentDirection);
            }
            case EAST -> {
                adjacentDirection = isLeft ? Direction.NORTH : Direction.SOUTH;
                yield pos.offset(adjacentDirection);
            }
            case WEST -> {
                adjacentDirection = isLeft ? Direction.SOUTH : Direction.NORTH;
                yield pos.offset(adjacentDirection);
            }
            default -> pos.offset(adjacentDirection);
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

    public static NbtCompound getContainerData(BlockPos pos) {
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


    public static NbtCompound enhanceBlockEntityWithContainerData(BlockEntity blockEntity, NbtCompound originalNbt) {
        if (originalNbt == null) {
            return null;
        }
        BlockPos pos = blockEntity.getPos();
        NbtCompound containerData = getContainerData(pos);

        if (containerData != null) {
            if (containerData.contains("Items")) {
                originalNbt.put("Items", containerData.get("Items"));
                NbtList items = (NbtList) containerData.get("Items");
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
