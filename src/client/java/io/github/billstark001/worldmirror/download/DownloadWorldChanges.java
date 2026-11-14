package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.xaero.XaeroWorldIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Tracks remote identity observations and fences capture across respawn boundaries. */
public final class DownloadWorldChanges {
    private static boolean respawnBoundary;
    private static long respawnDeadline;

    private DownloadWorldChanges() {}

    static boolean isRespawnBoundary() {
        return respawnBoundary;
    }

    static void reset() {
        respawnBoundary = false;
    }

    static void tick(Minecraft client) {
        var routing = WorldGraphService.instance().routing();
        if (routing.updateRemoteObservation(client)) {
            DownloadManager.logicalWorldChanged(client);
        }
        var evidence = XaeroWorldIdentity.observe(client);
        if (routing.observeProvider(evidence, DownloadManager.session() != null))
            DownloadManager.logicalWorldChanged(client);
        var session = DownloadManager.session();
        if (session != null && !routing.matchesSessionSource(session.sourceNodeId()))
            DownloadManager.logicalWorldChanged(client);
        if (respawnBoundary && System.nanoTime() - respawnDeadline >= 0L) {
            respawnBoundary = false;
            if (routing.expectsIdentity() && DownloadManager.session() != null) {
                routing.requireSourceChoice();
                DownloadManager.logicalWorldChanged(client);
            } else if (DownloadManager.isCaptureEnabled()) {
                DownloadCapture.queueLoadedScan(client, "respawn-capture");
            }
        }
    }

    public static void onRespawnBoundary(ClientPacketListener connection) {
        var client = Minecraft.getInstance();
        if (connection == client.getConnection() && client.getSingleplayerServer() == null) {
            if (DownloadManager.session() != null && DownloadManager.sessionIsCurrent(client)) {
                DownloadCapture.finishPendingBlockChanges(client);
                DownloadCapture.queue.finishLightUpdates();
                DownloadCapture.queue.clear();
            }
            respawnBoundary = true;
            respawnDeadline = System.nanoTime()
                    + (WorldGraphService.instance().routing().expectsIdentity() ? 2_000_000_000L : 0L);
        }
    }

    public static void observeProvider(ClientPacketListener connection, ProviderObservation evidence) {
        var client = Minecraft.getInstance();
        var graph = WorldGraphService.instance();
        if (connection != client.getConnection())
            return;
        boolean wasFenced = respawnBoundary;
        boolean advertised = evidence.state() == WorldSourceResolver.ProviderState.ADVERTISED;
        boolean routeChanged =
                graph.routing().observeProvider(evidence, DownloadManager.session() != null && !wasFenced);
        if (wasFenced && advertised) {
            respawnBoundary = false;
            if (!routeChanged && DownloadManager.isCaptureEnabled())
                DownloadCapture.queueLoadedScan(client, "respawn-capture");
        }
        if (routeChanged)
            DownloadManager.logicalWorldChanged(client);
    }
}
