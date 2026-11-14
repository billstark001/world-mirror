package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.DownloadWorldChanges;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ConnectionRoutingMixin {
    @Inject(method = "handleRespawn",
            at = @At(value = "INVOKE",
                    target =
                            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER))
    private void
    worldmirror$beforeRespawn(ClientboundRespawnPacket packet, CallbackInfo callback) {
        DownloadWorldChanges.onRespawnBoundary((ClientPacketListener) (Object) this);
    }
}
