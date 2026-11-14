package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadCapture;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.DownloadRoute;
import io.github.billstark001.worldmirror.download.ExportStatusStore;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.ui.map.ChunkMapScreen;
import io.github.billstark001.worldmirror.ui.map.ChunkStatusCache;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Native status UI; rendering and screen-installation differences live in StatusScreenApi. */
@Environment(EnvType.CLIENT)
public final class StatusScreen extends WorldMirrorTabbedScreen {
    private static final int MAX_PANEL_WIDTH = 360;
    static final int BUTTON_HEIGHT = 20;
    private static int activeTab;

    private final boolean viewOpenedSave;
    private boolean lastExportState;
    private Button toggleButton;
    private Button exportButton;
    private boolean sourceChoiceAvailable;
    private final StatusSettingsTab settings = new StatusSettingsTab(this);
    private final StatusMirrorMetrics metrics = new StatusMirrorMetrics(this);
    private boolean conflictOperationInProgress;
    private StatusContext statusContext;

    record StatusContext(WorldGraphService.Source observation, String sourceNodeId, Path output,
            MirrorTarget target, boolean currentWorldIsMirror) {
        String mirrorNodeId() {
            return target == null ? null : target.nodeId();
        }
    }

    private StatusScreen(boolean viewOpenedSave) {
        super(Component.translatable("screen.worldmirror.status.title"));
        this.viewOpenedSave = viewOpenedSave;
        lastExportState = DownloadManager.isExportInProgress();
    }

    @Override
    protected void init() {
        statusContext = resolveStatusContext(Minecraft.getInstance());
        sourceChoiceAvailable = DownloadRoute.canChooseDownloadSource();
        metrics.updateTarget(statusContext.target());
        toggleButton = null;
        exportButton = null;
        settings.resetControls();
        int left = left();
        initTabs(List.of(Component.translatable("screen.worldmirror.tab.status"),
                         Component.translatable("screen.worldmirror.tab.settings"),
                         Component.translatable("screen.worldmirror.tab.conflicts")),
                activeTab, true, this::switchTab);

        switch (activeTab) {
            case 0 -> addStatusButtons(left);
            case 1 -> settings.addControls(left);
            case 2 -> addConflictButtons(left);
            default -> activeTab = 0;
        }
        addMirrorControls();
    }

    private void addMirrorControls() {
        List<Button> buttons = new ArrayList<>(2);
        int footerWidth = Math.min(124, Math.max(70, (width - 32) / 3));
        boolean canViewOpened = ChunkStatusCache.openedTarget(Minecraft.getInstance()) != null;
        if (canViewOpened) {
            buttons.add(
                    Button.builder(Component.translatable(statusContext.currentWorldIsMirror()
                                                   ? "screen.worldmirror.status.viewDestination"
                                                   : "screen.worldmirror.status.viewOpened"),
                                  button -> showScreen(new StatusScreen(!viewOpenedSave)))
                            .width(footerWidth)
                            .build());
        } else if (sourceChoiceAvailable) {
            buttons.add(
                    Button.builder(Component.translatable("screen.worldmirror.captureRoute.chooseSource"),
                                  button -> DownloadRoute.chooseDownloadSource())
                            .width(footerWidth)
                            .build());
        }
        buttons.add(Button.builder(Component.translatable("gui.done"), button -> onClose())
                        .width(Math.min(100, footerWidth))
                        .build());
        initFooter(buttons.toArray(Button[] ::new));
    }

    static boolean canChooseMirror() {
        return !DownloadManager.hasManualExport();
    }

    @Override
    public boolean isPauseScreen() {
        return !DownloadManager.hasManualExport();
    }

    @Override
    public void tick() {
        super.tick();
        boolean export = DownloadManager.isExportInProgress();
        if (exportButton != null)
            exportButton.active = !DownloadRoute.isManualExportChecking();
        settings.updateChooseMirror();
        if (!hasSelectionPopup()
                && (sourceChoiceAvailable != DownloadRoute.canChooseDownloadSource()
                        || !resolveStatusContext(Minecraft.getInstance()).equals(statusContext))) {
            refresh();
            return;
        }
        boolean exportFinished = lastExportState && !export;
        if (!hasSelectionPopup() || export)
            lastExportState = export;
        if (toggleButton != null)
            toggleButton.setMessage(downloadActionLabel());
        if (exportFinished && !hasSelectionPopup()) {
            refresh();
            return;
        }
        metrics.poll();
    }

    @Override
    protected void renderContent(Canvas graphics) {
        switch (activeTab) {
            case 0 -> renderStatus(graphics);
            case 1 -> settings.render(graphics);
            case 2 -> renderConflicts(graphics);
            default -> {
            }
        }
    }

