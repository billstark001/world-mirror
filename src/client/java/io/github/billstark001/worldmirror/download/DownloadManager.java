package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ConnectionObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import io.github.billstark001.worldmirror.ui.CaptureRouteScreen;
import io.github.billstark001.worldmirror.xaero.XaeroWorldIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Path;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    // ── State ─────────────────────────────────────────────────────────────────

    private static final CapturePolicy capturePolicy = new CapturePolicy();
    private static volatile boolean captureEnabled;
    private static long nextResourceCheckMs;
    private static volatile long downloadSessionStartedAtMs;
    private static long lastCacheEvictionMs = 0;
    private static boolean manualExportChecking;
    private static final int INITIAL_CAPTURE_RANGE = 33;
    private static final int STOP_CAPTURE_RANGE = 6;
    private static final long CACHE_EVICTION_INTERVAL_MS = 5_000L;
    private static final long PREPARING_NOTICE_NS = 200_000_000L;
    private static final long SOURCE_WAIT_NOTICE_NS = 2_000_000_000L;
    private static boolean mirrorCaptureWarningShown;
    private static final ExportScheduler exportScheduler = new ExportScheduler();
    private static final LatencyWindow worldFrameIntervals = new LatencyWindow(8192);
    private static final LatencyWindow worldMirrorTickWork = new LatencyWindow(4096);
    private static volatile long lastDiagnosticLogMs;
    private static volatile long diagnosticSessionStartedMs;
    private static volatile boolean diagnosticSessionActive;
    private static volatile boolean recordPerformanceTimings;
    private static final DownloadCaptureQueue captureQueue =
            new DownloadCaptureQueue(()
                                             -> recordPerformanceTimings,
                    INITIAL_CAPTURE_RANGE, () -> ModConfig.get().performance.maxPendingCaptureHints);
    private static final DownloadExportCoordinator exportCoordinator = new DownloadExportCoordinator(
            captureQueue, exportScheduler, DownloadExportCoordinator.createExportExecutor(),
            work -> DownloadExportCoordinator.createJob(work, exportScheduler.mode()));
    private static volatile long lastWorldFrameNs;
    private static final Map<String, GcSnapshot> lastGcByCollector = new HashMap<>();

    private record GcSnapshot(long count, long timeMs) {}

    // ── Lifecycle tracking ────────────────────────────────────────────────────

    /**
     * The dimension registry key observed on the previous client tick.
     * Used to detect dimension changes (e.g. Overworld → Nether).
     */
    private static volatile ResourceKey<Level> lastDimension = null;

    /** Target and conflict policy frozen for the current capture session. */
    private static volatile CaptureSession captureSession;
    private static volatile long sourceEpoch;
    private static volatile String selectedMirror;
    private static volatile boolean createMirror;
    private static volatile boolean preparingRoute;
    private static volatile PendingRoute pendingRoute;
    private static volatile boolean preparingDownload;
    private static volatile long routeRequestId;
    private static PreparationReview downloadReview;
    private static boolean respawnBoundary;
    private static long respawnDeadline;

    private record PendingRoute(long id, ClientPacketListener connection, Runnable action, boolean manual,
            long requestedAt, boolean announced) {
        PendingRoute announcedNow() {
            return new PendingRoute(id, connection, action, manual, requestedAt, true);
        }
    }

    public static boolean isDownloadPreparing() {
        return preparingDownload;
    }

    public static boolean hasDownloadPreparationReview() {
        var review = downloadReview;
        return review != null && review.origin().matches(Minecraft.getInstance());
    }

    public static void reviewDownloadPreparation() {
        var review = downloadReview;
        var client = Minecraft.getInstance();
        if (review == null || !review.origin().matches(client) || preparingRoute)
            return;
        showOutputProblem(
                client, review.origin(), review.target(), review.onReady(), review.plan(), true, false);
    }

    /** User-enabled capture, including resource pauses; packet and unload hooks stay enabled. */
    public static boolean isCaptureEnabled() {
        return captureEnabled;
    }

    /** Enabled capture can be temporarily reduced or paused without changing its toggle. */
    public static String captureStatusKey() {
        if (!captureEnabled) {
            if (hasDownloadPreparationReview())
                return "screen.worldmirror.status.downloadNeedsReview";
            return preparingDownload ? "screen.worldmirror.status.downloadPreparing"
                                     : "screen.worldmirror.status.downloadInactive";
        }
        return switch (capturePolicy.mode()) {
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

    public static boolean isManualExportChecking() {
        return manualExportChecking;
    }

    public static boolean hasManualExport() {
        return manualExportChecking || exportCoordinator.hasManualExport();
    }

    /** Records gameplay frame spacing without doing work when diagnostics are disabled. */
    public static void recordWorldFrame() {
        if (!recordPerformanceTimings || !captureEnabled) {
            lastWorldFrameNs = 0L;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.isPaused()) {
            lastWorldFrameNs = 0L;
            return;
        }
        long nowNs = System.nanoTime();
        long previousNs = lastWorldFrameNs;
        lastWorldFrameNs = nowNs;
        if (previousNs > 0L)
            worldFrameIntervals.record((nowNs - previousNs) / 1_000L);
    }

    public static boolean canCapture(ClientLevel world) {
        if (!captureEnabled || world == null)
            return false;
        Minecraft client = Minecraft.getInstance();
        return client.level == world && sessionIsCurrent(client, captureSession);
    }

    public static void markEntitiesDirty(ClientLevel world) {
        if (canCapture(world))
            exportCoordinator.markEntitiesDirty();
    }

    /** Coalesces a packet/event hint into one main-thread capture per chunk. */
    public static void queueChunkCapture(ClientLevel world, ChunkPos pos, String reason) {
        if (pos == null || !canCapture(world))
            return;
        captureQueue.queueChunk(world, pos, reason);
    }

    /** Captures authoritative block changes, including removal of the final block in a chunk. */
    public static void queueBlockChange(ClientLevel world, ChunkPos pos, String reason) {
        if (pos == null || !canCapture(world))
            return;
        captureQueue.queueBlockChange(world, pos, reason);
    }

    /** Last-chance main-thread capture before Fabric removes a loaded chunk. */
    public static void captureChunkBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (chunk == null || !canCapture(world))
            return;
        captureQueue.captureBeforeUnload(world, chunk);
    }

    /** Queues a normal capture when a light packet arrives before its base chunk. */
    public static void queueLightUpdateCapture(ClientLevel world, ChunkPos pos) {
        if (!canCapture(world))
            return;
        captureQueue.queueLightUpdate(world, pos);
    }

    public static Set<ChunkPos> pendingChunkCaptures(
            ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
        if (!captureEnabled || dimension == null)
            return Set.of();
        return captureQueue.pendingChunkPositions(dimension, minX, maxX, minZ, maxZ);
    }

    public static long pendingCaptureStatusVersion(ResourceKey<Level> dimension) {
        return captureQueue.statusVersion(dimension);
    }

    /**
     * Coalesces repeated light-only packets before allowing export. The overlay
     * and its revision change immediately; export waits for the short window.
     */
    public static void markLightUpdateDirty(ClientLevel world, ChunkPos pos) {
        if (!canCapture(world))
            return;
        captureQueue.markLightDirty(world, pos);
    }

    // ── Public commands ───────────────────────────────────────────────────────

    /**
     * Toggles downloading on/off.
     * When enabling, all currently loaded chunks in the active dimension are
     * queued for capture across subsequent ticks so the player need not reload them.
     */
    public static void toggle(Minecraft client) {
        if (!WorldGraphService.instance().observationIsCurrent(client))
            return;
        if (captureEnabled) {
            captureEnabled = false;
            if (sessionIsCurrent(client, captureSession))
                finishPendingBlockChanges(client);
            clearPendingCaptureState(!respawnBoundary);
            finalizeCaptureOnStop(client, "manual-toggle");
            WMPlayerMessages.sendOverlayMessage(
                    client.player, Component.translatable("msg.worldmirror.downloadStop"));
            WMLogger.info("Download stopped reason=manual-toggle");
            return;
        }
        if (preparingDownload || hasDownloadPreparationReview()) {
            cancelRouteRequest();
            WMLogger.info("Download preparation cancelled reason=manual-toggle");
            WMPlayerMessages.sendOverlayMessage(
                    client.player, Component.translatable("msg.worldmirror.downloadStop"));
            return;
        }
        requestDownloadStart(client, "manual-toggle");
    }

    /**
     * Performs an immediate one-shot export regardless of the toggle state.
     */
    public static void exportNow(Minecraft client) {
        if (preparingDownload || hasDownloadPreparationReview() || manualExportChecking
                || client.level == null || !WorldGraphService.instance().observationIsCurrent(client))
            return;
        if (noManualExportWork(client)) {
            WMPlayerMessages.sendSystemMessage(
                    client.player, Component.translatable("msg.worldmirror.noChunks"));
            return;
        }
        requestRoute(client, () -> exportNowReady(client), true);
    }

    private static boolean noManualExportWork(Minecraft client) {
        if (ModConfig.get().lifecycle.captureNearbyBeforeExport && client.level != null
                && client.player != null)
            return false;
        return ChunkListener.isEmpty() && !EntityTracker.hasDirtyUpdates()
                && (captureSession == null || !exportCoordinator.hasEntityWork());
    }

    private static void exportNowReady(Minecraft client) {
        updateResourceState(client, System.nanoTime() / 1_000_000L);
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
        dispatchExports(client, currentBudget(), System.nanoTime() / 1_000_000L);
    }

    /** Clears all in-memory caches. */
    public static void clearAll(Minecraft client) {
        clearPendingCaptureState(false);
        int chunks = ChunkListener.getTotalCount();
        int entities = EntityTracker.getTotalTrackedEntities();
        int containers = ContainerTracker.getTotalSavedContainers();
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
        Component msg = Component.translatable("msg.worldmirror.cleared");
        WMPlayerMessages.sendSystemMessage(client.player, msg);
        WMLogger.debug(
                "Cleared: " + chunks + " chunks, " + entities + " entities, " + containers + " containers.");
    }

    // ── Lifecycle event handlers ──────────────────────────────────────────────

    /**
     * Called when the player joins (or re-joins) a world / server.
     * Resets lifecycle tracking state and applies the configured
     * {@link ModConfig.LifecycleConfig#onJoinWorld} behaviour.
     */
    public static void onJoinWorld(Minecraft client) {
        downloadSessionStartedAtMs = 0L;
        boolean wasActive = captureEnabled;
        captureEnabled = false;
        freezeStoppedSource(false);
        resetRoute();
        clearPendingCaptureState(false);
        clearCapturedWorldState();
        mirrorCaptureWarningShown = false;
        var behavior = ModConfig.get().lifecycle.onJoinWorld;
        if (behavior == ModConfig.TransitionBehavior.START
                || (wasActive && behavior == ModConfig.TransitionBehavior.KEEP)) {
            requestDownloadStart(client, "join-world");
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
                finishPendingBlockChanges(client);
                clearPendingCaptureState();
                finalizeCaptureOnStop(client, "leave-world");
            } else {
                freezeStoppedSource(false);
                clearPendingCaptureState(false);
            }
        } else {
            clearPendingCaptureState();
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
        boolean shouldRecordPerformance =
                captureEnabled && ModConfig.get().performance.diagnosticPerformanceLogging;
        if (shouldRecordPerformance && !diagnosticSessionActive) {
            resetDiagnosticSession();
            diagnosticSessionActive = true;
        } else if (!shouldRecordPerformance) {
            diagnosticSessionActive = false;
        }
        recordPerformanceTimings = shouldRecordPerformance;
        try {
            processClientTick(client);
        } finally {
            if (recordPerformanceTimings) {
                worldMirrorTickWork.record((System.nanoTime() - startedNs) / 1_000L);
            }
        }
    }

    private static void processClientTick(Minecraft client) {
        exportCoordinator.drainCompletions(captureSession);
        if (client.level == null) {
            dispatchExports(client, currentBudget(), System.nanoTime() / 1_000_000L);
            return;
        }

        ResourceKey<Level> currentDim = client.level.dimension();
        var graph = WorldGraphService.instance();
        if (graph.updateRemoteObservation(client)) {
            logicalWorldChanged(client);
            lastDimension = currentDim;
        }
        var evidence = XaeroWorldIdentity.observe(client);
        if (graph.observeProvider(evidence, captureSession != null))
            logicalWorldChanged(client);
        if (respawnBoundary && System.nanoTime() - respawnDeadline >= 0L) {
            respawnBoundary = false;
            if (graph.expectsIdentity() && captureSession != null) {
                graph.requireSourceChoice();
                logicalWorldChanged(client);
            } else if (captureEnabled) {
                queueLoadedScan(client, "respawn-capture");
            }
        }
        continuePendingRoute(client);
        // ── Detect dimension change (Overworld ↔ Nether ↔ End, etc.) ──────────
        if (!respawnBoundary && lastDimension != null && !lastDimension.equals(currentDim)) {
            WMLogger.debug("Dimension change detected: '" + lastDimension.identifier() + "' → '"
                    + currentDim.identifier() + "'");
            lastDimension = currentDim;
            clearPendingCaptureState();
            applyTransition(client, ModConfig.get().lifecycle.onDimensionChange, "dimension-change");
            // Re-capture loaded chunks according to the new active state
            if (captureEnabled)
                queueLoadedScan(client, "initial-capture");
        }

        // Update tracking even if player was in null world on the previous tick
        if (lastDimension == null)
            lastDimension = currentDim;

        long now = System.nanoTime() / 1_000_000L;
        if (captureEnabled || captureQueue.hasWork() || exportCoordinator.isInProgress())
            updateResourceState(client, now);
        CaptureBudget budget = currentBudget();
        if (sessionIsCurrent(client, captureSession))
            captureQueue.tick(client, budget);
        dispatchExports(client, budget, now);
        if (!captureEnabled)
            return;

        if (now - lastCacheEvictionMs >= CACHE_EVICTION_INTERVAL_MS) {
            lastCacheEvictionMs = now;
            applyCacheEviction(client);
        }
        maybeLogPerformanceSnapshot(System.currentTimeMillis());
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
                && (preparingDownload || hasDownloadPreparationReview())) {
            cancelRouteRequest();
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
            requestDownloadStart(client, eventName);
            return;
        }

        captureEnabled = false;
        finishPendingBlockChanges(client);
        clearPendingCaptureState();
        finalizeCaptureOnStop(client, eventName);

        Component msg = Component.translatable("msg.worldmirror.downloadStop");
        WMPlayerMessages.sendOverlayMessage(client.player, msg);
        WMLogger.debug("Download deactivated lifecycleEvent=" + eventName);
    }

    // ── Output path ───────────────────────────────────────────────────────────

    public static WorldGraphService.Target captureTarget() {
        var live = liveCaptureTarget();
        if (live != null)
            return live;
        return WorldGraphService.instance().targetById(selectedMirror);
    }

    public static WorldGraphService.Target liveCaptureTarget() {
        var current = captureSession;
        return current == null ? null : current.target();
    }

    public static void selectMirror(String id) {
        selectMirror(id, false);
    }

    public static void createDownloadMirror() {
        if (WorldGraphService.instance().currentResolution().state() != State.RESOLVED)
            throw new IllegalArgumentException("Resolve the download source first");
        selectMirror(null, true);
    }

    public static boolean isNewMirrorSelected() {
        return createMirror;
    }

    public static boolean canChooseDownloadSource() {
        var graph = WorldGraphService.instance();
        var observation = graph.observation();
        if (!graph.available() || observation == null || observation.endpoint() == null || hasManualExport())
            return false;
        var rule = graph.configSnapshot().endpointRules().get(observation.endpoint());
        return rule == null || rule.idPolicy() != WorldGraphConfig.IdPolicy.REQUIRE_BOUND_ID
                || graph.currentResolution().state() != State.WAITING_ID;
    }

    public static void chooseDownloadSource() {
        if (!canChooseDownloadSource())
            return;
        var graph = WorldGraphService.instance();
        var client = Minecraft.getInstance();
        var connection = client.getConnection();
        var observation = graph.observation();
        var resolution = graph.currentResolution();
        openSourceChooser(client, observation, resolution, (id, create) -> {
            if (connection != client.getConnection() || !graph.observationIsCurrent(client))
                return;
            String selected = graph.chooseSource(observation, id, create);
            if (selected == null || Objects.equals(resolution.sourceNodeId(), selected))
                return;
            boolean active = captureEnabled;
            boolean restart = preparingDownload || downloadReview != null;
            if (!active && restart && pendingRoute != null) {
                // Resolve the retained start request without creating another action or dropping the choice.
                continuePendingRoute(client);
                return;
            }
            if (sessionIsCurrent(client, captureSession)) {
                finishPendingBlockChanges(client);
                captureQueue.finishLightUpdates();
            }
            endCaptureSession(true);
            cancelRouteRequest();
            selectedMirror = null;
            createMirror = false;
            if (active || restart)
                requestDownloadStart(client, "source-selection");
        });
    }

    private static void selectMirror(String id, boolean create) {
        var graph = WorldGraphService.instance();
        var target = graph.targetById(id);
        if (id != null
                && (target == null
                        || !target.parentNodeId().equals(graph.currentResolution().sourceNodeId())))
            throw new IllegalArgumentException(
                    "Selected mirror is not a direct target of the resolved source");
        if (Objects.equals(id, selectedMirror) && create == createMirror)
            return;
        if (id == null && !create)
            target = graph.target(graph.source());
        var client = Minecraft.getInstance();
        boolean active = captureEnabled;
        boolean restart = preparingDownload || hasDownloadPreparationReview();
        if (captureSession != null && (target == null || !target.nodeId().equals(captureSession.id()))) {
            boolean safeToSample = sessionIsCurrent(client, captureSession);
            if (safeToSample)
                finishPendingBlockChanges(client);
            endCaptureSession(safeToSample);
        } else if (captureSession == null) {
            sourceEpoch++;
        }
        selectedMirror = id;
        createMirror = create;
        String selection = create ? "new" : (id == null ? "default" : "explicit");
        WMLogger.info("Session mirror choice changed selection=" + selection
                + (target == null ? "" : " world=" + target.folder()));
        if (restart)
            cancelRouteRequest();
        if ((active || restart) && !captureEnabled)
            requestDownloadStart(client, "mirror-selection");
    }

    static boolean hasRoutingWork() {
        // A source choice can wait for user configuration, but a resolved disk check or export owns its route.
        return captureEnabled || preparingRoute || exportCoordinator.hasQueuedWork();
    }

    public static void moveMirrorWorld(Minecraft client, MirrorTarget expected,
            ModConfig.SaveLocation targetLocation, java.util.function.Consumer<MirrorMoveResult> completed) {
        if (isCaptureEnabled()) {
            completed.accept(MirrorMoveResult.failure("download_active"));
            return;
        }
        if (isExportInProgress()) {
            completed.accept(MirrorMoveResult.failure("export_in_progress"));
            return;
        }
        var graph = WorldGraphService.instance();
        var target = graph.targetById(expected.nodeId());
        if (target == null || !graph.observationIsCurrent(client)
                || !expected.equals(new MirrorTarget(target.folder(), target.nodeId(), target.sourceId()))) {
            completed.accept(MirrorMoveResult.failure("source_missing"));
            return;
        }
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-MoveMirror",
                ()
                        -> {
                    var result = graph.move(target, targetLocation);
                    return new MirrorMoveResult(true, null, result.source(), result.target());
                },
                failure
                -> {
                    OperationFailures.report(target.nodeId(), "relocation", target.folder(), failure);
                    return MirrorMoveResult.failure("io_error");
                },
                client::execute, completed, () -> {});
    }

    public record MirrorMoveResult(boolean success, String failureCode, Path source, Path target) {
        private static MirrorMoveResult failure(String failureCode) {
            return new MirrorMoveResult(false, failureCode, null, null);
        }
    }

    private static void requestDownloadStart(Minecraft client, String reason) {
        if (captureEnabled || preparingDownload || client.level == null
                || !WorldGraphService.instance().observationIsCurrent(client))
            return;
        preparingDownload = true;
        WMLogger.debug("Download preparation started reason=" + reason);
        WMPlayerMessages.sendOverlayMessage(
                client.player, Component.translatable("screen.worldmirror.status.downloadPreparing"));
        requestRoute(client, () -> activateDownload(client, reason), false);
    }

    public static void onRespawnBoundary(ClientPacketListener connection) {
        var client = Minecraft.getInstance();
        if (connection == client.getConnection() && client.getSingleplayerServer() == null) {
            if (captureSession != null && sessionIsCurrent(client, captureSession)) {
                finishPendingBlockChanges(client);
                captureQueue.finishLightUpdates();
                captureQueue.clear();
            }
            respawnBoundary = true;
            respawnDeadline = System.nanoTime()
                    + (WorldGraphService.instance().expectsIdentity() ? 2_000_000_000L : 0L);
        }
    }

    public static void observeProvider(ClientPacketListener connection, ProviderObservation evidence) {
        var client = Minecraft.getInstance();
        var graph = WorldGraphService.instance();
        if (connection != client.getConnection())
            return;
        boolean wasFenced = respawnBoundary;
        boolean advertised = evidence.state() == WorldSourceResolver.ProviderState.ADVERTISED;
        boolean routeChanged = graph.observeProvider(evidence, captureSession != null && !wasFenced);
        if (wasFenced && advertised) {
            respawnBoundary = false;
            if (!routeChanged && captureEnabled)
                queueLoadedScan(client, "respawn-capture");
        }
        if (routeChanged)
            logicalWorldChanged(client);
    }

    private static void logicalWorldChanged(Minecraft client) {
        lastDimension = client.level == null ? null : client.level.dimension();
        selectedMirror = null;
        createMirror = false;
        if (captureSession == null && (pendingRoute != null || !preparingRoute && downloadReview == null))
            return; // first provider response; keep any pending join action
        boolean active = captureEnabled;
        boolean starting = preparingDownload || downloadReview != null;
        WMLogger.info("Download route changed; ending previous session active=" + active
                + " preparing=" + starting);
        endCaptureSession(false);
        cancelRouteRequest();
        var behavior = ModConfig.get().lifecycle.onServerWorldChange;
        if (behavior == ModConfig.TransitionBehavior.START || (starting && !active)
                || (active && behavior == ModConfig.TransitionBehavior.KEEP)) {
            requestDownloadStart(client, "server-world-change");
        } else if (active) {
            WMPlayerMessages.sendOverlayMessage(
                    client.player, Component.translatable("msg.worldmirror.downloadStop"));
        }
    }

    private static void endCaptureSession(boolean finishLighting) {
        captureEnabled = false;
        if (finishLighting)
            captureQueue.finishLightUpdates();
        freezeStoppedSource(true);
        captureSession = null;
        sourceEpoch++;
        clearPendingCaptureState(false);
        clearCapturedWorldState();
    }

    private static void resetRoute() {
        captureSession = null;
        cancelRouteRequest();
        selectedMirror = null;
        createMirror = false;
        respawnBoundary = false;
        sourceEpoch++;
    }

    private static void cancelRouteRequest() {
        routeRequestId++;
        pendingRoute = null;
        preparingDownload = false;
        downloadReview = null;
    }

    private static boolean sessionIsCurrent(Minecraft client, CaptureSession session) {
        var graph = WorldGraphService.instance();
        return session != null && !respawnBoundary && session.connectionEpoch() == graph.connectionEpoch()
                && session.sourceEpoch() == sourceEpoch && graph.observationIsCurrent(client);
    }

    private static void requestRoute(Minecraft client, Runnable action, boolean manual) {
        if (client.level == null)
            return;
        pendingRoute = new PendingRoute(
                ++routeRequestId, client.getConnection(), action, manual, System.nanoTime(), false);
        continuePendingRoute(client);
    }

    private static void continuePendingRoute(Minecraft client) {
        var request = pendingRoute;
        if (request == null || preparingRoute || client.level == null || respawnBoundary)
            return;
        long elapsed = System.nanoTime() - request.requestedAt();
        // Give the HUD and status screen a chance to show preparation even for cached targets.
        if (!request.manual() && elapsed < PREPARING_NOTICE_NS)
            return;
        var graph = WorldGraphService.instance();
        if (request.connection() != client.getConnection() || !graph.observationIsCurrent(client)) {
            cancelRouteRequest();
            return;
        }
        if (!graph.available())
            return;
        if (graph.needsLocalIdentity(client)) {
            preparingRoute = true;
            manualExportChecking = request.manual();
            graph.requireLinkedSource(client).whenComplete((id, failure) -> {
                preparingRoute = false;
                manualExportChecking = false;
                if (pendingRoute != request || request.connection() != client.getConnection())
                    return;
                if (failure == null) {
                    continuePendingRoute(client);
                } else {
                    cancelRouteRequest();
                    WMLogger.warnRateLimited("local-world-identity", 30_000L,
                            "Could not assign the active world's identity", failure);
                    showOutputProblem(client, OutputOrigin.current(client), null, request.action(), null,
                            request.manual(), request.manual());
                }
            });
            return;
        }
        var resolution = graph.currentResolution();
        if (resolution.state() == State.RESOLVED) {
            pendingRoute = null;
            requestOutputReady(client, OutputOrigin.current(client), request.action(), request.manual());
            return;
        }
        if (resolution.state() == State.WAITING_ID && !request.manual() && elapsed < SOURCE_WAIT_NOTICE_NS)
            return;
        var observation = graph.observation();
        if (observation == null || observation.endpoint() == null) {
            cancelRouteRequest();
            return;
        }
        var config = graph.configSnapshot();
        var rule = config.endpointRules().get(observation.endpoint());
        boolean canChoose = resolution.state() == State.CHOOSE_SOURCE || rule == null
                || rule.idPolicy() != WorldGraphConfig.IdPolicy.REQUIRE_BOUND_ID;
        if (!canChoose || !request.manual()) {
            // An arriving ID or an explicit source choice can resume this action without interrupting play.
            if (!request.announced()) {
                pendingRoute = request.announcedNow();
                WMLogger.info("Download route awaiting " + (canChoose ? "source selection" : "a bound ID")
                        + " state=" + resolution.state()
                        + " choices=" + resolution.candidateSourceIds().size());
                WMPlayerMessages.sendSystemMessage(client.player,
                        Component.translatable(canChoose ? "screen.worldmirror.captureRoute.selectionRequired"
                                                         : "screen.worldmirror.captureRoute.waiting"));
            }
            return;
        }
        pendingRoute = null;
        WMLogger.info("Manual action requires a download source choice state=" + resolution.state()
                + " choices=" + resolution.candidateSourceIds().size());
        openSourceChooser(client, observation, resolution, (id, create) -> {
            if (request.id() != routeRequestId || request.connection() != client.getConnection()
                    || graph.chooseSource(observation, id, create) == null)
                return;
            requestRoute(client, request.action(), request.manual());
        });
    }

    private static void openSourceChooser(Minecraft client, ConnectionObservation observation,
            WorldSourceResolver.Resolution resolution,
            java.util.function.BiConsumer<String, Boolean> selected) {
        var choices = new java.util.TreeSet<>(resolution.candidateSourceIds());
        CaptureRouteScreen.open(
                ClientDialogs.currentScreen(client), observation.endpoint(), choices, selected);
    }

    /** Ensures an output world is current only after an explicit confirmation. */
    private static void requestOutputReady(
            Minecraft client, OutputOrigin origin, Runnable onReady, boolean manualExport) {
        if (!origin.matches(client) || preparingRoute)
            return;
        var graph = WorldGraphService.instance();
        var observation = origin.source();
        var preparedSession = captureEnabled ? captureSession : null;
        var chosen = selectedMirror;
        var makeMirror = createMirror;
        preparingRoute = true;
        Screen parentScreen = manualExport ? ClientDialogs.currentScreen(client) : null;
        manualExportChecking = manualExport;
        OfflineTasks.start("WM-InspectOutput",
                ()
                        -> {
                    if (!origin.matchesRoute())
                        return null;
                    var target = preparedSession == null
                            ? graph.prepare(observation, chosen, makeMirror, origin::matchesRoute)
                            : graph.currentTarget(preparedSession.target());
                    return new PreparedOutput(
                            target, MirrorMigrationCoordinator.recoverAndInspect(target.folder()));
                },
                failure
                -> {
                    if (origin.matchesRoute())
                        OperationFailures.report(observation.label(), "worldgen_check", null, failure);
                    return null;
                },
                client::execute,
                prepared
                -> {
                    if (!origin.matches(client)) {
                        if (routeRequestId == origin.requestId() && !manualExport)
                            retryDownloadStart(client, onReady);
                        return;
                    }
                    preparingDownload = false;
                    boolean showDialog = manualExport && ClientDialogs.currentScreen(client) == parentScreen;
                    if (prepared == null) {
                        showOutputProblem(client, origin, null, onReady, null, showDialog, manualExport);
                        return;
                    }
                    var target = prepared.target();
                    Runnable accept = () -> {
                        if (!origin.matches(client))
                            return;
                        if (preparedSession == null) {
                            var previous = captureSession;
                            if (previous != null && !previous.targets(target)) {
                                boolean sameSource = previous.sourceNodeId().equals(target.parentNodeId())
                                        && sessionIsCurrent(client, previous);
                                if (sameSource)
                                    finishPendingBlockChanges(client);
                                endCaptureSession(sameSource);
                            }
                            var config = ModConfig.get();
                            var settings = new CaptureSession.Settings(
                                    graph.conflictStrategy(target.nodeId(), target.parentNodeId()),
                                    WorldStructureCreator.resolveNewWorldSettings(
                                            WorldStructureCreator.captureWorldSettings(client.level)),
                                    config.cache.invalidateAfterExport);
                            captureSession = new CaptureSession(origin.observation().connectionEpoch(),
                                    sourceEpoch, target.parentNodeId(), target, settings);
                            if (makeMirror) {
                                selectedMirror = target.nodeId();
                                createMirror = false;
                            }
                        }
                        onReady.run();
                    };
                    if (prepared.inspection().mayCreateOrWriteWithoutMigration())
                        accept.run();
                    else
                        showOutputProblem(client, origin, target, accept, prepared.inspection(), showDialog,
                                manualExport);
                },
                () -> finishRoutePreparation(origin));
    }

    private record
            PreparedOutput(WorldGraphService.Target target, MirrorMigrationPlan.Inspection inspection) {}

    private record OutputOrigin(WorldGraphService.Source source, ConnectionObservation observation,
            String sourceNodeId, long epoch, long requestId, ClientPacketListener connection) {
        static OutputOrigin current(Minecraft client) {
            var graph = WorldGraphService.instance();
            return new OutputOrigin(graph.source(), graph.observation(),
                    graph.currentResolution().sourceNodeId(), sourceEpoch, routeRequestId,
                    client.getConnection());
        }

        boolean matchesRoute() {
            var graph = WorldGraphService.instance();
            return source != null && source == graph.source() && epoch == sourceEpoch
                    && requestId == routeRequestId
                    && Objects.equals(sourceNodeId, graph.currentResolution().sourceNodeId());
        }

        boolean matches(Minecraft client) {
            return matchesRoute() && connection == client.getConnection()
                    && WorldGraphService.instance().observationIsCurrent(client);
        }
    }

    private record PreparationReview(OutputOrigin origin, WorldGraphService.Target target, Runnable onReady,
            MirrorMigrationPlan.Inspection plan) {}

    private static void finishRoutePreparation(OutputOrigin origin) {
        manualExportChecking = false;
        preparingRoute = false;
        if (routeRequestId == origin.requestId())
            preparingDownload = false;
    }

    private static void retryDownloadStart(Minecraft client, Runnable onReady) {
        if (client.level == null || !WorldGraphService.instance().observationIsCurrent(client))
            return;
        preparingDownload = true;
        requestRoute(client, onReady, false);
    }

    private static void showOutputProblem(Minecraft client, OutputOrigin origin,
            WorldGraphService.Target target, Runnable onReady, MirrorMigrationPlan.Inspection plan,
            boolean showDialog, boolean manualExport) {
        if (plan == null) {
            showOutputAlert(client, showDialog,
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody", "inspection_failed"));
            return;
        }
        switch (plan.state()) {
            case OUTDATED -> {
                WMLogger.info("Mirror output awaiting upgrade approval world=" + target.folder()
                        + " generator=" + plan.worldgenWork() + " terrain=" + plan.terrainWork());
                boolean repair =
                        plan.worldgenWork() == MirrorMigrationPlan.WorldgenWork.REPAIR_VOID_GENERATOR;
                var title = new MirrorPrompt.Text(
                        repair ? "screen.worldmirror.repair.title" : "screen.worldmirror.upgrade.title");
                var message = new MirrorPrompt.Text(repair ? "screen.worldmirror.repair.downloadBody"
                                                           : "screen.worldmirror.upgrade.downloadBody",
                        target.folder().getFileName());
                if (showDialog)
                    ClientDialogs.confirm(client,
                            new MirrorPrompt.Confirmation(title, message,
                                    new MirrorPrompt.Text(repair
                                                    ? "screen.worldmirror.repair.downloadConfirm"
                                                    : "screen.worldmirror.upgrade.downloadConfirm"),
                                    new MirrorPrompt.Text("gui.cancel")),
                            ()
                                    -> migrateOutputAndContinue(
                                            client, origin, target, onReady, manualExport),
                            () -> {});
                else if (!manualExport) {
                    downloadReview = new PreparationReview(origin, target, onReady, plan);
                    ClientDialogs.toast(client, title,
                            new MirrorPrompt.Text("screen.worldmirror.status.downloadReviewHint"));
                } else {
                    ClientDialogs.toast(client, title, message);
                }
            }
            case FUTURE -> {
                WMLogger.warn("Mirror output is newer than this client world=" + target.folder());
                showOutputAlert(client, showDialog,
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.futureTitle"),
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.futureBody"));
            }
            default -> {
                WMLogger.warn(
                        "Mirror output unavailable world=" + target.folder() + " state=" + plan.state());
                showOutputAlert(client, showDialog,
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                        plan.problem() != null
                                ? new MirrorPrompt.Text("screen.worldmirror.repair.unavailableBody")
                                : new MirrorPrompt.Text(
                                          "screen.worldmirror.upgrade.unavailableBody", plan.state().name()));
            }
        }
    }

    private static void showOutputAlert(
            Minecraft client, boolean showDialog, MirrorPrompt.Text title, MirrorPrompt.Text message) {
        if (showDialog)
            ClientDialogs.alert(client,
                    new MirrorPrompt.Alert(title, message, new MirrorPrompt.Text("gui.done")), () -> {});
        else
            ClientDialogs.toast(client, title, message);
    }

    private static void migrateOutputAndContinue(Minecraft client, OutputOrigin origin,
            WorldGraphService.Target target, Runnable onReady, boolean manualExport) {
        if (!origin.matches(client) || preparingRoute)
            return;
        downloadReview = null;
        preparingRoute = true;
        preparingDownload = !manualExport;
        manualExportChecking = manualExport;
        OfflineTasks.start("WM-MirrorMigration",
                ()
                        -> {
                    var graph = WorldGraphService.instance();
                    if (!origin.matchesRoute())
                        return MirrorMigrationCoordinator.Result.failure("source_changed");
                    var resolved = graph.currentTarget(target);
                    var mirror = new MirrorTarget(resolved.folder(), target.nodeId(), target.sourceId());
                    return MirrorMigrationCoordinator.migrateApproved(
                            mirror, MirrorMigrationCoordinator.ProgressListener.NONE);
                },
                failure
                -> {
                    WMLogger.warn("Mirror output upgrade failed world=" + target.folder(), failure);
                    return MirrorMigrationCoordinator.Result.failure("unexpected_failure");
                },
                client::execute,
                result
                -> {
                    if (!origin.matches(client))
                        return;
                    if (!result.success()) {
                        ClientDialogs.toast(client,
                                new MirrorPrompt.Text("screen.worldmirror.upgrade.failedTitle"),
                                new MirrorPrompt.Text(
                                        "screen.worldmirror.upgrade.failedBody", result.failure()));
                        return;
                    }
                    ClientDialogs.toast(client,
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.completeTitle"),
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.completeBody"));
                    onReady.run();
                },
                () -> finishRoutePreparation(origin));
    }

    private static void activateDownload(Minecraft client, String reason) {
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
        capturePolicy.reset();
        downloadSessionStartedAtMs = System.currentTimeMillis();
        resetDiagnosticSession();
        diagnosticSessionActive = ModConfig.get().performance.diagnosticPerformanceLogging;
        recordPerformanceTimings = diagnosticSessionActive;
        if (diagnosticSessionActive)
            DownloadConfigDiagnostics.log(client);
        EntityTracker.resetObservationEpochs();
        exportCoordinator.markEntitiesDirty();
        long nowMs = System.nanoTime() / 1_000_000L;
        exportScheduler.reset(ModConfig.get(), nowMs);
        nextResourceCheckMs = Long.MIN_VALUE;
        lastCacheEvictionMs = nowMs;
        if (client.level != null)
            queueLoadedScan(client, "initial-capture");
        WMPlayerMessages.sendOverlayMessage(
                client.player, Component.translatable("msg.worldmirror.downloadStart"));
        WMLogger.info("Download activated pipeline=" + exportScheduler.mode()
                + " world=" + captureSession.target().folder() + (reason == null ? "" : " reason=" + reason));
        updateResourceState(client, System.nanoTime() / 1_000_000L);
    }

    private static void updateResourceState(Minecraft client, long nowMs) {
        if (nextResourceCheckMs != Long.MIN_VALUE && nowMs - nextResourceCheckMs < 0)
            return;
        nextResourceCheckMs = nowMs + 1_000L;
        var sample = exportCoordinator.sampleResources();
        var previous = capturePolicy.mode();
        var state = capturePolicy.update(nowMs, sample);
        if (state == previous || !captureEnabled)
            return;

        if (state == CapturePolicy.Mode.NORMAL
                || (previous == CapturePolicy.Mode.PAUSED && state == CapturePolicy.Mode.REDUCED)) {
            queueLoadedScan(client, "resource-recovery");
        }
        String details = "source=" + WorldGraphService.instance().source() + " state=" + previous + "->"
                + state + " reason=" + capturePolicy.reason() + " heapBytes=" + sample.usedHeap() + "/"
                + sample.maxHeap() + " dirty=" + sample.dirtyChunks() + " retainedChunks="
                + sample.retainedChunks() + " frozen=" + sample.frozenBundles() + " inFlightChunks="
                + sample.inFlightChunks() + " pendingCaptures=" + captureQueue.metrics().pendingCaptures();
        if (state == CapturePolicy.Mode.NORMAL)
            WMLogger.info("Capture resource recovery " + details);
        else
            WMLogger.warn("Capture resource pressure " + details);

        if (client.player != null)
            try {
                String key = switch (state) {
                    case REDUCED -> "msg.worldmirror.capture.degraded";
                    case PAUSED -> "msg.worldmirror.capture.paused";
                    default -> "msg.worldmirror.capture.resumed";
                };
                Component cause = Component.translatable("msg.worldmirror.capture.reason."
                        + capturePolicy.reason().name().toLowerCase(java.util.Locale.ROOT));
                Component notice = Component.translatable(key, cause)
                                           .withStyle(state == CapturePolicy.Mode.NORMAL
                                                           ? net.minecraft.ChatFormatting.GREEN
                                                           : net.minecraft.ChatFormatting.YELLOW);
                WMPlayerMessages.sendSystemMessage(client.player, notice);
                WMPlayerMessages.showExportToast(client, Component.translatable(captureStatusKey()), cause);
            } catch (RuntimeException noticeFailure) {
                WMLogger.warnRateLimited("capture-resource-notice", 60_000L,
                        "Cannot display capture resource state; capture and export processing continue",
                        noticeFailure);
            }
    }

    private static CaptureBudget currentBudget() {
        return CaptureBudget.forMode(capturePolicy.mode(), ModConfig.get().performance.captureBudgetMicros);
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

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Queues a wide area of already-loaded chunks for incremental capture on the
     * game thread. Capturing is spread across subsequent ticks to avoid long render
     * thread stalls while still keeping all chunk/world access on the correct thread.
     */
    private static void queueLoadedScan(Minecraft client, String reason) {
        ClientLevel world = client.level;
        if (world == null || client.player == null || !sessionIsCurrent(client, captureSession))
            return;

        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        boolean scheduled =
                captureQueue.queueLoaded(world, playerCX, playerCZ, INITIAL_CAPTURE_RANGE, reason);
        if (scheduled) {
            WMLogger.debug("Queued a loaded-chunk scan for incremental capture in [" + dimension.identifier()
                    + "]...");
        }
    }

    /**
     * Captures loaded chunks around the player synchronously on the game thread.
     *
     * <p><b>Use only at disconnect / toggle-off time</b> (a one-shot event where a
     * brief freeze is acceptable and the world is still fully accessible). During
     * normal gameplay, the export coordinator instead schedules incremental
     * chunk capture across subsequent client ticks so large nearby refreshes do not
     * block a single render frame.</p>
     */
    private static void captureNearbyLoadedChunksSync(Minecraft client, String reason) {
        ClientLevel world = client.level;
        if (world == null || client.player == null)
            return;

        long startedNs = System.nanoTime();
        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int captured = 0;

        for (int cx = playerCX - STOP_CAPTURE_RANGE; cx <= playerCX + STOP_CAPTURE_RANGE; cx++) {
            for (int cz = playerCZ - STOP_CAPTURE_RANGE; cz <= playerCZ + STOP_CAPTURE_RANGE; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk wc) || SeedChunkTracking.hidden(world, wc))
                    continue;
                try {
                    if (ChunkSerializer.isChunkEmpty(wc))
                        continue;
                    CompoundTag nbt = ChunkSerializer.serialize(world, wc);
                    ChunkListener.addChunkNbt(dimension, wc.getPos(), nbt);
                    captured++;
                } catch (Exception e) {
                    WMLogger.warnRateLimited("capture-stop-" + reason, 30_000L,
                            "Stop-time capture failed chunk=" + wc.getPos() + " reason=" + reason, e);
                }
            }
        }

        if (captured > 0) {
            WMLogger.debug("Captured " + captured + " nearby chunks (range=" + STOP_CAPTURE_RANGE + ") for "
                    + reason + " elapsedMs=" + ((System.nanoTime() - startedNs) / 1_000_000L) + ".");
        }
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
        if (lifecycle.captureNearbyOnStop && client.level != null && currentBudget().preExportScan()) {
            captureNearbyLoadedChunksSync(client, "stop-" + reason);
        }

        if (lifecycle.exportAllCachedOnStop) {
            exportCoordinator.enqueue(exportCoordinator.freeze(
                    client, exportRequest(ExportWork.Trigger.STOP, false).ready(), currentBudget()));
            dispatchExports(client, currentBudget(), System.nanoTime() / 1_000_000L);
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

    /** Finishes applied lighting before dropping pending capture and unprepared export requests. */
    private static void clearPendingCaptureState() {
        clearPendingCaptureState(true);
    }

    private static void finishPendingBlockChanges(Minecraft client) {
        if (client.level != null && captureSession != null) {
            captureQueue.finishBlockChanges(client.level);
        }
    }

    private static void clearPendingCaptureState(boolean preserveLightUpdates) {
        if (preserveLightUpdates)
            captureQueue.finishLightUpdates();
        captureQueue.clear();
        exportCoordinator.cancelRequest();
    }

    /** Drops source-scoped captured data before a different logical world becomes active. */
    private static void clearCapturedWorldState() {
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
        exportCoordinator.markEntitiesDirty();
    }

    /**
     * Applies cache-eviction rules from {@link ModConfig} to the chunk cache.
     * Safe to call on the game thread.
     */
    private static void applyCacheEviction(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        long maxAgeMs = (long) cfg.cache.maxCacheAgeSeconds * 1000L;
        int maxCount = cfg.cache.maxCachedChunks;
        int maxDist = cfg.cache.maxCacheDistanceChunks;

        if (maxAgeMs <= 0 && maxCount <= 0 && maxDist <= 0)
            return;

        ResourceKey<Level> playerDim = (client.level != null) ? client.level.dimension() : null;
        int playerCX = (client.player != null) ? (client.player.getBlockX() >> 4) : 0;
        int playerCZ = (client.player != null) ? (client.player.getBlockZ() >> 4) : 0;

        ChunkListener.evictStale(maxAgeMs, maxCount, playerDim, playerCX, playerCZ, maxDist);
    }

    private static void resetDiagnosticSession() {
        diagnosticSessionStartedMs = System.currentTimeMillis();
        lastDiagnosticLogMs = diagnosticSessionStartedMs;
        captureQueue.resetDiagnostics();
        exportCoordinator.resetDiagnostics();
        worldFrameIntervals.reset();
        worldMirrorTickWork.reset();
        lastWorldFrameNs = 0L;
        lastGcByCollector.clear();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            lastGcByCollector.put(bean.getName(),
                    new GcSnapshot(
                            Math.max(0L, bean.getCollectionCount()), Math.max(0L, bean.getCollectionTime())));
        }
    }

    private static void maybeLogPerformanceSnapshot(long nowMs) {
        if (!ModConfig.get().performance.diagnosticPerformanceLogging
                || nowMs - lastDiagnosticLogMs < 30_000L)
            return;
        lastDiagnosticLogMs = nowMs;
        DownloadCaptureQueue.Metrics queued = captureQueue.metrics();
        Runtime runtime = Runtime.getRuntime();
        long usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long committedMiB = runtime.totalMemory() / (1024L * 1024L);
        long gcCount = 0L;
        long gcTimeMs = 0L;
        StringBuilder collectors = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collectors.length() > 0)
                collectors.append('|');
            long currentCount = Math.max(0L, bean.getCollectionCount());
            long currentTimeMs = Math.max(0L, bean.getCollectionTime());
            GcSnapshot previous = lastGcByCollector.get(bean.getName());
            long countDelta = previous == null ? 0L : Math.max(0L, currentCount - previous.count());
            long timeDelta = previous == null ? 0L : Math.max(0L, currentTimeMs - previous.timeMs());
            gcCount += countDelta;
            gcTimeMs += timeDelta;
            collectors.append(bean.getName().replace(' ', '_'))
                    .append(':')
                    .append(countDelta)
                    .append('/')
                    .append(timeDelta);
            lastGcByCollector.put(bean.getName(), new GcSnapshot(currentCount, currentTimeMs));
        }
        DownloadCaptureQueue.DiagnosticSnapshot capture = captureQueue.snapshotDiagnostics();
        LatencyWindow.Summary captureLatency = capture.captureTickLatency();
        LatencyWindow.Summary unloadLatency = capture.unloadLatency();
        LatencyWindow.Summary frameInterval = worldFrameIntervals.snapshotAndReset();
        LatencyWindow.Summary tickWork = worldMirrorTickWork.snapshotAndReset();
        DownloadExportCoordinator.DiagnosticSnapshot export = exportCoordinator.snapshotDiagnostics();
        ChunkExporter.ExportTimings timings = export.timings();
        WMLogger.info("[perf] pipeline=" + exportScheduler.mode() + " captureState=" + capturePolicy.mode()
                + " sessionMs=" + Math.max(0L, nowMs - diagnosticSessionStartedMs)
                + " cache=" + ChunkListener.getTotalCount() + " dirty=" + ChunkListener.getDirtyCount()
                + " captureQueue=" + queued.pendingCaptures() + " oldestHintMs=" + queued.oldestCaptureAgeMs()
                + " coalesced=" + queued.coalescedHints() + " dropped=" + queued.droppedHints()
                + " hintReasons=" + capture.hintSummary() + " captureReasonLatency="
                + capture.reasonLatencySummary() + " captureTickCount=" + captureLatency.observations()
                + " captureTickAvgUs=" + captureLatency.average() + " captureTickP95Us="
                + captureLatency.p95() + " captureTickP99Us=" + captureLatency.p99() + " captureTickMaxUs="
                + captureLatency.maximum() + " captureProcessed=" + capture.processed() + " captureCompleted="
                + capture.completed() + " captureBudgetUs=" + ModConfig.get().performance.captureBudgetMicros
                + " captureBudgetOverruns=" + capture.budgetOverruns() + " captureBudgetMaxOverrunUs="
                + capture.maximumBudgetOverrunUs() + " unloadCaptureCount=" + unloadLatency.observations()
                + " unloadCaptureAvgUs=" + unloadLatency.average() + " unloadCaptureP95Us="
                + unloadLatency.p95() + " unloadCaptureP99Us=" + unloadLatency.p99() + " unloadCaptureMaxUs="
                + unloadLatency.maximum() + " unloadCaptureFailures=" + capture.unloadFailures()
                + " frameCount=" + frameInterval.observations() + " frameAvgUs=" + frameInterval.average()
                + " frameP95Us=" + frameInterval.p95() + " frameP99Us=" + frameInterval.p99()
                + " frameMaxUs=" + frameInterval.maximum() + " wmTickCount=" + tickWork.observations()
                + " wmTickAvgUs=" + tickWork.average() + " wmTickP95Us=" + tickWork.p95()
                + " wmTickP99Us=" + tickWork.p99() + " wmTickMaxUs=" + tickWork.maximum()
                + " automaticExportSuppressed=" + export.automaticSuppressed() + " deferredExportCoalesced="
                + export.deferredCoalesced() + " exportWorkerDutyMs=" + export.workerWallMillis()
                + " lastExportMs=" + export.lastExportMillis() + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs() + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs() + " regionFlushMs="
                + timings.flushMs() + " regionVerifyMs=" + timings.verificationMs() + " entityWriteMs="
                + timings.entityMs() + " dbCommitMs=" + export.durabilityIndexMillis() + " written="
                + export.written() + " settled=" + export.settled() + " unreadable=" + export.unreadable()
                + " failures=" + export.failures() + " heapMiB=" + usedMiB + "/" + committedMiB
                + " gcCountDelta=" + gcCount + " gcTimeMsDelta=" + gcTimeMs + " gcCollectors=" + collectors);
    }
}
