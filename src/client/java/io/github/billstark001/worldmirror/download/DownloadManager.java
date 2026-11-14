package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * Central controller for the download lifecycle.
 *
 * <ul>
 *   <li>Tracks whether downloading is currently active.</li>
 *   <li>Drives the periodic sync tick (called from the client tick event).</li>
 *   <li>Runs the actual export on a background thread to avoid freezing the game.</li>
 *   <li>Exposes helpers for a one-shot manual export and for cache clearing.</li>
 * </ul>
 */
public final class DownloadManager {
    private DownloadManager() {}

    private static volatile boolean captureEnabled;
    private static volatile long downloadSessionStartedAtMs;
    private static long lastCacheEvictionMs = 0;
    private static final long CACHE_EVICTION_INTERVAL_MS = 5_000L;
    private static boolean mirrorCaptureWarningShown;
    static final ExportScheduler exportScheduler = new ExportScheduler();
    static final DownloadExportCoordinator exportCoordinator = new DownloadExportCoordinator(
            DownloadCapture.queue, exportScheduler, DownloadExportCoordinator.createExportExecutor(),
            work -> DownloadExportCoordinator.createJob(work, exportScheduler.mode()));
    /**
     * The dimension registry key observed on the previous client tick.
     * Used to detect dimension changes (e.g. Overworld → Nether).
     */
    private static volatile ResourceKey<Level> lastDimension = null;

    /** Target and conflict policy frozen for the current capture session. */
    private static volatile CaptureSession captureSession;
    private static volatile long sourceEpoch;

    public static boolean isCaptureEnabled() {
        return captureEnabled;
    }

    /** Enabled capture can be temporarily reduced or paused without changing its toggle. */
    public static String captureStatusKey() {
        if (DownloadRoute.awaitingSource()
                || (DownloadWorldChanges.hasCaptureIntent() && DownloadWorldChanges.awaitingIdentity()))
            return DownloadPreparationPrompts.sourceStatusKey();
        if (!captureEnabled) {
            if (DownloadRoute.hasDownloadPreparationReview())
                return "screen.worldmirror.status.downloadNeedsReview";
            return DownloadRoute.isDownloadPreparing() ? "screen.worldmirror.status.downloadPreparing"
                                                       : "screen.worldmirror.status.downloadInactive";
        }
        return switch (CaptureResources.mode()) {
            case NORMAL -> "screen.worldmirror.status.downloadActive";
            case REDUCED -> "screen.worldmirror.status.downloadDegraded";
            case PAUSED -> "screen.worldmirror.status.downloadPaused";
        };
    }

    /** Start time of the latest capture session, used to distinguish green current data from blue history. */
    public static long downloadSessionStartedAtMs() {
        return downloadSessionStartedAtMs;
    }

    public static boolean isExportInProgress() {
        return exportCoordinator.isInProgress();
    }

    public static boolean hasManualExport() {
        return DownloadRoute.isManualExportChecking() || exportCoordinator.hasManualExport();
    }

    /**
     * Toggles downloading on/off.
     * When enabling, all currently loaded chunks in the active dimension are
     * queued for capture across subsequent ticks so the player need not reload them.
     */
    public static void toggle(Minecraft client) {
        if (!WorldGraphService.instance().routing().observationIsCurrent(client))
            return;
        if (captureEnabled) {
            captureEnabled = false;
            if (sessionIsCurrent(client, captureSession))
                DownloadCapture.finishPendingBlockChanges(client);
            DownloadCapture.clearPendingCaptureState(!DownloadWorldChanges.isRespawnBoundary());
            finalizeCaptureOnStop(client, "manual-toggle");
            WMPlayerMessages.sendOverlayMessage(
                    client.player, Component.translatable("msg.worldmirror.downloadStop"));
            WMLogger.info("Download stopped reason=manual-toggle");
            return;
        }
        if (DownloadRoute.isDownloadPreparing() || DownloadRoute.hasDownloadPreparationReview()) {
            DownloadRoute.cancelRouteRequest();
            WMLogger.info("Download preparation cancelled reason=manual-toggle");
            WMPlayerMessages.sendOverlayMessage(
                    client.player, Component.translatable("msg.worldmirror.downloadStop"));
            return;
        }
        DownloadRoute.requestDownloadStart(client, "manual-toggle");
    }

