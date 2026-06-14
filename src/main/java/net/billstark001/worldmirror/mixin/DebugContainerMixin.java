package net.billstark001.worldmirror.mixin;

import net.billstark001.worldmirror.core.ContainerTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.billstark001.worldmirror.util.WMLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({ClientPacketListener.class})
public class DebugContainerMixin {
    @Inject(method = {"handleOpenScreen"}, at = {@At("HEAD")})
    private void debugOnOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        String targetInfo = "unknown";

        HitResult HitResult = client.hitResult;
        if (HitResult instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            if (client.level != null) {
                String blockName = client.level.getBlockState(pos).getBlock().toString();
                targetInfo = blockName + " at " + blockName;
            }
        }


        WMLogger.debug("DEBUG: OpenScreen packet");
        WMLogger.debug("  syncId: " + packet.getContainerId());
        WMLogger.debug("  name: " + packet.getTitle().getString());
        WMLogger.debug("  target: " + targetInfo);

        ContainerTracker.onContainerOpened(packet.getContainerId(), packet.getTitle());
    }


    @Inject(method = {"handleContainerSetSlot"}, at = {@At("HEAD")})
    private void debugOnSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        String itemInfo = packet.getItem().isEmpty() ? "empty"
                : (packet.getItem().getItem() + " x" + packet.getItem().getCount());

        WMLogger.debug("DEBUG: SlotUpdate - syncId: " + packet.getContainerId()
                + ", slot: " + packet.getSlot() + ", item: " + itemInfo);

        ContainerTracker.onSlotUpdate(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = {"handleContainerContent"}, at = {@At("HEAD")})
    private void debugOnInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        int totalSlots = packet.items().size();
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
        for (i = 0; i < packet.items().size(); i++) {
            if (!packet.items().get(i).isEmpty()) {
                nonEmptySlots++;
                if (i < containerSlots) {
                    containerNonEmpty++;
                } else {
                    playerNonEmpty++;
                }
            }
        }

        WMLogger.debug("DEBUG: Inventory - syncId: " + packet.containerId());
        WMLogger.debug("  Total slots: " + totalSlots);
        WMLogger.debug("  Non-empty slots: " + nonEmptySlots);


        WMLogger.debug("  Container items (first 10):");
        for (i = 0; i < Math.min(10, containerSlots) && i < packet.items().size(); i++) {
            if (!packet.items().get(i).isEmpty()) {
                WMLogger.debug("  [" + i + "] "
                        + packet.items().get(i).getItem()
                        + " x" + packet.items().get(i).getCount());
            }
        }

        ContainerTracker.onInventoryUpdate(packet.containerId(), packet.items());
    }

    @Inject(method = {"handleContainerClose"}, at = {@At("HEAD")})
    private void debugOnCloseScreen(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        WMLogger.debug("DEBUG: CloseScreen - syncId: " + packet.getContainerId());
        ContainerTracker.onContainerClosed(packet.getContainerId());
    }
}
