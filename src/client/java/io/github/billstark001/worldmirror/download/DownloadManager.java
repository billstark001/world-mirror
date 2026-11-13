package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
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
    private static volatile boolean mirrorCaptureWarningShown;
    private static final ExportScheduler exportScheduler = new ExportScheduler();
    private static final LatencyWindow worldFrameIntervals = new LatencyWindow(8192);
    private static final LatencyWindow worldMirrorTickWork = new LatencyWindow(4096);
    private static volatile long lastDiagnosticLogMs;
    private static volatile long diagnosticSessionStartedMs;
    private static volatile boolean diagnosticSessionActive;
    private static volatile boolean recordPerformanceTimings;
    private static final DownloadCaptureQueue captureQueue =
            new DownloadCaptureQueue(() -> recordPerformanceTimings, INITIAL_CAPTURE_RANGE, () -> ModConfig.get().performance.maxPendingCaptureHints);
    private static final DownloadExportCoordinator exportCoordinator =
            new DownloadExportCoordinator(captureQueue, exportScheduler, DownloadExportCoordinator.createExportExecutor(),
                    work -> DownloadExportCoordinator.createJob(work, exportScheduler.mode()));
    private static volatile long lastWorldFrameNs;
    private static final Map<String, GcSnapshot> lastGcByCollector = new HashMap<>();

    private record GcSnapshot(long count, long timeMs) { }

    // ── Lifecycle tracking ────────────────────────────────────────────────────

    /**
     * The dimension registry key observed on the previous client tick.
     * Used to detect dimension changes (e.g. Overworld → Nether).
     */
    private static volatile ResourceKey<Level> lastDimension = null;

    /** Target and conflict policy frozen for the current capture session. */
    private static volatile ExportWork.Source captureSource;

    /** User-enabled capture, including resource pauses; packet and unload hooks stay enabled. */
    public static boolean isCaptureEnabled() {
        return captureEnabled;
    }

    /** Enabled capture can be temporarily reduced or paused without changing its toggle. */
    public static String captureStatusKey() {
        if (!captureEnabled) return "screen.worldmirror.status.downloadInactive";
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
        if (previousNs > 0L) worldFrameIntervals.record((nowNs - previousNs) / 1_000L);
    }

    public static boolean canCapture(ClientLevel world) {
        if (!captureEnabled || world == null) return false;
        Minecraft client = Minecraft.getInstance();
        return client.level == world && WorldGraphService.instance().observationIsCurrent(client);
    }

    public static void markEntitiesDirty(ClientLevel world) {
        if (canCapture(world)) exportCoordinator.markEntitiesDirty();
    }

    /** Coalesces a packet/event hint into one main-thread capture per chunk. */
    public static void queueChunkCapture(ClientLevel world, ChunkPos pos, String reason) {
        if (pos == null || !canCapture(world)) return;
        captureQueue.queueChunk(world, pos, reason);
    }

    /** Captures authoritative block changes, including removal of the final block in a chunk. */
    public static void queueBlockChange(ClientLevel world, ChunkPos pos, String reason) {
        if (pos == null || !canCapture(world)) return;
        captureQueue.queueBlockChange(world, pos, reason);
    }

    /** Last-chance main-thread capture before Fabric removes a loaded chunk. */
    public static void captureChunkBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (chunk == null || !canCapture(world)) return;
        captureQueue.captureBeforeUnload(world, chunk);
    }

    /** Queues a normal capture when a light packet arrives before its base chunk. */
    public static void queueLightUpdateCapture(ClientLevel world, ChunkPos pos) {
        if (!canCapture(world)) return;
        captureQueue.queueLightUpdate(world, pos);
    }

    public static Set<ChunkPos> pendingChunkCaptures(ResourceKey<Level> dimension,
                                                     int minX, int maxX, int minZ, int maxZ) {
        if (!captureEnabled || dimension == null) return Set.of();
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
        if (!canCapture(world)) return;
        captureQueue.markLightDirty(world, pos);
    }

    // ── Public commands ───────────────────────────────────────────────────────

    /**
     * Toggles downloading on/off.
     * When enabling, all currently loaded chunks in the active dimension are
     * queued for capture across subsequent ticks so the player need not reload them.
     */
    public static void toggle(Minecraft client) {
        if (!WorldGraphService.instance().observationIsCurrent(client)) return;
        if (captureEnabled) {
            captureEnabled = false;
            finishPendingBlockChanges(client);
            clearPendingCaptureState();
            finalizeCaptureOnStop(client, "manual-toggle");
            WMPlayerMessages.sendOverlayMessage(client.player,
                    Component.translatable("msg.worldmirror.downloadStop"));
            WMLogger.debug("Download deactivated");
            return;
        }
        requestDownloadStart(client, "manual-toggle");
    }

    /**
     * Performs an immediate one-shot export regardless of the toggle state.
     */
    public static void exportNow(Minecraft client) {
        if (manualExportChecking || client.level == null || !WorldGraphService.instance().observationIsCurrent(client)) return;
        requestOutputReady(client, OutputOrigin.current(client), () -> exportNowReady(client), true);
    }

    private static void exportNowReady(Minecraft client) {
        updateResourceState(client, System.nanoTime() / 1_000_000L);
        boolean canPreCapture = ModConfig.get().lifecycle.captureNearbyBeforeExport
                && client.level != null && client.player != null;
        if (ChunkListener.isEmpty() && !exportCoordinator.hasEntityWork()
                && !EntityTracker.hasDirtyUpdates() && !canPreCapture) {
            Component msg = Component.translatable("msg.worldmirror.noChunks");
            WMPlayerMessages.sendSystemMessage(client.player, msg);
            WMLogger.debug("Manual export ignored because no chunks are cached.");
            return;
        }
        var request = exportRequest(client, ExportWork.Trigger.MANUAL, true);
        if (exportCoordinator.isInProgress()) {
            WMPlayerMessages.sendSystemMessage(client.player, Component.translatable("msg.worldmirror.exportBusy"));
            request = request.withContainers(ContainerTracker.snapshotSavedData());
        }
        exportCoordinator.request(request);
        dispatchExports(client, currentBudget(), System.nanoTime() / 1_000_000L);

    }

    /** Clears all in-memory caches. */
    public static void clearAll(Minecraft client) {
        clearPendingCaptureState(false);
        int chunks     = ChunkListener.getTotalCount();
        int entities   = EntityTracker.getTotalTrackedEntities();
        int containers = ContainerTracker.getTotalSavedContainers();
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
        Component msg = Component.translatable("msg.worldmirror.cleared");
        WMPlayerMessages.sendSystemMessage(client.player, msg);
        WMLogger.debug("Cleared: " + chunks + " chunks, " + entities
                + " entities, " + containers + " containers.");
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
        if (wasActive) freezeStoppedSource();
        captureSource = null;
        clearPendingCaptureState(false);
        clearCapturedWorldState();
        mirrorCaptureWarningShown = false;
        var behavior = ModConfig.get().lifecycle.onJoinWorld;
        if (behavior == ModConfig.TransitionBehavior.START || (wasActive && behavior == ModConfig.TransitionBehavior.KEEP)) {
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
            if (WorldGraphService.instance().observationIsCurrent(client)) {
                finishPendingBlockChanges(client);
                clearPendingCaptureState();
                finalizeCaptureOnStop(client, "leave-world");
            } else {
                freezeStoppedSource();
                clearPendingCaptureState(false);
            }
        } else {
            clearPendingCaptureState();
        }
        lastDimension = null;
        captureSource = null;
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
        boolean shouldRecordPerformance = captureEnabled
                && ModConfig.get().performance.diagnosticPerformanceLogging;
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
        exportCoordinator.drainCompletions(captureSource);
        if (client.level == null) {
            dispatchExports(client, currentBudget(), System.nanoTime() / 1_000_000L);
            return;
        }

        ResourceKey<Level> currentDim = client.level.dimension();
        if (WorldGraphService.instance().updateRemoteObservation(client)) {
            boolean wasActive = captureEnabled;
            captureEnabled = false;
            clearPendingCaptureState(false);
            if (wasActive) freezeStoppedSource();
            captureSource = null;
            clearCapturedWorldState();
            lastDimension = currentDim;
            var behavior = ModConfig.get().lifecycle.onServerWorldChange;
            if (behavior == ModConfig.TransitionBehavior.START || (behavior == ModConfig.TransitionBehavior.KEEP && wasActive)) {
                requestDownloadStart(client, "server-world-change");
            } else if (wasActive) {
                WMPlayerMessages.sendOverlayMessage(client.player, Component.translatable("msg.worldmirror.downloadStop"));
            }
        }
        // ── Detect dimension change (Overworld ↔ Nether ↔ End, etc.) ──────────
        if (lastDimension != null && !lastDimension.equals(currentDim)) {
            WMLogger.debug("Dimension change detected: '" + lastDimension.identifier()
                    + "' → '" + currentDim.identifier() + "'");
            lastDimension = currentDim;
            clearPendingCaptureState();
            applyTransition(client, ModConfig.get().lifecycle.onDimensionChange,
                    "dimension-change");
            // Re-capture loaded chunks according to the new active state
            if (captureEnabled) queueLoadedScan(client, "initial-capture");
        }

        // Update tracking even if player was in null world on the previous tick
        if (lastDimension == null) lastDimension = currentDim;

        long now = System.nanoTime() / 1_000_000L;
        if (captureEnabled || captureQueue.hasWork() || exportCoordinator.isInProgress()) updateResourceState(client, now);
        CaptureBudget budget = currentBudget();
        captureQueue.tick(client, budget);
        dispatchExports(client, budget, now);
        if (!captureEnabled) return;

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
    private static void applyTransition(Minecraft client,
                                        ModConfig.TransitionBehavior behavior,
                                        String eventName) {
        boolean wasActive = captureEnabled;
        boolean desired;
        switch (behavior) {
            case START -> desired = true;
            case STOP  -> desired = false;
            default    -> { return; } // KEEP — do nothing
        }
        if (wasActive == desired) return; // already in the right state

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
        var current = captureSource;
        return current == null ? null : current.target();
    }

    public static void moveMirrorWorld(Minecraft client, MirrorTarget expected, ModConfig.SaveLocation targetLocation,
                                       java.util.function.Consumer<MirrorMoveResult> completed) {
        if (isCaptureEnabled()) { completed.accept(MirrorMoveResult.failure("download_active")); return; }
        if (isExportInProgress()) { completed.accept(MirrorMoveResult.failure("export_in_progress")); return; }
        var graph = WorldGraphService.instance();
        var target = graph.target(graph.source());
        if (target == null || !graph.observationIsCurrent(client)
                || !expected.equals(new MirrorTarget(target.folder(), target.nodeId(), target.sourceId()))) {
            completed.accept(MirrorMoveResult.failure("source_missing"));
            return;
        }
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-MoveMirror", () -> {
            var result = graph.move(target, targetLocation);
            return new MirrorMoveResult(true, null, result.source(), result.target());
        }, failure -> {
            OperationFailures.report(target.nodeId(), "relocation", target.folder(), failure);
            return MirrorMoveResult.failure("io_error");
        }, client::execute, completed, () -> {});
    }

    public record MirrorMoveResult(boolean success, String failureCode, Path source, Path target) {
        private static MirrorMoveResult failure(String failureCode) {
            return new MirrorMoveResult(false, failureCode, null, null);
        }
    }

    private static void requestDownloadStart(Minecraft client, String reason) {
        var origin = OutputOrigin.current(client);
        if (!origin.matches(client)) return;
        Runnable continueStart = () -> requestOutputReady(client, origin, () -> activateDownload(client, reason), false);
        MirrorWorldContext.Snapshot currentMirror = MirrorWorldContext.current();
        if (currentMirror.isMirror() && !mirrorCaptureWarningShown) {
            String bodyKey = currentMirror.state() == MirrorWorldContext.State.OUTDATED
                    ? "screen.worldmirror.captureMirror.outdated"
                    : "screen.worldmirror.captureMirror.body";
            ClientDialogs.confirm(client, new MirrorPrompt.Confirmation(
                    new MirrorPrompt.Text("screen.worldmirror.captureMirror.title"),
                    new MirrorPrompt.Text(bodyKey),
                    new MirrorPrompt.Text("screen.worldmirror.captureMirror.continue"),
                    new MirrorPrompt.Text("gui.cancel")), () -> {
                if (!origin.matches(client)) return;
                mirrorCaptureWarningShown = true;
                continueStart.run();
            }, () -> {});
            return;
        }
        continueStart.run();
    }

    /** Ensures an output world is current only after an explicit confirmation. */
    private static void requestOutputReady(Minecraft client, OutputOrigin origin, Runnable onReady, boolean manualExport) {
        if (!origin.matches(client)) return;
        var graph = WorldGraphService.instance();
        var observation = origin.source();
        var preparedSource = captureSource;
        Screen parentScreen = manualExport ? ClientDialogs.currentScreen(client) : null;
        Runnable close;
        if (manualExport) {
            manualExportChecking = true;
            close = () -> manualExportChecking = false;
        } else {
            MirrorPrompt.ProgressHandle progress = ClientDialogs.progress(client,
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.checking"));
            close = progress::close;
        }
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-InspectOutput",
                () -> {
                    if (graph.source() != observation) throw new java.io.IOException("Connection changed during output preparation");
                    var target = preparedSource == null ? graph.prepare(observation, () -> graph.source() == observation)
                            : graph.currentTarget(preparedSource.target());
                    return new PreparedOutput(target, MirrorMigrationCoordinator.recoverAndInspect(target.folder()));
                }, failure -> {
                    OperationFailures.report(observation == null ? "world_graph" : observation.label(), "worldgen_check", null, failure);
                    return null;
                }, client::execute, prepared -> {
                    if (!origin.matches(client)) return;
                    boolean showDialog = !manualExport || ClientDialogs.currentScreen(client) == parentScreen;
                    if (prepared == null) {
                        showOutputProblem(client, origin, null, onReady, null, showDialog);
                        return;
                    }
                    var target = prepared.target();
                    Runnable accept = () -> {
                        if (!origin.matches(client)) return;
                        var previous = captureSource;
                        var strategy = graph.conflictStrategy(target.nodeId(), target.parentNodeId());
                        if (previous == null || !previous.target().equals(target) || previous.strategy() != strategy) {
                            captureSource = new ExportWork.Source(target, strategy);
                        }
                        onReady.run();
                    };
                    if (prepared.inspection().mayCreateOrWriteWithoutMigration()) accept.run();
                    else showOutputProblem(client, origin, target, accept, prepared.inspection(), showDialog);
                }, close);
    }

    private record PreparedOutput(WorldGraphService.Target target, MirrorMigrationPlan.Inspection inspection) {}

    private record OutputOrigin(WorldGraphService.Source source, ClientPacketListener connection) {
        static OutputOrigin current(Minecraft client) {
            return new OutputOrigin(WorldGraphService.instance().source(), client.getConnection());
        }

        boolean matches(Minecraft client) {
            var graph = WorldGraphService.instance();
            return source != null && connection == client.getConnection() && source == graph.source()
                    && graph.observationIsCurrent(client);
        }
    }

    private static void showOutputProblem(Minecraft client, OutputOrigin origin, WorldGraphService.Target target, Runnable onReady,
                                          MirrorMigrationPlan.Inspection plan, boolean showDialog) {
        if (plan == null) {
            showOutputAlert(client, showDialog,
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody", "inspection_failed"));
            return;
        }
        switch (plan.state()) {
            case OUTDATED -> {
                var title = new MirrorPrompt.Text(plan.repairWorldgen()
                        ? "screen.worldmirror.repair.title" : "screen.worldmirror.upgrade.title");
                var message = new MirrorPrompt.Text(plan.repairWorldgen()
                        ? "screen.worldmirror.repair.downloadBody" : "screen.worldmirror.upgrade.downloadBody", target.folder().getFileName());
                if (showDialog) ClientDialogs.confirm(client, new MirrorPrompt.Confirmation(title, message,
                        new MirrorPrompt.Text(plan.repairWorldgen()
                                ? "screen.worldmirror.repair.downloadConfirm" : "screen.worldmirror.upgrade.downloadConfirm"),
                        new MirrorPrompt.Text("gui.cancel")),
                        () -> migrateOutputAndContinue(client, origin, target, onReady), () -> {});
                else ClientDialogs.toast(client, title, message);
            }
            case FUTURE -> showOutputAlert(client, showDialog,
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.futureTitle"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.futureBody"));
            default -> showOutputAlert(client, showDialog,
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                    plan.problem() != null ? new MirrorPrompt.Text("screen.worldmirror.repair.unavailableBody")
                            : new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody", plan.state().name()));
        }
    }

    private static void showOutputAlert(Minecraft client, boolean showDialog, MirrorPrompt.Text title,
                                        MirrorPrompt.Text message) {
        if (showDialog) ClientDialogs.alert(client,
                new MirrorPrompt.Alert(title, message, new MirrorPrompt.Text("gui.done")), () -> {});
        else ClientDialogs.toast(client, title, message);
    }

    private static void migrateOutputAndContinue(Minecraft client, OutputOrigin origin,
                                                 WorldGraphService.Target target, Runnable onReady) {
        if (!origin.matches(client)) return;
        MirrorPrompt.ProgressHandle progress = ClientDialogs.progress(client,
                new MirrorPrompt.Text("screen.worldmirror.upgrade.progressTitle"));
        io.github.billstark001.worldmirror.util.OfflineTasks.start("WM-MirrorMigration",
                () -> {
                    var graph = WorldGraphService.instance();
                    if (origin.source() != graph.source()) return MirrorMigrationCoordinator.Result.failure("source_changed");
                    var resolved = graph.currentTarget(target);
                    var mirror = new MirrorTarget(resolved.folder(), target.nodeId(), target.sourceId());
                    return MirrorMigrationCoordinator.migrateApproved(mirror, new MirrorMigrationProgress(client, progress));
                },
                failure -> MirrorMigrationCoordinator.Result.failure("unexpected_failure"),
                client::execute, result -> {
                if (!origin.matches(client)) return;
                if (!result.success()) {
                    ClientDialogs.alert(client, new MirrorPrompt.Alert(
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.failedTitle"),
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.failedBody", result.failure()),
                            new MirrorPrompt.Text("gui.done")), () -> {});
                    return;
                }
                ClientDialogs.toast(client,
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.completeTitle"),
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.completeBody"));
                onReady.run();

                }, progress::close);
    }

    private static void activateDownload(Minecraft client, String reason) {
        if (captureEnabled) return;
        captureEnabled = true;
        capturePolicy.reset();
        downloadSessionStartedAtMs = System.currentTimeMillis();
        resetDiagnosticSession();
        diagnosticSessionActive = ModConfig.get().performance.diagnosticPerformanceLogging;
        recordPerformanceTimings = diagnosticSessionActive;
        if (diagnosticSessionActive) DownloadConfigDiagnostics.log(client);
        EntityTracker.resetObservationEpochs();
        exportCoordinator.markEntitiesDirty();
        long nowMs = System.nanoTime() / 1_000_000L;
        exportScheduler.reset(ModConfig.get(), nowMs);
        nextResourceCheckMs = Long.MIN_VALUE;
        lastCacheEvictionMs = nowMs;
        if (client.level != null) queueLoadedScan(client, "initial-capture");
        WMPlayerMessages.sendOverlayMessage(client.player, Component.translatable("msg.worldmirror.downloadStart"));
        WMLogger.info("Download activated with pipeline=" + exportScheduler.mode()
                + (reason == null ? "" : " by " + reason));
        updateResourceState(client, System.nanoTime() / 1_000_000L);
    }

    private static void updateResourceState(Minecraft client, long nowMs) {
        if (nextResourceCheckMs != Long.MIN_VALUE && nowMs - nextResourceCheckMs < 0) return;
        nextResourceCheckMs = nowMs + 1_000L;
        var sample = exportCoordinator.sampleResources();
        var previous = capturePolicy.mode();
        var state = capturePolicy.update(nowMs, sample);
        if (state == previous || !captureEnabled) return;

        if (state == CapturePolicy.Mode.NORMAL
                || (previous == CapturePolicy.Mode.PAUSED
                && state == CapturePolicy.Mode.REDUCED)) {
            queueLoadedScan(client, "resource-recovery");
        }
        String details = "source=" + WorldGraphService.instance().source() + " state=" + previous + "->" + state
                + " reason=" + capturePolicy.reason() + " heapBytes=" + sample.usedHeap() + "/" + sample.maxHeap()
                + " dirty=" + sample.dirtyChunks() + " retainedChunks=" + sample.retainedChunks()
                + " frozen=" + sample.frozenBundles() + " inFlightChunks=" + sample.inFlightChunks()
                + " pendingCaptures=" + captureQueue.metrics().pendingCaptures();
        if (state == CapturePolicy.Mode.NORMAL) WMLogger.info("Capture resource recovery " + details);
        else WMLogger.warn("Capture resource pressure " + details);

        if (client.player != null) try {
            String key = switch (state) {
                case REDUCED -> "msg.worldmirror.capture.degraded";
                case PAUSED -> "msg.worldmirror.capture.paused";
                default -> "msg.worldmirror.capture.resumed";
            };
            Component cause = Component.translatable("msg.worldmirror.capture.reason."
                    + capturePolicy.reason().name().toLowerCase(java.util.Locale.ROOT));
            Component notice = Component.translatable(key, cause).withStyle(state == CapturePolicy.Mode.NORMAL
                    ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.YELLOW);
            WMPlayerMessages.sendSystemMessage(client.player, notice);
            WMPlayerMessages.showExportToast(client, Component.translatable(captureStatusKey()), cause);
        } catch (RuntimeException noticeFailure) {
            WMLogger.warnRateLimited("capture-resource-notice", 60_000L,
                    "Cannot display capture resource state; capture and export processing continue", noticeFailure);
        }
    }

    private static CaptureBudget currentBudget() {
        return CaptureBudget.forMode(capturePolicy.mode(), ModConfig.get().performance.captureBudgetMicros);
    }

    private static ExportWork.Intent exportRequest(Minecraft client, ExportWork.Trigger trigger, boolean notifyPlayer) {
        var source = java.util.Objects.requireNonNull(captureSource, "No capture target");
        var settings = io.github.billstark001.worldmirror.io.WorldStructureCreator.resolveNewWorldSettings(
                io.github.billstark001.worldmirror.io.WorldStructureCreator.captureWorldSettings(client.level));
        return new ExportWork.Intent(new ExportWork.Context(trigger, notifyPlayer, source), ExportWork.CapturePhase.REQUIRED,
                ExportWork.EntityScope.LIVE, trigger == ExportWork.Trigger.MANUAL && ModConfig.get().lifecycle.captureNearbyBeforeExport,
                java.util.Optional.empty(), settings);
    }

    private static void dispatchExports(Minecraft client, CaptureBudget budget, long now) {
        exportCoordinator.dispatch(client, budget, captureEnabled, now, ModConfig.get(), trigger -> exportRequest(client, trigger, false));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Queues a wide area of already-loaded chunks for incremental capture on the
     * game thread. Capturing is spread across subsequent ticks to avoid long render
     * thread stalls while still keeping all chunk/world access on the correct thread.
     */
    private static void queueLoadedScan(Minecraft client, String reason) {
        ClientLevel world = client.level;
        if (world == null || client.player == null) return;

        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        boolean scheduled = captureQueue.queueLoaded(world, playerCX, playerCZ, INITIAL_CAPTURE_RANGE,
                reason);
        if (scheduled) {
            WMLogger.debug("Queued a loaded-chunk scan for incremental capture in ["
                    + dimension.identifier() + "]...");
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
        if (world == null || client.player == null) return;

        long startedNs = System.nanoTime();
        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int captured = 0;

        for (int cx = playerCX - STOP_CAPTURE_RANGE; cx <= playerCX + STOP_CAPTURE_RANGE; cx++) {
            for (int cz = playerCZ - STOP_CAPTURE_RANGE; cz <= playerCZ + STOP_CAPTURE_RANGE; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk wc)) continue;
                try {
                    if (ChunkSerializer.isChunkEmpty(wc)) continue;
                    CompoundTag nbt = ChunkSerializer.serialize(world, wc);
                    ChunkListener.addChunkNbt(dimension, wc.getPos(), nbt);
                    captured++;
                } catch (Exception e) {
                    WMLogger.warnRateLimited("capture-stop-" + reason, 30_000L,
                            "Stop-time capture failed chunk=" + wc.getPos()
                                    + " reason=" + reason, e);
                }
            }
        }

        if (captured > 0) {
            WMLogger.debug("Captured " + captured + " nearby chunks (range=" + STOP_CAPTURE_RANGE
                    + ") for " + reason + " elapsedMs="
                    + ((System.nanoTime() - startedNs) / 1_000_000L) + ".");
        }
    }

    /**
     * Finalisation path when download is deactivated.
     * Optionally captures nearby chunks and then writes all cached chunks once.
     */
    private static void finalizeCaptureOnStop(Minecraft client, String reason) {
        ModConfig.LifecycleConfig lifecycle = ModConfig.get().lifecycle;
        if (captureSource == null) return;
        if (lifecycle.captureNearbyOnStop && client.level != null && currentBudget().preExportScan()) {
            captureNearbyLoadedChunksSync(client, "stop-" + reason);
        }

        if (lifecycle.exportAllCachedOnStop) {
            exportCoordinator.enqueue(exportCoordinator.freeze(client, exportRequest(client, ExportWork.Trigger.STOP, false).ready(), currentBudget()));
            dispatchExports(client, currentBudget(), System.nanoTime() / 1_000_000L);
        }
    }

    /** A changed connection can retain captured data without sampling the new world. */
    private static void freezeStoppedSource() {
        if (captureSource == null || !ModConfig.get().lifecycle.exportAllCachedOnStop) return;
        exportCoordinator.enqueue(new ExportWork.Frozen(
                new ExportWork.Context(ExportWork.Trigger.STOP, false, captureSource),
                ChunkListener.snapshotDirtyState(), EntityTracker.snapshot(), ContainerTracker.snapshotSavedData(),
                WorldSettingsSnapshot.defaults(), 0L, java.util.OptionalLong.empty()));
    }

    /** Finishes applied lighting before dropping pending capture and unprepared export requests. */
    private static void clearPendingCaptureState() {
        clearPendingCaptureState(true);
    }

    private static void finishPendingBlockChanges(Minecraft client) {
        if (client.level != null && captureSource != null) {
            captureQueue.finishBlockChanges(client.level);
        }
    }

    private static void clearPendingCaptureState(boolean preserveLightUpdates) {
        if (preserveLightUpdates) captureQueue.finishLightUpdates();
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

        if (maxAgeMs <= 0 && maxCount <= 0 && maxDist <= 0) return;

        ResourceKey<Level> playerDim = (client.level != null)
                ? client.level.dimension() : null;
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
            lastGcByCollector.put(bean.getName(), new GcSnapshot(
                    Math.max(0L, bean.getCollectionCount()),
                    Math.max(0L, bean.getCollectionTime())));
        }
    }

    private static void maybeLogPerformanceSnapshot(long nowMs) {
        if (!ModConfig.get().performance.diagnosticPerformanceLogging
                || nowMs - lastDiagnosticLogMs < 30_000L) return;
        lastDiagnosticLogMs = nowMs;
        DownloadCaptureQueue.Metrics queued = captureQueue.metrics();
        Runtime runtime = Runtime.getRuntime();
        long usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long committedMiB = runtime.totalMemory() / (1024L * 1024L);
        long gcCount = 0L;
        long gcTimeMs = 0L;
        StringBuilder collectors = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collectors.length() > 0) collectors.append('|');
            long currentCount = Math.max(0L, bean.getCollectionCount());
            long currentTimeMs = Math.max(0L, bean.getCollectionTime());
            GcSnapshot previous = lastGcByCollector.get(bean.getName());
            long countDelta = previous == null ? 0L
                    : Math.max(0L, currentCount - previous.count());
            long timeDelta = previous == null ? 0L
                    : Math.max(0L, currentTimeMs - previous.timeMs());
            gcCount += countDelta;
            gcTimeMs += timeDelta;
            collectors.append(bean.getName().replace(' ', '_'))
                    .append(':').append(countDelta).append('/').append(timeDelta);
            lastGcByCollector.put(bean.getName(), new GcSnapshot(currentCount, currentTimeMs));
        }
        DownloadCaptureQueue.DiagnosticSnapshot capture = captureQueue.snapshotDiagnostics();
        LatencyWindow.Summary captureLatency = capture.captureTickLatency();
        LatencyWindow.Summary unloadLatency = capture.unloadLatency();
        LatencyWindow.Summary frameInterval = worldFrameIntervals.snapshotAndReset();
        LatencyWindow.Summary tickWork = worldMirrorTickWork.snapshotAndReset();
        DownloadExportCoordinator.DiagnosticSnapshot export =
                exportCoordinator.snapshotDiagnostics();
        ChunkExporter.ExportTimings timings = export.timings();
        WMLogger.info("[perf] pipeline=" + exportScheduler.mode()
                + " captureState=" + capturePolicy.mode()
                + " sessionMs=" + Math.max(0L, nowMs - diagnosticSessionStartedMs)
                + " cache=" + ChunkListener.getTotalCount()
                + " dirty=" + ChunkListener.getDirtyCount()
                + " captureQueue=" + queued.pendingCaptures()
                + " oldestHintMs=" + queued.oldestCaptureAgeMs()
                + " coalesced=" + queued.coalescedHints()
                + " dropped=" + queued.droppedHints()
                + " hintReasons=" + capture.hintSummary()
                + " captureReasonLatency=" + capture.reasonLatencySummary()
                + " captureTickCount=" + captureLatency.observations()
                + " captureTickAvgUs=" + captureLatency.average()
                + " captureTickP95Us=" + captureLatency.p95()
                + " captureTickP99Us=" + captureLatency.p99()
                + " captureTickMaxUs=" + captureLatency.maximum()
                + " captureProcessed=" + capture.processed()
                + " captureCompleted=" + capture.completed()
                + " captureBudgetUs=" + ModConfig.get().performance.captureBudgetMicros
                + " captureBudgetOverruns=" + capture.budgetOverruns()
                + " captureBudgetMaxOverrunUs=" + capture.maximumBudgetOverrunUs()
                + " unloadCaptureCount=" + unloadLatency.observations()
                + " unloadCaptureAvgUs=" + unloadLatency.average()
                + " unloadCaptureP95Us=" + unloadLatency.p95()
                + " unloadCaptureP99Us=" + unloadLatency.p99()
                + " unloadCaptureMaxUs=" + unloadLatency.maximum()
                + " unloadCaptureFailures=" + capture.unloadFailures()
                + " frameCount=" + frameInterval.observations()
                + " frameAvgUs=" + frameInterval.average()
                + " frameP95Us=" + frameInterval.p95()
                + " frameP99Us=" + frameInterval.p99()
                + " frameMaxUs=" + frameInterval.maximum()
                + " wmTickCount=" + tickWork.observations()
                + " wmTickAvgUs=" + tickWork.average()
                + " wmTickP95Us=" + tickWork.p95()
                + " wmTickP99Us=" + tickWork.p99()
                + " wmTickMaxUs=" + tickWork.maximum()
                + " automaticExportSuppressed=" + export.automaticSuppressed()
                + " deferredExportCoalesced=" + export.deferredCoalesced()
                + " exportWorkerDutyMs=" + export.workerWallMillis()
                + " lastExportMs=" + export.lastExportMillis()
                + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs()
                + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs()
                + " regionFlushMs=" + timings.flushMs()
                + " regionVerifyMs=" + timings.verificationMs()
                + " entityWriteMs=" + timings.entityMs()
                + " dbCommitMs=" + export.durabilityIndexMillis()
                + " written=" + export.written()
                + " settled=" + export.settled()
                + " unreadable=" + export.unreadable()
                + " failures=" + export.failures()
                + " heapMiB=" + usedMiB + "/" + committedMiB
                + " gcCountDelta=" + gcCount
                + " gcTimeMsDelta=" + gcTimeMs
                + " gcCollectors=" + collectors);
    }

}