    /**
     * Performs an immediate one-shot export regardless of the toggle state.
     */
    public static void exportNow(Minecraft client) {
        if (DownloadRoute.isDownloadPreparing() || DownloadRoute.hasDownloadPreparationReview()
                || DownloadRoute.isManualExportChecking() || client.level == null
                || !WorldGraphService.instance().routing().observationIsCurrent(client))
            return;
        if (noManualExportWork(client)) {
            WMPlayerMessages.sendSystemMessage(
                    client.player, Component.translatable("msg.worldmirror.noChunks"));
            return;
        }
        DownloadRoute.requestRoute(client, () -> exportNowReady(client), true);
    }

    private static boolean noManualExportWork(Minecraft client) {
        if (ModConfig.get().lifecycle.captureNearbyBeforeExport && client.level != null
                && client.player != null)
            return false;
        return ChunkListener.isEmpty() && !EntityTracker.hasDirtyUpdates()
                && (captureSession == null || !exportCoordinator.hasEntityWork());
    }

    private static void exportNowReady(Minecraft client) {
        CaptureResources.updateResourceState(client, System.nanoTime() / 1_000_000L);
        if (noManualExportWork(client)) {
            Component msg = Component.translatable("msg.worldmirror.noChunks");
            WMPlayerMessages.sendSystemMessage(client.player, msg);
            WMLogger.debug("Manual export ignored because no chunks are cached.");
            return;
        }
        var request = exportRequest(ExportWork.Trigger.MANUAL, true);
        if (exportCoordinator.isInProgress()) {
            WMPlayerMessages.sendSystemMessage(
                    client.player, Component.translatable("msg.worldmirror.exportBusy"));
            request = request.withContainers(ContainerTracker.snapshotSavedData());
        }
        exportCoordinator.request(request);
        dispatchExports(client, CaptureResources.currentBudget(), System.nanoTime() / 1_000_000L);
    }

    // ── Lifecycle event handlers ──────────────────────────────────────────────

    /**
     * Called when the player joins (or re-joins) a world / server.
     * Resets lifecycle tracking state and applies the configured
     * {@link ModConfig.LifecycleConfig#onJoinWorld} behaviour.
     */
    public static void onJoinWorld(Minecraft client) {
        downloadSessionStartedAtMs = 0L;
        Boolean continued = DownloadWorldChanges.takeReconfiguration(client);
        boolean wasActive = continued == null ? captureEnabled : continued;
        captureEnabled = false;
        freezeStoppedSource(false);
        resetRoute();
        DownloadCapture.clearPendingCaptureState(false);
        DownloadCapture.clearCapturedWorldState();
        mirrorCaptureWarningShown = false;
        var lifecycle = ModConfig.get().lifecycle;
        var behavior = continued == null ? lifecycle.onJoinWorld : lifecycle.onServerWorldChange;
        if (behavior == ModConfig.TransitionBehavior.START
                || (wasActive && behavior == ModConfig.TransitionBehavior.KEEP)) {
            DownloadRoute.requestDownloadStart(
                    client, continued == null ? "join-world" : "server-world-change");
        }

        // Reset tracking so subsequent change-detection starts fresh.
        lastDimension = (client.level != null) ? client.level.dimension() : null;
    }

    /**
     * Called when the player disconnects from a world / server.
     * Resets lifecycle tracking state so the next join starts clean.
     * If download was active, runs stop-time finalisation (optional capture/export)
     * before clearing lifecycle tracking state.
     */
    public static void onLeaveWorld(Minecraft client) {
        if (captureEnabled) {
            captureEnabled = false;
            if (sessionIsCurrent(client, captureSession)) {
                DownloadCapture.finishPendingBlockChanges(client);
                DownloadCapture.clearPendingCaptureState(true);
                finalizeCaptureOnStop(client, "leave-world");
            } else {
                freezeStoppedSource(false);
                DownloadCapture.clearPendingCaptureState(false);
            }
        } else {
            DownloadCapture.clearPendingCaptureState(true);
        }
        lastDimension = null;
        resetRoute();
        captureEnabled = false; // no world to download — always stop
        ContainerTracker.clear();
        WMLogger.debug("Left world; download deactivated.");
    }

