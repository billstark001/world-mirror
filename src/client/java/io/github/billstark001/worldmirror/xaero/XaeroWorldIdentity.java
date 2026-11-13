package io.github.billstark001.worldmirror.xaero;

import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Observes Xaero's raw level-properties packet without registering or replacing its receiver. */
public final class XaeroWorldIdentity {
    public static final String PROVIDER = "xaero";
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("xaeroworldmap");
    private static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("xaeroworldmap", "main");
    private static final String WRAPPER_CLASS = "xaero.lib.common.packet.payload.PacketPayload";
    private static final String LEVEL_PROPERTIES_CLASS = "xaero.map.server.level.LevelMapProperties";
    private static final Map<Connection, Boolean> PLAY_CHANNELS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ClientPacketListener, ProviderObservation> PLAY_OBSERVATIONS =
            new WeakHashMap<>();

    private XaeroWorldIdentity() {}

    public static ProviderObservation observe(Minecraft client) {
        if (!INSTALLED) {
            return ProviderObservation.absent();
        }
        var listener = client.getConnection();
        ProviderObservation last = PLAY_OBSERVATIONS.get(listener);
        if (last != null) {
            return last;
        }
        // An unadvertised channel at JOIN can still arrive during PLAY. Only a complete registration is evidence.
        if (listener != null && Boolean.FALSE.equals(PLAY_CHANNELS.get(listener.getConnection()))
                && !ClientPlayNetworking.canSend(CHANNEL)) {
            return ProviderObservation.absent();
        }
        return ProviderObservation.pending();
    }

    public static void remember(ClientPacketListener listener, ProviderObservation observation) {
        // Both packet dispatch and polling are on the client thread. The listener also changes on reconfiguration.
        PLAY_OBSERVATIONS.put(listener, observation);
    }

    public static void observeConfiguration(Connection connection, CustomPacketPayload payload) {
        if (!INSTALLED || !(payload instanceof CommonRegisterPayload registration)) {
            return;
        }
#if MC_VERSION < 260102
        String protocol = registration.phase();
#else
        String protocol = registration.protocol();
#endif
        if ("play".equals(protocol)) {
            PLAY_CHANNELS.put(connection, registration.channels().contains(CHANNEL));
        }
    }

    /** Returns null for other Xaero messages, including the handshake. */
    public static ProviderObservation readPayload(CustomPacketPayload payload) {
        if (!WRAPPER_CLASS.equals(payload.getClass().getName()) || !CHANNEL.equals(payload.type().id())) {
            return null;
        }
        try {
            return readLevelProperties(payload);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            WMLogger.warnRateLimited(
                    "xaero-level-id", 30_000L, "Cannot read Xaero's advertised level ID", error);
            return ProviderObservation.conflict();
        }
    }

    private static ProviderObservation readLevelProperties(Object payload)
            throws ReflectiveOperationException {
        Object packet = payload.getClass().getMethod("getPacket").invoke(payload);
        if (packet == null || !LEVEL_PROPERTIES_CLASS.equals(packet.getClass().getName())) {
            return null;
        }
        Method getId = packet.getClass().getMethod("getId");
        int id = (int) getId.invoke(packet);
        return ProviderObservation.advertised(PROVIDER, Integer.toString(id));
    }
}
