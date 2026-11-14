package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ConnectionObservation;
import io.github.billstark001.worldmirror.download.WorldConnectionRouting.Choice;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import io.github.billstark001.worldmirror.ui.CaptureRouteScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/** Resolves and prepares a download target for the current connection. */
public final class DownloadRoute {
    private DownloadRoute() {}

    private static final long PREPARING_NOTICE_NS = 200_000_000L;
    private static volatile String selectedMirror;
    private static volatile boolean createMirror;
    private static volatile boolean preparingRoute;
    private static volatile PendingRoute pendingRoute;
    private static volatile boolean preparingDownload;
    private static volatile long routeRequestId;
    private static boolean manualExportChecking;
    private static PreparationReview downloadReview;
    private record PendingRoute(long id, ClientPacketListener connection, Runnable action, boolean manual,
            long requestedAt, boolean announced) {
        PendingRoute announcedNow() {
            return new PendingRoute(id, connection, action, manual, requestedAt, true);
        }
    }

    public static boolean isDownloadPreparing() {
        return preparingDownload;
    }

    static boolean awaitingSource() {
        var request = pendingRoute;
        return request != null && !request.manual() && request.announced();
    }

    public static boolean isManualExportChecking() {
        return manualExportChecking;
    }

    static boolean isCheckingTarget() {
        return preparingRoute;
    }

    static void clearMirrorSelection() {
        selectedMirror = null;
        createMirror = false;
    }

    static boolean canRetainStartOnSourceChange() {
        return pendingRoute != null || !preparingRoute && downloadReview == null;
    }