    /** Freeze configured stop-time work before closing the export worker to new requests. */
    public static void shutdown(Minecraft client) {
        onLeaveWorld(client);
        exportCoordinator.shutdown();
    }

    /**
     * Should be called on every client tick.
     * Triggers a periodic background sync when the configured interval elapses,
     * applies cache-eviction rules, and detects dimension / server-world changes.
     */
    public static void onClientTick(Minecraft client) {
        ExportNotifications.global().tick(client, System.nanoTime() / 1_000_000L);
        OperationFailures.tick(client, System.currentTimeMillis());
        long startedNs = System.nanoTime();
        DownloadPerformanceDiagnostics.beginTick();
        try {
            processClientTick(client);
        } finally {
            DownloadPerformanceDiagnostics.endTick(startedNs);
        }
    }

    private static void processClientTick(Minecraft client) {
        exportCoordinator.drainCompletions(captureSession);
        if (client.level == null) {
            dispatchExports(client, CaptureResources.currentBudget(), System.nanoTime() / 1_000_000L);
            return;
        }

        ResourceKey<Level> currentDim = client.level.dimension();
        DownloadWorldChanges.tick(client);
        DownloadRoute.continuePendingRoute(client);
        // ── Detect dimension change (Overworld ↔ Nether ↔ End, etc.) ──────────
        if (!DownloadWorldChanges.isRespawnBoundary() && lastDimension != null
                && !lastDimension.equals(currentDim)) {
            WMLogger.debug("Dimension change detected: '" + lastDimension.identifier() + "' → '"
                    + currentDim.identifier() + "'");
            lastDimension = currentDim;
            DownloadCapture.clearPendingCaptureState(true);
            applyTransition(client, ModConfig.get().lifecycle.onDimensionChange, "dimension-change");
            // Re-capture loaded chunks according to the new active state
            if (captureEnabled)
                DownloadCapture.queueLoadedScan(client, "initial-capture");
        }

        // Update tracking even if player was in null world on the previous tick
        if (lastDimension == null)
            lastDimension = currentDim;

        long now = System.nanoTime() / 1_000_000L;
        if (captureEnabled || DownloadCapture.queue.hasWork() || exportCoordinator.isInProgress())
            CaptureResources.updateResourceState(client, now);
        CaptureBudget budget = CaptureResources.currentBudget();
        if (sessionIsCurrent(client, captureSession))
            DownloadCapture.queue.tick(client, budget);
        dispatchExports(client, budget, now);
        if (!captureEnabled)
            return;

        if (now - lastCacheEvictionMs >= CACHE_EVICTION_INTERVAL_MS) {
            lastCacheEvictionMs = now;
            DownloadCapture.applyCacheEviction(client);
        }
        DownloadPerformanceDiagnostics.maybeLogPerformanceSnapshot(System.currentTimeMillis());
    }

    /**
     * Applies a {@link ModConfig.TransitionBehavior} to the download state.
     * Sends an appropriate HUD message when the state actually changes.
     *
     * @param eventName human-readable event name used only for logging
     */
    private static void applyTransition(
            Minecraft client, ModConfig.TransitionBehavior behavior, String eventName) {
        boolean wasActive = captureEnabled;
        if (behavior == ModConfig.TransitionBehavior.STOP
                && (DownloadRoute.isDownloadPreparing() || DownloadRoute.hasDownloadPreparationReview())) {
            DownloadRoute.cancelRouteRequest();
            WMPlayerMessages.sendOverlayMessage(
                    client.player, Component.translatable("msg.worldmirror.downloadStop"));
        }
        boolean desired;
        switch (behavior) {
            case START -> desired = true;
            case STOP -> desired = false;
            default -> {
                return;
            } // KEEP — do nothing
        }
        if (wasActive == desired)
            return; // already in the right state

        if (desired) {
            DownloadRoute.requestDownloadStart(client, eventName);
            return;
        }

        captureEnabled = false;
        DownloadCapture.finishPendingBlockChanges(client);
        DownloadCapture.clearPendingCaptureState(true);
        finalizeCaptureOnStop(client, eventName);

        Component msg = Component.translatable("msg.worldmirror.downloadStop");
        WMPlayerMessages.sendOverlayMessage(client.player, msg);
        WMLogger.debug("Download deactivated lifecycleEvent=" + eventName);
    }

