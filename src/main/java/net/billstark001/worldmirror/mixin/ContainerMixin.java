package net.billstark001.worldmirror.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.billstark001.worldmirror.core.ContainerTracker;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({ClientPlayNetworkHandler.class})
public class ContainerMixin {
    @Inject(method = {"onOpenScreen"}, at = {@At("TAIL")})
    private void onOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        ContainerTracker.onContainerOpened(packet.getSyncId(), packet.getName());
    }


    @Inject(method = {"onScreenHandlerSlotUpdate"}, at = {@At("TAIL")})
    private void onSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        ContainerTracker.onSlotUpdate(packet.getSyncId(), packet.getSlot(), packet.getStack());
    }


    @Inject(method = {"onInventory"}, at = {@At("TAIL")})
    private void onInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        ContainerTracker.onInventoryUpdate(packet.syncId(), packet.contents());
    }


    @Inject(method = {"onCloseScreen"}, at = {@At("TAIL")})
    private void onCloseScreen(CloseScreenS2CPacket packet, CallbackInfo ci) {
        ContainerTracker.onContainerClosed(packet.getSyncId());
    }
}
