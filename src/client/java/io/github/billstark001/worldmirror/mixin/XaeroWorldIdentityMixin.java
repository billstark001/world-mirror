package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.DownloadWorldChanges;
import io.github.billstark001.worldmirror.xaero.XaeroWorldIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 1100)
abstract class XaeroWorldIdentityMixin {
    @Shadow @Final protected Connection connection;

    @Inject(method =
                    "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"), order = 900)
    private void
    worldmirror$observeXaeroPayload(ClientboundCustomPayloadPacket packet, CallbackInfo callback) {
        Object handler = this;
        if (handler instanceof ClientConfigurationPacketListenerImpl) {
            // Fabric's configuration receiver runs on Netty and completes this negotiation before joining PLAY.
            XaeroWorldIdentity.observeConfiguration(connection, packet.payload());
            return;
        }
        // Fabric dispatches and may cancel this method at HEAD. Observe before that receiver, only on its
        // client packet-processor invocation. A Netty invocation must not mutate the capture route.
        if (!(handler instanceof ClientPacketListener listener)
                || !Minecraft.getInstance().packetProcessor().isSameThread()) {
            return;
        }
        var observation = XaeroWorldIdentity.readPayload(packet.payload());
        if (observation != null) {
            XaeroWorldIdentity.remember(listener, observation);
            DownloadWorldChanges.observeProvider(listener, observation);
        }
    }
}
