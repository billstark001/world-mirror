package net.billstark001.worldmirror.core;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public class ContainerData {
    final BlockPos pos;
    final Component name;
    final Map<Integer, ItemStack> containerSlots = new ConcurrentHashMap<>();
    int containerSize = 27;
    private ChestType chestType = null;
    private Direction facing = null;

    ContainerData(BlockPos pos, Component name) {
        this.pos = pos;
        this.name = name;
    }

    void updateSlot(int slot, ItemStack stack) {
        if (!stack.isEmpty()) {
            this.containerSlots.put(slot, stack.copy());
        } else {
            this.containerSlots.remove(slot);
        }
    }

    void setContainerInventory(List<ItemStack> contents, int containerSlotCount) {
        this.containerSize = containerSlotCount;
        this.containerSlots.clear();

        for (int i = 0; i < containerSlotCount && i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (!stack.isEmpty()) {
                this.containerSlots.put(i, stack.copy());
            }
        }
    }

    void setChestType(ChestType type, Direction facing) {
        this.chestType = type;
        this.facing = facing;
    }

    CompoundTag toNbt() {
        CompoundTag containerNbt = getContainerNbtCompound();

        ListTag itemsList = new ListTag();
        for (Map.Entry<Integer, ItemStack> entry : this.containerSlots.entrySet()) {
            ItemStack stack = entry.getValue();
            if (stack.isEmpty())
                continue;
            try {
                CompoundTag itemNbt = serializeItemStack(stack, entry.getKey());
                if (itemNbt != null) {
                    itemsList.add(itemNbt);
                }
            } catch (Exception e) {
                WMLogger.warn("Failed to serialize item in slot " + entry.getKey() + ": " + e.getMessage());
            }
        }

        containerNbt.put("Items", itemsList);
        return containerNbt;
    }

    private @NonNull CompoundTag getContainerNbtCompound() {
        CompoundTag containerNbt = new CompoundTag();


        containerNbt.putInt("x", this.pos.getX());
        containerNbt.putInt("y", this.pos.getY());
        containerNbt.putInt("z", this.pos.getZ());


        containerNbt.putString("id", "minecraft:chest");


        if (this.name != null && !this.name.getString().isEmpty() && !this.name.getString().equals("Chest")) {
            try {
                Minecraft client = Minecraft.getInstance();
                if (client.level != null) {
                    String jsonName = this.name.getString();
                    containerNbt.putString("CustomName", jsonName);
                } else {
                    containerNbt.putString("CustomName", "\"" + this.name.getString() + "\"");
                }
            } catch (Exception e) {
                containerNbt.putString("CustomName", "\"" + this.name.getString() + "\"");
            }
        }
        return containerNbt;
    }

    private CompoundTag serializeItemStack(ItemStack stack, int slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;

        try {
            CompoundTag itemNbt = new CompoundTag();


            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            itemNbt.putString("id", itemId);
            itemNbt.putInt("count", stack.getCount());
            itemNbt.putByte("Slot", (byte) slot);


            try {
                test1(client, itemNbt, stack);
            } catch (Exception e) {
                WMLogger.warn("Failed to encode item components for " + itemId + ": " + e.getMessage());
            }

            return itemNbt;
        } catch (Exception e) {
            WMLogger.warn("Item serialization failed: " + e.getMessage());

            CompoundTag basicItem = new CompoundTag();
            basicItem.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            basicItem.putInt("count", stack.getCount());
            basicItem.putByte("Slot", (byte) slot);
            return basicItem;
        }
    }

    static void test1(Minecraft client, CompoundTag itemNbt, ItemStack stack) {
        DataResult<Tag> result = DataComponentPatch.CODEC.encodeStart(
                client.level.registryAccess().createSerializationContext((DynamicOps) NbtOps.INSTANCE),
                stack.getComponentsPatch()
        );

        if (result.result().isPresent()) {
            Tag componentsNbt = result.result().get();
            if (componentsNbt instanceof CompoundTag components) {
                if (!components.isEmpty())
                    itemNbt.put("components", components);
            }

        }
    }
}