    static boolean hasStartInProgress() {
        return preparingDownload || downloadReview != null;
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

    public static WorldGraphService.Target captureTarget() {
        var live = DownloadManager.liveCaptureTarget();
        if (live != null)
            return live;
        return WorldGraphService.instance().targetById(selectedMirror);
    }

    public static void selectMirror(String id) {
        selectMirror(id, false);
    }

    public static void createDownloadMirror() {
        if (WorldGraphService.instance().routing().currentResolution().state() != State.RESOLVED)
            throw new IllegalArgumentException("Resolve the download source first");
        selectMirror(null, true);
    }

    public static boolean isNewMirrorSelected() {
        return createMirror;
    }

    public static boolean canChooseDownloadSource() {
        var graph = WorldGraphService.instance();
        var observation = graph.routing().observation();
        if (!graph.available() || observation == null || observation.endpoint() == null
                || DownloadManager.hasManualExport()
                || DownloadWorldChanges.isRespawnBoundary() && !DownloadWorldChanges.needsConfirmation())
            return false;
        return !graph.routing().strictlyRequiresIdentity();
    }

    public static void chooseDownloadSource() {
        if (!canChooseDownloadSource())
            return;
        var graph = WorldGraphService.instance();
        var client = Minecraft.getInstance();
        var connection = client.getConnection();
        var observation = graph.routing().observation();
        var resolution = graph.routing().currentResolution();
        openSourceChooser(client, observation, resolution, (id, choice) -> {
            if (connection != client.getConnection() || !graph.routing().observationIsCurrent(client))
                return;
            String selected = graph.routing().chooseSource(observation, id, choice);
            if (selected == null)
                return;
            boolean active = DownloadManager.isCaptureEnabled();
            boolean restart = preparingDownload || downloadReview != null;
            boolean safe = DownloadManager.sessionIsCurrent(client);
            DownloadWorldChanges.confirmSource(client, observation.worldEpoch(), selected);
            var current = DownloadManager.session();
            if (active && current != null && current.sourceNodeId().equals(selected)
                    && choice != Choice.REMEMBER_ADDRESS) {
                DownloadWorldChanges.resumeCapture(client);
                return;
            }
            if (!active && restart && pendingRoute != null) {
                // Resolve the retained start request without creating another action or dropping the choice.
                continuePendingRoute(client);
                return;
            }
            String previous = current == null ? resolution.sourceNodeId() : current.sourceNodeId();
            if (previous != null && !previous.equals(selected))
                RemoteChunkProvenance.restrict(client.level);
            if (safe) {
                DownloadCapture.finishPendingBlockChanges(client);
                DownloadCapture.queue.finishLightUpdates();
            }
            DownloadManager.endCaptureSession(safe);
            cancelRouteRequest();
            selectedMirror = null;
            createMirror = false;
            if (active || restart || choice == Choice.REMEMBER_ADDRESS)
                requestDownloadStart(client, "source-selection");
        });
    }

    private static void selectMirror(String id, boolean create) {
        var graph = WorldGraphService.instance();
        var target = graph.targetById(id);
        if (id != null
                && (target == null
                        || !target.parentNodeId().equals(graph.routing().currentResolution().sourceNodeId())))
            throw new IllegalArgumentException(
                    "Selected mirror is not a direct target of the resolved source");
        if (Objects.equals(id, selectedMirror) && create == createMirror)
            return;
        if (id == null && !create)
            target = graph.target(graph.routing().source());
        var client = Minecraft.getInstance();
        boolean active = DownloadManager.isCaptureEnabled();
        boolean restart = preparingDownload || hasDownloadPreparationReview();
        if (DownloadManager.session() != null
                && (target == null || !target.nodeId().equals(DownloadManager.session().id()))) {
            boolean safeToSample = DownloadManager.sessionIsCurrent(client);
            if (safeToSample)
                DownloadCapture.finishPendingBlockChanges(client);
            DownloadManager.endCaptureSession(safeToSample);
        } else if (DownloadManager.session() == null) {
            DownloadManager.advanceSourceEpoch();
        }
        selectedMirror = id;
        createMirror = create;
        String selection = create ? "new" : (id == null ? "default" : "explicit");
        WMLogger.info("Session mirror choice changed selection=" + selection
                + (target == null ? "" : " world=" + target.folder()));
        if (restart)
            cancelRouteRequest();
        if ((active || restart) && !DownloadManager.isCaptureEnabled())
            requestDownloadStart(client, "mirror-selection");
    }

    static void requestDownloadStart(Minecraft client, String reason) {
        if (DownloadManager.isCaptureEnabled() || preparingDownload || client.level == null
                || !WorldGraphService.instance().routing().observationIsCurrent(client))
            return;
        preparingDownload = true;
        WMLogger.debug("Download preparation started reason=" + reason);
        WMPlayerMessages.sendOverlayMessage(
                client.player, Component.translatable("screen.worldmirror.status.downloadPreparing"));
        requestRoute(client, () -> DownloadManager.activateDownload(client, reason), false);
    }

    static void cancelRouteRequest() {
        routeRequestId++;
        pendingRoute = null;
        preparingDownload = false;
        downloadReview = null;
    }

    static void requestRoute(Minecraft client, Runnable action, boolean manual) {
        if (client.level == null)
            return;
        pendingRoute = new PendingRoute(
                ++routeRequestId, client.getConnection(), action, manual, System.nanoTime(), false);
        continuePendingRoute(client);
    }

    static void continuePendingRoute(Minecraft client) {
        var request = pendingRoute;
        if (request == null || preparingRoute || client.level == null
                || DownloadWorldChanges.isRespawnBoundary())
            return;
        long elapsed = System.nanoTime() - request.requestedAt();
        // Give the HUD and status screen a chance to show preparation even for cached targets.
        if (!request.manual() && elapsed < PREPARING_NOTICE_NS)
            return;
        var graph = WorldGraphService.instance();
        if (request.connection() != client.getConnection() || !graph.routing().observationIsCurrent(client)) {
            cancelRouteRequest();
            return;
        }
        if (!graph.available())
            return;
        if (graph.routing().needsLocalIdentity(client)) {
            assignLocalIdentity(client, request, graph);
            return;
        }
        var resolution = graph.routing().currentResolution();
        if (resolution.state() == State.RESOLVED) {
            pendingRoute = null;
            requestOutputReady(client, OutputOrigin.current(client), request.action(), request.manual());
            return;
        }
        if (resolution.state() == State.WAITING_ID && !request.manual()
                && elapsed < RemoteWorldBoundary.DISCOVERY_NS)
            return;
        if (resolution.state() == State.WAITING_ID)
            graph.routing().finishDiscovery(graph.routing().observation());
        awaitSourceChoice(client, request, graph);
    }

    private static void awaitSourceChoice(Minecraft client, PendingRoute request, WorldGraphService graph) {
        var observation = graph.routing().observation();
        var resolution = graph.routing().currentResolution();
        if (observation == null || observation.endpoint() == null) {
            cancelRouteRequest();
            return;
        }
        boolean canChoose = !graph.routing().strictlyRequiresIdentity();
        if (!canChoose || !request.manual()) {
            // An arriving ID or an explicit source choice can resume this action without interrupting play.
            if (!request.announced()) {
                pendingRoute = request.announcedNow();
                DownloadPreparationPrompts.waitForSource(client, canChoose, resolution);
            }
            return;
        }
        pendingRoute = null;
        WMLogger.info("Manual action requires a download source choice state=" + resolution.state()
                + " choices=" + resolution.candidateSourceIds().size());
        openSourceChooser(client, observation, resolution, (id, choice) -> {
            if (request.id() != routeRequestId || request.connection() != client.getConnection()
                    || graph.routing().chooseSource(observation, id, choice) == null)
                return;
            requestRoute(client, request.action(), request.manual());
        });
    }

    private static void assignLocalIdentity(Minecraft client, PendingRoute request, WorldGraphService graph) {
        preparingRoute = true;
        manualExportChecking = request.manual();
        graph.routing().requireLinkedSource(client).whenComplete((id, failure) -> {
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
    }

    private static void openSourceChooser(Minecraft client, ConnectionObservation observation,
            WorldSourceResolver.Resolution resolution,
            java.util.function.BiConsumer<String, Choice> selected) {
        var routing = WorldGraphService.instance().routing();
        CaptureRouteScreen.open(ClientDialogs.currentScreen(client), observation.endpoint(),
                resolution.candidateSourceIds(), routing.addressSource(observation) != null, selected);
    }

    private static void requestOutputReady(
            Minecraft client, OutputOrigin origin, Runnable onReady, boolean manualExport) {
        if (!origin.matches(client) || preparingRoute)
            return;
        var graph = WorldGraphService.instance();
        var observation = origin.source();
        var preparedSession = DownloadManager.isCaptureEnabled() ? DownloadManager.session() : null;
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
                            DownloadManager.acceptTarget(
                                    client, origin.observation().connectionEpoch(), target);
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
            return new OutputOrigin(graph.routing().source(), graph.routing().observation(),
                    graph.routing().currentResolution().sourceNodeId(), DownloadManager.sourceEpoch(),
                    routeRequestId, client.getConnection());
        }

        boolean matchesRoute() {
            var graph = WorldGraphService.instance();
            return source != null && source == graph.routing().source()
                    && epoch == DownloadManager.sourceEpoch() && requestId == routeRequestId
                    && graph.routing().isSameWorld(observation)
                    && Objects.equals(sourceNodeId, graph.routing().currentResolution().sourceNodeId());
        }

        boolean matches(Minecraft client) {
            return matchesRoute() && connection == client.getConnection()
                    && WorldGraphService.instance().routing().observationIsCurrent(client);
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
        if (client.level == null || !WorldGraphService.instance().routing().observationIsCurrent(client))
            return;
        preparingDownload = true;
        requestRoute(client, onReady, false);
    }

    private static void showOutputProblem(Minecraft client, OutputOrigin origin,
            WorldGraphService.Target target, Runnable onReady, MirrorMigrationPlan.Inspection plan,
            boolean showDialog, boolean manualExport) {
        if (plan == null) {
            DownloadPreparationPrompts.alert(client, showDialog,
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody", "inspection_failed"));
            return;
        }
        switch (plan.state()) {
            case OUTDATED -> {
                WMLogger.info("Mirror output awaiting upgrade approval world=" + target.folder()
                        + " generator=" + plan.worldgenWork() + " terrain=" + plan.terrainWork());
                var prompt = DownloadPreparationPrompts.upgrade(target, plan);
                if (showDialog)
                    ClientDialogs.confirm(client, prompt,
                            ()
                                    -> migrateOutputAndContinue(
                                            client, origin, target, onReady, manualExport),
                            () -> {});
                else if (!manualExport) {
                    downloadReview = new PreparationReview(origin, target, onReady, plan);
                    ClientDialogs.toast(client, prompt.title(),
                            new MirrorPrompt.Text("screen.worldmirror.status.downloadReviewHint"));
                } else {
                    ClientDialogs.toast(client, prompt.title(), prompt.message());
                }
            }
            case FUTURE -> {
                DownloadPreparationPrompts.future(client, target, showDialog);
            }
            default -> {
                DownloadPreparationPrompts.unavailable(client, target, plan, showDialog);
            }
        }
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
}
