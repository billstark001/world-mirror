package net.billstark001.worldmirror.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.billstark001.worldmirror.core.ContainerTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({ClientPacketListener.class})
public class ContainerMixin {
    @Inject(method = {"handleOpenScreen"}, at = {@At("TAIL")})
    private void onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        ContainerTracker.onContainerOpened(packet.getContainerId(), packet.getTitle());
    }


    @Inject(method = {"handleContainerSetSlot"}, at = {@At("TAIL")})
    private void onSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        ContainerTracker.onSlotUpdate(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }


    @Inject(method = {"handleContainerContent"}, at = {@At("TAIL")})
    private void onInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        ContainerTracker.onInventoryUpdate(packet.containerId(), packet.items());
    }


    @Inject(method = {"handleContainerClose"}, at = {@At("TAIL")})
    private void onCloseScreen(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        ContainerTracker.onContainerClosed(packet.getContainerId());
    }
}
