package net.billstark001.worlddownloader.util;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.item.ItemStack;
import net.minecraft.block.ChestBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.HitResult;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.ChestType;
import net.minecraft.state.property.Property;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.registry.Registries;

@Environment(EnvType.CLIENT)
public class ContainerTracker {
  private static final Map<Integer, ContainerData> openContainers = new ConcurrentHashMap<>();
  private static final Map<BlockPos, NbtCompound> savedContainerData = new ConcurrentHashMap<>();
  
  public static void onContainerOpened(int syncId, Text name) {
    MinecraftClient client = MinecraftClient.getInstance();
    HitResult HitResult = client.crosshairTarget; if (HitResult instanceof BlockHitResult) { BlockHitResult blockHit = (BlockHitResult)HitResult;
      BlockPos pos = blockHit.getBlockPos();
      openContainers.put(Integer.valueOf(syncId), new ContainerData(pos, name));
      System.out.println("📦 Container opened at " + String.valueOf(pos) + ": " + name.getString()); }
  
  }
  
  public static void onSlotUpdate(int syncId, int slot, ItemStack stack) {
    ContainerData container = openContainers.get(Integer.valueOf(syncId));
    if (container != null) {
      container.updateSlot(slot, stack);
    }
  }
  
  public static void onInventoryUpdate(int syncId, List<ItemStack> contents) {
    ContainerData container = openContainers.get(Integer.valueOf(syncId));
    if (container != null) {
      int totalSlots = contents.size();
      int containerSlots = determineContainerSlots(totalSlots);
      
      System.out.println("🔍 Processing container: " + totalSlots + " total slots, " + containerSlots + " container slots");

      
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
    System.out.println("✅ Saved regular container at " + String.valueOf(container.pos) + " with " + nonEmptySlots + "/" + containerSlots + " filled slots");
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
      ChestType chestType = (ChestType)blockState.get((Property)ChestBlock.CHEST_TYPE);
      Direction facing = (Direction)blockState.get((Property)ChestBlock.FACING);

      
      if (chestType == ChestType.field_12569) {
        handleRegularContainer(container, contents, 27);

        
        return;
      } 

      
      if (chestType == ChestType.field_12574) {
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
      leftContainer.setChestType(ChestType.field_12574, facing);
      NbtCompound leftChestNbt = leftContainer.toNbt();
      savedContainerData.put(leftChestPos, leftChestNbt);
      
      ContainerData rightContainer = new ContainerData(rightChestPos, container.name);
      rightContainer.setContainerInventory(rightChestItems, 27);
      rightContainer.setChestType(ChestType.field_12571, facing);
      NbtCompound rightChestNbt = rightContainer.toNbt();
      savedContainerData.put(rightChestPos, rightChestNbt);
      
      int leftNonEmpty = countNonEmptySlots(leftChestItems, 27);
      int rightNonEmpty = countNonEmptySlots(rightChestItems, 27);
      
      System.out.println("✅ Saved double chest:");
      System.out.println("   - Left chest at " + String.valueOf(leftChestPos) + " with " + leftNonEmpty + "/27 items");
      System.out.println("   - Right chest at " + String.valueOf(rightChestPos) + " with " + rightNonEmpty + "/27 items");
    }
    catch (Exception e) {
      System.err.println("❌ Failed to handle double chest, falling back to regular container: " + e.getMessage());
      handleRegularContainer(container, contents, 54);
    } 
  }

  
  private static BlockPos getAdjacentChestPos(BlockPos pos, Direction facing, boolean isLeft) {
    switch (facing)
    
    { 
      
      case field_11043:
        adjacentDirection = isLeft ? Direction.field_11034 : Direction.field_11039;
        
        return pos.method_10093(adjacentDirection);case field_11035: adjacentDirection = isLeft ? Direction.field_11039 : Direction.field_11034; return pos.method_10093(adjacentDirection);case field_11039: adjacentDirection = isLeft ? Direction.field_11043 : Direction.field_11035; return pos.method_10093(adjacentDirection);case field_11034: adjacentDirection = isLeft ? Direction.field_11035 : Direction.field_11043; return pos.method_10093(adjacentDirection); }  Direction adjacentDirection = Direction.field_11034; return pos.method_10093(adjacentDirection);
  }
  
  private static int determineContainerSlots(int totalSlots) {
    switch (totalSlots) { case 63: case 90: case 45: case 41: case 36:  }  return 




      
      Math.max(0, totalSlots - 36);
  }

  
  private static int countNonEmptySlots(List<ItemStack> items, int maxSlots) {
    int count = 0;
    for (int i = 0; i < maxSlots && i < items.size(); i++) {
      if (!((ItemStack)items.get(i)).isEmpty()) {
        count++;
      }
    } 
    return count;
  }
  
  public static void onContainerClosed(int syncId) {
    ContainerData container = openContainers.remove(Integer.valueOf(syncId));
    if (container != null) {
      System.out.println("🚪 Container closed at " + String.valueOf(container.pos));
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
    System.out.println("🗑️ Cleared all container data");
  }
  
  public static int getTotalSavedContainers() {
    return savedContainerData.size();
  }
  
  @Environment(EnvType.CLIENT)
  private static class ContainerData { final BlockPos pos;
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
    } }

  
  public static NbtCompound enhanceBlockEntityWithContainerData(BlockEntity blockEntity, NbtCompound originalNbt) {
    BlockPos pos = blockEntity.getPos();
    NbtCompound containerData = getContainerData(pos);
    
    if (containerData != null) {
      if (containerData.contains("Items")) {
        originalNbt.put("Items", containerData.get("Items"));
        NbtList items = (NbtList)containerData.get("Items");
        System.out.println("✅ Enhanced block entity at " + String.valueOf(pos) + " with " + items.size() + " items");
      } 
      if (containerData.contains("CustomName")) {
        originalNbt.put("CustomName", containerData.get("CustomName"));
      }
    } 
    
    return originalNbt;
  }
}
