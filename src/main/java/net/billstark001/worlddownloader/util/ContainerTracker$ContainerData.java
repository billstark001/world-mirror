package net.billstark001.worlddownloader.util;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.component.ComponentChanges;
















































































































































































@Environment(EnvType.CLIENT)
class ContainerData
{
  final BlockPos pos;
  final Text name;
  final Map<Integer, ItemStack> containerSlots = new ConcurrentHashMap<>();
  int containerSize = 27;
  private ChestType chestType = null;
  private Direction facing = null;
  
  ContainerData(BlockPos pos, Text name) {
    this.pos = pos;
    this.name = name;
  }
  
  void updateSlot(int slot, ItemStack stack) {
    if (!stack.isEmpty()) {
      this.containerSlots.put(Integer.valueOf(slot), stack.copy());
    } else {
      this.containerSlots.remove(Integer.valueOf(slot));
    } 
  }
  
  void setContainerInventory(List<ItemStack> contents, int containerSlotCount) {
    this.containerSize = containerSlotCount;
    this.containerSlots.clear();
    
    for (int i = 0; i < containerSlotCount && i < contents.size(); i++) {
      ItemStack stack = contents.get(i);
      if (!stack.isEmpty()) {
        this.containerSlots.put(Integer.valueOf(i), stack.copy());
      }
    } 
  }
  
  void setChestType(ChestType type, Direction facing) {
    this.chestType = type;
    this.facing = facing;
  }
  
  NbtCompound toNbt() {
    NbtCompound containerNbt = new NbtCompound();

    
    containerNbt.putInt("x", this.pos.getX());
    containerNbt.putInt("y", this.pos.getY());
    containerNbt.putInt("z", this.pos.getZ());

    
    containerNbt.putString("id", "minecraft:chest");

    
    if (this.name != null && !this.name.getString().isEmpty() && !this.name.getString().equals("Chest")) {
      try {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
          String jsonName = this.name.getString();
          containerNbt.putString("CustomName", jsonName);
        } else {
          containerNbt.putString("CustomName", "\"" + this.name.getString() + "\"");
        } 
      } catch (Exception e) {
        containerNbt.putString("CustomName", "\"" + this.name.getString() + "\"");
      } 
    }

    
    NbtList itemsList = new NbtList();
    for (Map.Entry<Integer, ItemStack> entry : this.containerSlots.entrySet()) {
      ItemStack stack = entry.getValue();
      if (stack.isEmpty())
        continue; 
      try {
        NbtCompound itemNbt = serializeItemStack(stack, ((Integer)entry.getKey()).intValue());
        if (itemNbt != null) {
          itemsList.add(itemNbt);
        }
      } catch (Exception e) {
        System.err.println("❌ Failed to serialize item in slot " + String.valueOf(entry.getKey()) + ": " + e.getMessage());
      } 
    } 
    
    containerNbt.put("Items", (NbtElement)itemsList);
    return containerNbt;
  }
  
  private NbtCompound serializeItemStack(ItemStack stack, int slot) {
    MinecraftClient client = MinecraftClient.getInstance();
    if (client.world == null) return null;
    
    try {
      NbtCompound itemNbt = new NbtCompound();

      
      String itemId = Registries.ITEM.getId(stack.getItem()).toString();
      itemNbt.putString("id", itemId);
      itemNbt.putInt("count", stack.getCount());
      itemNbt.putByte("Slot", (byte)slot);

      
      try {
        DataResult<NbtElement> result = ComponentChanges.CODEC.encodeStart((DynamicOps)client.world
            .getRegistryManager().getOps((DynamicOps)NbtOps.INSTANCE), stack
            .getComponentChanges());
        
        if (result.result().isPresent()) {
          NbtElement componentsNbt = result.result().get();
          if (componentsNbt instanceof NbtCompound) { NbtCompound components = (NbtCompound)componentsNbt; if (!components.isEmpty())
              itemNbt.put("components", (NbtElement)components);  }
        
        } 
      } catch (Exception e) {
        System.err.println("⚠ Failed to encode item components for " + itemId + ": " + e.getMessage());
      } 
      
      return itemNbt;
    } catch (Exception e) {
      System.err.println("❌ Item serialization failed: " + e.getMessage());
      
      NbtCompound basicItem = new NbtCompound();
      basicItem.putString("id", Registries.ITEM.getId(stack.getItem()).toString());
      basicItem.putInt("count", stack.getCount());
      basicItem.putByte("Slot", (byte)slot);
      return basicItem;
    } 
  }
}
