package net.billstark001.worlddownloader.mixin;

import net.billstark001.worlddownloader.util.ContainerTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.util.hit.HitResult;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.billstark001.worlddownloader.util.WDLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({ClientPlayNetworkHandler.class})
public class DebugContainerMixin {
    @Inject(method = {"onOpenScreen"}, at = {@At("HEAD")})
    private void debugOnOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        String targetInfo = "unknown";

        HitResult HitResult = client.crosshairTarget;
        if (HitResult instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            if (client.world != null) {
                String blockName = client.world.getBlockState(pos).getBlock().toString();
                targetInfo = blockName + " at " + blockName;
            }
        }


        WDLogger.debug("DEBUG: OpenScreen packet");
        WDLogger.debug("  syncId: " + packet.getSyncId());
        WDLogger.debug("  name: " + packet.getName().getString());
        WDLogger.debug("  target: " + targetInfo);

        ContainerTracker.onContainerOpened(packet.getSyncId(), packet.getName());
    }


    @Inject(method = {"onScreenHandlerSlotUpdate"}, at = {@At("HEAD")})
    private void debugOnSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        String itemInfo = packet.getStack().isEmpty() ? "empty"
                : (packet.getStack().getItem() + " x" + packet.getStack().getCount());

        WDLogger.debug("DEBUG: SlotUpdate - syncId: " + packet.getSyncId()
                + ", slot: " + packet.getSlot() + ", item: " + itemInfo);

        ContainerTracker.onSlotUpdate(packet.getSyncId(), packet.getSlot(), packet.getStack());
    }

    @Inject(method = {"onInventory"}, at = {@At("HEAD")})
    private void debugOnInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        int totalSlots = packet.contents().size();
        int nonEmptySlots = 0;
        int containerSlots = 0;
        int playerSlots = 0;


        switch (totalSlots) {
            case 63:
                containerSlots = 27;
                playerSlots = 36;
                break;
            case 90:
                containerSlots = 54;
                playerSlots = 36;
                break;
            case 45:
                containerSlots = 9;
                playerSlots = 36;
                break;
            case 41:
                containerSlots = 5;
                playerSlots = 36;
                break;
            default:
                containerSlots = Math.max(0, totalSlots - 36);
                playerSlots = 36;
                break;
        }


        int containerNonEmpty = 0;
        int playerNonEmpty = 0;
        int i;
        for (i = 0; i < packet.contents().size(); i++) {
            if (!packet.contents().get(i).isEmpty()) {
                nonEmptySlots++;
                if (i < containerSlots) {
                    containerNonEmpty++;
                } else {
                    playerNonEmpty++;
                }
            }
        }

        WDLogger.debug("DEBUG: Inventory - syncId: " + packet.syncId());
        WDLogger.debug("  Total slots: " + totalSlots);
        WDLogger.debug("  Non-empty slots: " + nonEmptySlots);


        WDLogger.debug("  Container items (first 10):");
        for (i = 0; i < Math.min(10, containerSlots) && i < packet.contents().size(); i++) {
            if (!packet.contents().get(i).isEmpty()) {
                WDLogger.debug("  [" + i + "] "
                        + packet.contents().get(i).getItem()
                        + " x" + packet.contents().get(i).getCount());
            }
        }

        ContainerTracker.onInventoryUpdate(packet.syncId(), packet.contents());
    }

    @Inject(method = {"onCloseScreen"}, at = {@At("HEAD")})
    private void debugOnCloseScreen(CloseScreenS2CPacket packet, CallbackInfo ci) {
        WDLogger.debug("DEBUG: CloseScreen - syncId: " + packet.getSyncId());
        ContainerTracker.onContainerClosed(packet.getSyncId());
    }
}