    public static WorldGraphService.Target liveCaptureTarget() {
        var current = captureSession;
        return current == null ? null : current.target();
    }

    static boolean hasRoutingWork() {
        // A source choice can wait for user configuration, but a resolved disk check or export owns its route.
        return captureEnabled && !DownloadWorldChanges.isRespawnBoundary() || DownloadRoute.isCheckingTarget()
                || exportCoordinator.hasQueuedWork();
    }

    static void logicalWorldChanged(Minecraft client) {
        lastDimension = client.level == null ? null : client.level.dimension();
        DownloadRoute.clearMirrorSelection();
        if (captureSession == null && DownloadRoute.canRetainStartOnSourceChange())
            return; // first provider response; keep any pending join action
        boolean active = captureEnabled;
        boolean starting = DownloadRoute.hasStartInProgress();
        WMLogger.info("Download route changed; ending previous session active=" + active
                + " preparing=" + starting);
        endCaptureSession(false);
        DownloadRoute.cancelRouteRequest();
        var behavior = ModConfig.get().lifecycle.onServerWorldChange;
        if (behavior == ModConfig.TransitionBehavior.START || (starting && !active)
                || (active && behavior == ModConfig.TransitionBehavior.KEEP)) {
            DownloadRoute.requestDownloadStart(client, "server-world-change");
        } else if (active) {
            WMPlayerMessages.sendOverlayMessage(
                    client.player, Component.translatable("msg.worldmirror.downloadStop"));
        }
    }

    static void endCaptureSession(boolean finishLighting) {
        captureEnabled = false;
        if (finishLighting)
            DownloadCapture.queue.finishLightUpdates();
        freezeStoppedSource(true);
        captureSession = null;
        sourceEpoch++;
        DownloadCapture.clearPendingCaptureState(false);
        DownloadCapture.clearCapturedWorldState();
    }

    private static void resetRoute() {
        captureSession = null;
        DownloadRoute.cancelRouteRequest();
        DownloadRoute.clearMirrorSelection();
        DownloadWorldChanges.reset();
        sourceEpoch++;
    }

    private static boolean sessionIsCurrent(Minecraft client, CaptureSession session) {
        if (session == null || DownloadWorldChanges.isRespawnBoundary()
                || session.sourceEpoch() != sourceEpoch)
            return false;
        var routing = WorldGraphService.instance().routing();
        return session.connectionEpoch() == routing.connectionEpoch() && routing.observationIsCurrent(client)
                && routing.matchesSessionSource(session.sourceNodeId());
    }

    static CaptureSession session() {
        return captureSession;
    }

    static long sourceEpoch() {
        return sourceEpoch;
    }

    static void advanceSourceEpoch() {
        sourceEpoch++;
    }

    static boolean sessionIsCurrent(Minecraft client) {
        return sessionIsCurrent(client, captureSession);
    }

    static void acceptTarget(Minecraft client, long connectionEpoch, WorldGraphService.Target target) {
        var previous = captureSession;
        if (previous != null && !previous.targets(target)) {
            boolean sameSource = previous.sourceNodeId().equals(target.parentNodeId())
                    && sessionIsCurrent(client, previous);
            if (sameSource)
                DownloadCapture.finishPendingBlockChanges(client);
            endCaptureSession(sameSource);
        }
        var graph = WorldGraphService.instance();
        var config = ModConfig.get();
        var settings =
                new CaptureSession.Settings(graph.conflictStrategy(target.nodeId(), target.parentNodeId()),
                        WorldStructureCreator.resolveNewWorldSettings(
                                WorldStructureCreator.captureWorldSettings(client.level)),
                        config.cache.invalidateAfterExport);
        captureSession =
                new CaptureSession(connectionEpoch, sourceEpoch, target.parentNodeId(), target, settings);
    }

