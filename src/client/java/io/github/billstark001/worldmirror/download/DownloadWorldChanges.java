package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.xaero.XaeroWorldIdentity;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;

/** Tracks remote identity observations and fences capture across respawn boundaries. */
public final class DownloadWorldChanges {
    private static final RemoteWorldBoundary BOUNDARY = new RemoteWorldBoundary();
    private static Reconfiguration reconfiguration;

    private record Reconfiguration(Connection connection, boolean wasActive) {}

    private DownloadWorldChanges() {}

    static boolean isRespawnBoundary() {
        return BOUNDARY.blocksCapture();
    }

    static boolean needsConfirmation() {
        return BOUNDARY.needsConfirmation();
    }

    static boolean awaitingIdentity() {
        return BOUNDARY.needsIdentity();
    }

    static void reset() {
        BOUNDARY.reset();
        RemoteChunkProvenance.reset();
    }

    static Boolean takeReconfiguration(Minecraft client) {
        var previous = reconfiguration;
        reconfiguration = null;
        return previous != null && client.getConnection() != null
                        && previous.connection() == client.getConnection().getConnection()
                ? previous.wasActive()
                : null;
    }

    static void tick(Minecraft client) {
        var routing = WorldGraphService.instance().routing();
        if (routing.updateRemoteObservation(client)) {
            BOUNDARY.reset();
            RemoteChunkProvenance.restrict(client.level);
            DownloadManager.logicalWorldChanged(client);
        }
        if (!isRespawnBoundary())
            updateProvider(client, routing, XaeroWorldIdentity.observe(client));
        var session = DownloadManager.session();
        if (session != null && !isRespawnBoundary()
                && !routing.matchesSessionSource(session.sourceNodeId())) {
            RemoteChunkProvenance.restrict(client.level);
            DownloadManager.logicalWorldChanged(client);
        }
        boolean wasAwaitingChoice = needsConfirmation();
        if (BOUNDARY.advance(System.nanoTime()) && DownloadManager.isCaptureEnabled())
            DownloadCapture.queueLoadedScan(client, "respawn-capture");
        if (!wasAwaitingChoice && needsConfirmation() && hasCaptureIntent())
            WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable(routing.strictlyRequiresIdentity()
                                    ? "screen.worldmirror.captureRoute.waiting"
                                    : "screen.worldmirror.captureRoute.selectionRequired"));
    }

    static boolean hasCaptureIntent() {
        return DownloadManager.isCaptureEnabled() || DownloadRoute.isDownloadPreparing();
    }

    private static void updateProvider(
            Minecraft client, WorldConnectionRouting routing, ProviderObservation evidence) {
        String previous = routing.currentResolution().sourceNodeId();
        if (routing.observeProvider(evidence, DownloadManager.session() != null)) {
            if (previous != null)
                RemoteChunkProvenance.restrict(client.level);
            DownloadManager.logicalWorldChanged(client);
        }
    }

    public static void onRespawnBoundary(ClientPacketListener connection) {
        var client = Minecraft.getInstance();
        var routing = WorldGraphService.instance().routing();
        if (connection == client.getConnection() && client.getSingleplayerServer() == null
                && routing.observation() != null && routing.observation().endpoint() != null) {
            if (DownloadManager.session() != null && DownloadManager.sessionIsCurrent(client)) {
                DownloadCapture.finishPendingBlockChanges(client);
                DownloadCapture.queue.finishLightUpdates();
                DownloadCapture.queue.clear();
            }
            boolean needsFreshIdentity = routing.expectsIdentity();
            var session = DownloadManager.session();
            String previous =
                    session == null ? routing.currentResolution().sourceNodeId() : session.sourceNodeId();
            routing.enterRemoteBoundary(needsFreshIdentity);
            BOUNDARY.enter(
                    routing.observation().worldEpoch(), System.nanoTime(), needsFreshIdentity, previous);
            if (needsFreshIdentity)
                XaeroWorldIdentity.resetPlayEvidence(connection);
        }
    }

    public static void beforeRemoteReconfiguration(ClientPacketListener connection) {
        var client = Minecraft.getInstance();
        if (connection != client.getConnection() || client.getSingleplayerServer() != null)
            return;
        reconfiguration = new Reconfiguration(connection.getConnection(), DownloadManager.isCaptureEnabled());
        XaeroWorldIdentity.resetNegotiation(connection);
        if (DownloadManager.sessionIsCurrent(client))
            DownloadCapture.finishPendingBlockChanges(client);
        DownloadManager.endCaptureSession(!isRespawnBoundary());
        DownloadRoute.cancelRouteRequest();
        DownloadRoute.clearMirrorSelection();
    }

    static void confirmSource(Minecraft client, long worldEpoch, String selected) {
        String previous = BOUNDARY.previousSource();
        if (BOUNDARY.confirm(worldEpoch, WorldGraphService.instance().routing().strictlyRequiresIdentity())
                && previous != null && !previous.equals(selected))
            RemoteChunkProvenance.restrict(client.level);
    }

    static void resumeCapture(Minecraft client) {
        if (DownloadManager.isCaptureEnabled())
            DownloadCapture.queueLoadedScan(client, "respawn-capture");
    }

    public static void observeProvider(ClientPacketListener connection, ProviderObservation evidence) {
        var client = Minecraft.getInstance();
        var graph = WorldGraphService.instance();
        if (connection != client.getConnection())
            return;
        var routing = graph.routing();
        if (!isRespawnBoundary()) {
            updateProvider(client, routing, evidence);
            return;
        }
        long epoch = BOUNDARY.epoch();
        var previous = DownloadManager.session();
        String source = BOUNDARY.previousSource();
        if (!routing.observeAtBoundary(routing.connectionEpoch(), epoch, evidence, source)
                || !BOUNDARY.attest(epoch, evidence))
            return;
        boolean sessionChanged = previous != null && !routing.matchesSessionSource(previous.sourceNodeId());
        if (sessionChanged || source != null && !routing.matchesSessionSource(source))
            RemoteChunkProvenance.restrict(client.level);
        if (sessionChanged)
            DownloadManager.logicalWorldChanged(client);
        else if (DownloadManager.isCaptureEnabled())
            DownloadCapture.queueLoadedScan(client, "respawn-capture");
    }
}