    private void addStatusButtons(int left) {
        toggleButton = addRenderableWidget(
                Button.builder(downloadActionLabel(), button -> {
                          if (DownloadRoute.hasDownloadPreparationReview())
                              DownloadRoute.reviewDownloadPreparation();
                          else
                              DownloadManager.toggle(Minecraft.getInstance());
                      }).bounds(left, contentY(88), columnWidth(), BUTTON_HEIGHT).build());
        exportButton = addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.exportNow"), button -> {
                          DownloadManager.exportNow(Minecraft.getInstance());
                          button.active = !DownloadRoute.isManualExportChecking();
                      }).bounds(right(), contentY(88), columnWidth(), BUTTON_HEIGHT).build());
        exportButton.active = !DownloadRoute.isManualExportChecking();
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.clearData"), button -> {
                          DownloadCapture.clearAll(Minecraft.getInstance());
                          refresh();
                      }).bounds(left, contentY(112), columnWidth(), BUTTON_HEIGHT).build());
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.exportNearby"),
                              button -> ExportNearbyScreen.open(this))
                        .bounds(right(), contentY(112), columnWidth(), BUTTON_HEIGHT)
                        .build());
    }

    private static Component downloadActionLabel() {
        if (DownloadRoute.hasDownloadPreparationReview()) {
            return Component.translatable("screen.worldmirror.status.reviewDownloadPreparation");
        }
        boolean active = DownloadManager.isCaptureEnabled() || DownloadRoute.isDownloadPreparing();
        return Component.translatable(active ? "screen.worldmirror.status.stopDownload"
                                             : "screen.worldmirror.status.startDownload");
    }

    private void addConflictButtons(int left) {
        MirrorTarget target = statusContext.target();
        if (metrics.conflictCount() > 0 && ConflictActions.canResolve(target)) {
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.worldmirror.status.overwriteAll"),
                                  button -> resolveAllConflicts(target, true))
                            .bounds(left, contentY(56), columnWidth(), BUTTON_HEIGHT)
                            .build());
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.worldmirror.status.discardAll"),
                                  button -> resolveAllConflicts(target, false))
                            .bounds(right(), contentY(56), columnWidth(), BUTTON_HEIGHT)
                            .build());
        }
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.openChunkMap"),
                              button -> ChunkMapScreen.open(viewOpenedSave))
                        .bounds(left, contentY(90), panelWidth(), BUTTON_HEIGHT)
                        .build());
    }

    private void renderStatus(Canvas graphics) {
        Path output = statusContext.output();
        String folder = !statusContext.currentWorldIsMirror() && DownloadRoute.isNewMirrorSelected()
                ? Component.translatable("screen.worldmirror.status.newMirror").getString()
                : output == null ? "—"
                                 : output.getFileName().toString();
        var observation = statusContext.observation();
        String label = observation == null ? "—" : observation.label();
        int x = left();
        pairLine(graphics, "screen.worldmirror.status.sourceId", label,
                "screen.worldmirror.status.mirrorPath", folder, x, contentY(10));
        int savedCount = ChunkStatusCache.getOrScheduleSavedCount(statusContext.target(), 1_000L);
        var live = DownloadManager.liveCaptureTarget();
        boolean hasLive = live != null && live.nodeId().equals(statusContext.mirrorNodeId());
        pairLine(graphics, "screen.worldmirror.status.savedChunks",
                savedCount < 0 ? "…" : String.valueOf(savedCount), "screen.worldmirror.status.chunks",
                hasLive ? String.valueOf(ChunkListener.getTotalCount()) : "—", x, contentY(24));
        pairLine(graphics, "screen.worldmirror.status.pendingWrite",
                hasLive ? String.valueOf(ChunkListener.getDirtyCount()) : "—",
                "screen.worldmirror.status.lastSync", metrics.lastSync().getString(), x, contentY(38));
        if (!statusContext.currentWorldIsMirror() || hasLive || DownloadRoute.isDownloadPreparing()
                || DownloadRoute.hasDownloadPreparationReview()) {
            Component state = Component.translatable(DownloadManager.captureStatusKey());
            graphics.text(font.width(state) > columnWidth()
                            ? Component.literal(font.plainSubstrByWidth(state.getString(), columnWidth()))
                            : state,
                    x, contentY(52), 0xFFE0E0E0);
        }
        var health = statusContext.target() == null
                ? null
                : ExportStatusStore.global().health(statusContext.mirrorNodeId());
        boolean writing = health != null && health.counts().writing() > 0;
        graphics.text(Component.translatable(writing || hasLive && DownloadManager.hasManualExport()
                                      ? "screen.worldmirror.status.exportRunning"
                                      : "screen.worldmirror.status.exportIdle"),
                right(), contentY(52), 0xFFE0E0E0);
        if (health != null) {
            graphics.text(ExportStatusText.health(health), x, contentY(76),
                    health.degraded() ? 0xFFFF6060 : 0xFFE0E0E0);
            if (health.degraded())
                graphics.text(ExportStatusText.stage(health), x, contentY(138), 0xFFFF6060);
        }
        MirrorWorldContext.Snapshot mirror = MirrorWorldContext.current();
        if (statusContext.currentWorldIsMirror() && mirror.isMirror()) {
            graphics.centered(Component.translatable("screen.worldmirror.status.currentMirror."
                                      + mirror.state().name().toLowerCase()),
                    width / 2, contentY(66), 0xFFFFFF55);
        }
    }

    private void renderConflicts(Canvas graphics) {
        int conflictCount = metrics.conflictCount();
        graphics.centered(Component.translatable("screen.worldmirror.tab.conflictsHeader"), width / 2,
                contentY(14), 0xFFE0E0E0);
        if (conflictCount == 0) {
            graphics.centered(Component.translatable("screen.worldmirror.status.noConflicts"), width / 2,
                    contentY(36), 0xFFE0E0E0);
        } else {
            line(graphics, "screen.worldmirror.status.conflicts",
                    conflictCount < 0 ? "?" : String.valueOf(conflictCount), left(), contentY(36));
        }
    }

    void line(Canvas graphics, String key, String value, int x, int y) {
        graphics.text(valueLine(key, value, panelWidth()), x, y, 0xFFE0E0E0);
    }

    private void pairLine(Canvas graphics, String leftKey, String leftValue, String rightKey,
            String rightValue, int x, int y) {
        graphics.text(valueLine(leftKey, leftValue, columnWidth()), x, y, 0xFFE0E0E0);
        graphics.text(valueLine(rightKey, rightValue, columnWidth()), right(), y, 0xFFE0E0E0);
    }

    private Component valueLine(String key, String value, int maximumWidth) {
        Component prefix = Component.translatable(key).append(": ");
        int valueWidth = maximumWidth - font.width(prefix);
        if (font.width(value) > valueWidth) {
            value = font.plainSubstrByWidth(value, Math.max(0, valueWidth - font.width("…"))) + "…";
        }
        return prefix.copy().append(value);
    }

    private void switchTab(int tab) {
        activeTab = tab;
        settings.clearMessage();
        metrics.requestRefresh();
        rebuildWidgets();
    }

    int contentY(int offset) {
        return tabContentTop() + offset;
    }

    int left() {
        return (width - panelWidth()) / 2;
    }

    private int right() {
        return left() + columnWidth() + 4;
    }

    private int columnWidth() {
        return (panelWidth() - 4) / 2;
    }

    int panelWidth() {
        return Math.min(MAX_PANEL_WIDTH, width - 24);
    }

    private void resolveAllConflicts(MirrorTarget target, boolean overwrite) {
        var client = Minecraft.getInstance();
        if (conflictOperationInProgress || !target.equals(resolveStatusContext(client).target()))
            return;
        conflictOperationInProgress = true;
        ConflictActions.resolveAll(target, overwrite, () -> {
            conflictOperationInProgress = false;
            if (ClientDialogs.currentScreen(client) == this) {
                metrics.requestRefresh();
                refresh();
            }
        });
    }

    StatusContext resolveStatusContext(Minecraft client) {
        WorldGraphService service = WorldGraphService.instance();
        var observation = service.routing().source();
        var statusTarget =
                viewOpenedSave ? ChunkStatusCache.targetFor(client) : ChunkStatusCache.downloadTarget(client);
        if (statusTarget != null) {
            var target = statusTarget.mirror();
            String parent = statusTarget.currentWorldIsMirror()
                    ? MirrorWorldContext.current().metadata().parentNodeId
                    : service.routing().sourceNode(observation);
            return new StatusContext(
                    observation, parent, target.folder(), target, statusTarget.currentWorldIsMirror());
        }
        boolean current = viewOpenedSave && MirrorWorldContext.current().isMirror();
        return new StatusContext(observation, service.routing().sourceNode(observation),
                service.preview(observation), null, current);
    }

    StatusContext statusContext() {
        return statusContext;
    }

    boolean isConflictsTab() {
        return activeTab == 2;
    }

    Button addControl(Button button) {
        return addRenderableWidget(button);
    }

    void refresh() {
        rebuildWidgets();
    }

    public static void open() {
        showScreen(new StatusScreen(true));
    }
}