    static void activateDownload(Minecraft client, String reason) {
        if (captureEnabled)
            return;
        var mirror = MirrorWorldContext.current();
        if (mirror.isMirror() && !mirrorCaptureWarningShown) {
            mirrorCaptureWarningShown = true;
            ClientDialogs.toast(client, new MirrorPrompt.Text("screen.worldmirror.captureMirror.title"),
                    new MirrorPrompt.Text(mirror.state() == MirrorWorldContext.State.OUTDATED
                                    ? "screen.worldmirror.captureMirror.outdated"
                                    : "screen.worldmirror.captureMirror.body"));
        }
        captureEnabled = true;
        CaptureResources.reset();
        downloadSessionStartedAtMs = System.currentTimeMillis();
        DownloadPerformanceDiagnostics.beginSession(client);
        EntityTracker.resetObservationEpochs();
        exportCoordinator.markEntitiesDirty();
        long nowMs = System.nanoTime() / 1_000_000L;
        exportScheduler.reset(ModConfig.get(), nowMs);
        lastCacheEvictionMs = nowMs;
        if (client.level != null)
            DownloadCapture.queueLoadedScan(client, "initial-capture");
        WMPlayerMessages.sendOverlayMessage(
                client.player, Component.translatable("msg.worldmirror.downloadStart"));
        WMLogger.info("Download activated pipeline=" + exportScheduler.mode()
                + " world=" + captureSession.target().folder() + (reason == null ? "" : " reason=" + reason));
        CaptureResources.updateResourceState(client, System.nanoTime() / 1_000_000L);
    }

    private static ExportWork.Intent exportRequest(ExportWork.Trigger trigger, boolean notifyPlayer) {
        var session = Objects.requireNonNull(captureSession, "No capture target");
        return new ExportWork.Intent(new ExportWork.Context(trigger, notifyPlayer, session),
                ExportWork.CapturePhase.REQUIRED, ExportWork.EntityScope.LIVE,
                trigger == ExportWork.Trigger.MANUAL && ModConfig.get().lifecycle.captureNearbyBeforeExport,
                java.util.Optional.empty());
    }

    private static void dispatchExports(Minecraft client, CaptureBudget budget, long now) {
        var session = sessionIsCurrent(client, captureSession) ? captureSession : null;
        exportCoordinator.dispatch(client, budget, session, captureEnabled, now, ModConfig.get(),
                trigger -> exportRequest(trigger, false));
    }

    /**
     * Finalisation path when download is deactivated.
     * Optionally captures nearby chunks and then writes all cached chunks once.
     */
    private static void finalizeCaptureOnStop(Minecraft client, String reason) {
        ModConfig.LifecycleConfig lifecycle = ModConfig.get().lifecycle;
        if (captureSession == null)
            return;
        if (!sessionIsCurrent(client, captureSession)) {
            freezeStoppedSource(false);
            return;
        }
        if (lifecycle.captureNearbyOnStop && client.level != null
                && CaptureResources.currentBudget().preExportScan()) {
            DownloadCapture.captureNearbyLoadedChunksSync(client, "stop-" + reason);
        }

        if (lifecycle.exportAllCachedOnStop) {
            exportCoordinator.enqueue(exportCoordinator.freeze(client,
                    exportRequest(ExportWork.Trigger.STOP, false).ready(), CaptureResources.currentBudget()));
            dispatchExports(client, CaptureResources.currentBudget(), System.nanoTime() / 1_000_000L);
        }
    }

    /** A changed connection can retain captured data without sampling the new world. */
    private static void freezeStoppedSource(boolean force) {
        if (captureSession == null || (!force && !ModConfig.get().lifecycle.exportAllCachedOnStop))
            return;
        var frozen =
                new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false, captureSession),
                        exportCoordinator.snapshotReadyTerrain(), EntityTracker.snapshot(),
                        ContainerTracker.snapshotSavedData(), 0L, java.util.OptionalLong.empty());
        if (frozen.hasTerrain() || frozen.hasEntities())
            exportCoordinator.enqueue(frozen);
    }
}
