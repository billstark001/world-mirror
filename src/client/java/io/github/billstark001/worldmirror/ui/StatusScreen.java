package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.EnumDropdownGui;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.ExportStatusStore;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.OperationFailures;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.ui.map.ChunkMapScreen;
import io.github.billstark001.worldmirror.ui.map.ChunkStatusCache;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Native status UI; rendering and screen-installation differences live in StatusScreenApi. */
@Environment(EnvType.CLIENT)
public final class StatusScreen extends WorldMirrorTabbedScreen {
    private static final int MAX_PANEL_WIDTH = 360;
    private static final int BUTTON_HEIGHT = 20;
    private static int activeTab;

    private final boolean viewOpenedSave;
    private boolean lastExportState;
    private Button toggleButton;
    private Button exportButton;
    private Button chooseMirrorButton;
    private boolean sourceChoiceAvailable;
    private Component settingsFailure;
    private boolean settingsSucceeded;
    private boolean savingRouting;
    private long lastSyncTime;
    private long nextStatusReadMs;
    private int conflictCount;
    private boolean readingStatus;
    private MirrorTarget statusReadTarget;
    private StatusContext statusContext;

    private record StatusContext(WorldGraphService.Source observation, String sourceNodeId, Path output,
            MirrorTarget target, boolean currentWorldIsMirror) {
        String mirrorNodeId() {
            return target == null ? null : target.nodeId();
        }
    }

    protected StatusScreen() {
        this(true);
    }

    private StatusScreen(boolean viewOpenedSave) {
        super(Component.translatable("screen.worldmirror.status.title"));
        this.viewOpenedSave = viewOpenedSave;
        lastExportState = DownloadManager.isExportInProgress();
    }

    @Override
    protected void init() {
        statusContext = resolveStatusContext(Minecraft.getInstance());
        sourceChoiceAvailable = DownloadManager.canChooseDownloadSource();
        updateStatusReadTarget();
        toggleButton = null;
        exportButton = null;
        chooseMirrorButton = null;
        int left = left();
        initTabs(List.of(Component.translatable("screen.worldmirror.tab.status"),
                         Component.translatable("screen.worldmirror.tab.settings"),
                         Component.translatable("screen.worldmirror.tab.conflicts")),
                activeTab, true, this::switchTab);

        switch (activeTab) {
            case 0 -> addStatusButtons(left);
            case 1 -> addSettingsButtons(left);
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
                                  button -> DownloadManager.chooseDownloadSource())
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
            exportButton.active = !DownloadManager.isManualExportChecking();
        if (chooseMirrorButton != null)
            chooseMirrorButton.active = mirrorSource() != null && canChooseMirror();
        if (!hasSelectionPopup()
                && (sourceChoiceAvailable != DownloadManager.canChooseDownloadSource()
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
        pollPersistentStatus();
    }

    @Override
    protected void renderContent(Canvas graphics) {
        switch (activeTab) {
            case 0 -> renderStatus(graphics);
            case 1 -> renderSettings(graphics);
            case 2 -> renderConflicts(graphics);
            default -> {
            }
        }
    }

    private void addStatusButtons(int left) {
        toggleButton = addRenderableWidget(
                Button.builder(downloadActionLabel(), button -> {
                          if (DownloadManager.hasDownloadPreparationReview())
                              DownloadManager.reviewDownloadPreparation();
                          else
                              DownloadManager.toggle(Minecraft.getInstance());
                      }).bounds(left, contentY(88), columnWidth(), BUTTON_HEIGHT).build());
        exportButton = addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.exportNow"), button -> {
                          DownloadManager.exportNow(Minecraft.getInstance());
                          button.active = !DownloadManager.isManualExportChecking();
                      }).bounds(right(), contentY(88), columnWidth(), BUTTON_HEIGHT).build());
        exportButton.active = !DownloadManager.isManualExportChecking();
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.clearData"), button -> {
                          DownloadManager.clearAll(Minecraft.getInstance());
                          refresh();
                      }).bounds(left, contentY(112), columnWidth(), BUTTON_HEIGHT).build());
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.exportNearby"),
                              button -> ExportNearbyScreen.open(this))
                        .bounds(right(), contentY(112), columnWidth(), BUTTON_HEIGHT)
                        .build());
    }

    private void addSettingsButtons(int left) {
        WorldGraphService service = WorldGraphService.instance();
        ModConfig.SaveLocation saveLocation = resolveSaveLocation();
        ModConfig.ConflictStrategy strategy =
                service.conflictStrategy(statusContext.mirrorNodeId(), statusContext.sourceNodeId());
        Button location = addEnumDropdown(left, contentY(58), WorldLabels.LOCATION, saveLocation,
                ModConfig.SaveLocation.values(), WorldLabels::location, this::selectSaveLocation);
        location.active = service.available() && statusContext.sourceNodeId() != null
                && !statusContext.currentWorldIsMirror();
        Button conflict = addEnumDropdown(left, contentY(80), "screen.worldmirror.status.conflictStrategy",
                strategy, ModConfig.ConflictStrategy.values(),
                value
                -> enumLabel("config.worldmirror.conflictStrategy", value),
                this::selectConflictStrategy);
        conflict.active = service.available()
                && (statusContext.sourceNodeId() != null || statusContext.target() != null);
        int settingWidth = (panelWidth() - 8) / 3;
        boolean canSync = canSyncWorldSettings();
        Button syncTime = addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.syncTime"),
                              button -> syncWorldSetting(WorldStructureCreator.Setting.TIME))
                        .bounds(left, contentY(102), settingWidth, BUTTON_HEIGHT)
                        .build());
        Button syncWeather = addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.syncWeather"),
                              button -> syncWorldSetting(WorldStructureCreator.Setting.WEATHER))
                        .bounds(left + settingWidth + 4, contentY(102), settingWidth, BUTTON_HEIGHT)
                        .build());
        Button syncDifficulty = addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.syncDifficulty"),
                              button -> syncWorldSetting(WorldStructureCreator.Setting.DIFFICULTY))
                        .bounds(left + (settingWidth + 4) * 2, contentY(102), settingWidth, BUTTON_HEIGHT)
                        .build());
        syncTime.active = canSync;
        syncWeather.active = canSync;
        syncDifficulty.active = canSync;
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.openSettings"), button -> {
                          EnumDropdownGui.register();
                          showScreen(AutoConfigClient.getConfigScreen(ModConfig.class, this).get());
                      }).bounds(left, contentY(124), settingWidth, BUTTON_HEIGHT).build());
        chooseMirrorButton = addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.chooseMirror"), button -> {
                          String source = mirrorSource();
                          if (source != null && canChooseMirror())
                              DownloadMirrorSelectionScreen.open(this, source);
                      }).bounds(left + settingWidth + 4, contentY(124), settingWidth, BUTTON_HEIGHT).build());
        chooseMirrorButton.active = mirrorSource() != null && canChooseMirror();
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.routing.title"),
                              button -> WorldRoutingScreen.open(this))
                        .bounds(left + (settingWidth + 4) * 2, contentY(124), settingWidth, BUTTON_HEIGHT)
                        .build());
    }

    private static String mirrorSource() {
        return WorldGraphService.instance().currentResolution().sourceNodeId();
    }

    private static Component downloadActionLabel() {
        if (DownloadManager.hasDownloadPreparationReview()) {
            return Component.translatable("screen.worldmirror.status.reviewDownloadPreparation");
        }
        boolean active = DownloadManager.isCaptureEnabled() || DownloadManager.isDownloadPreparing();
        return Component.translatable(active ? "screen.worldmirror.status.stopDownload"
                                             : "screen.worldmirror.status.startDownload");
    }

    private <T extends Enum<T>> Button addEnumDropdown(int x, int y, String labelKey, T current, T[] values,
            Function<T, Component> valueLabel, Consumer<T> onSelect) {
        var options = Arrays.stream(values)
                              .map(value
                                      -> new SelectionOption(valueLabel.apply(value), value == current,
                                              () -> onSelect.accept(value)))
                              .toList();
        return addRenderableWidget(
                Button.builder(selectionLabel(selectionValue(labelKey, valueLabel.apply(current))),
                              button -> openSelectionPopup(button, options))
                        .bounds(x, y, panelWidth(), BUTTON_HEIGHT)
                        .build());
    }

    private void selectSaveLocation(ModConfig.SaveLocation target) {
        if (!canChangeRouting())
            return;
        if (target == resolveSaveLocation()) {
            refresh();
        } else if (statusContext.target() != null) {
            showScreen(new SaveLocationMoveScreen(this, statusContext.target(), target));
        } else {
            var observation = statusContext.observation();
            saveRouting(
                    "save_location", () -> WorldGraphService.instance().setSaveLocation(observation, target));
        }
    }

    private void selectConflictStrategy(ModConfig.ConflictStrategy strategy) {
        if (!canChangeRouting())
            return;
        var context = statusContext;
        saveRouting("conflict_strategy",
                ()
                        -> WorldGraphService.instance().setConflictStrategy(
                                context.mirrorNodeId(), context.sourceNodeId(), strategy));
    }

    private boolean canChangeRouting() {
        if (DownloadManager.isCaptureEnabled() || DownloadManager.isDownloadPreparing()
                || DownloadManager.hasDownloadPreparationReview()) {
            refreshWithSettingsResult(
                    Component.translatable("screen.worldmirror.move.failure.download_active"), false);
            return false;
        }
        if (DownloadManager.isExportInProgress()) {
            refreshWithSettingsResult(
                    Component.translatable("screen.worldmirror.move.failure.export_in_progress"), false);
            return false;
        }
        if (savingRouting)
            return false;
        if (!statusContext.equals(resolveStatusContext(Minecraft.getInstance()))) {
            refresh();
            return false;
        }
        return true;
    }

    @FunctionalInterface
    private interface RoutingChange {
        void run() throws java.io.IOException;
    }

    private void saveRouting(String operation, RoutingChange change) {
        var context = statusContext;
        var client = Minecraft.getInstance();
        savingRouting = true;
        OfflineTasks.start("WM-ChangeRouting",
                ()
                        -> {
                    change.run();
                    return true;
                },
                failure
                -> {
                    String scope =
                            context.mirrorNodeId() != null ? context.mirrorNodeId() : context.sourceNodeId();
                    OperationFailures.report(scope, operation, context.output(), failure);
                    return false;
                },
                client::execute,
                success
                -> {
                    savingRouting = false;
                    if (ClientDialogs.currentScreen(client) != this)
                        return;
                    if (success)
                        refresh();
                    else
                        refreshWithSettingsResult(
                                Component.translatable("screen.worldmirror.move.failure.io_error"), false);
                },
                () -> {});
    }

    private static Component enumLabel(String prefix, Enum<?> value) {
        return Component.translatable(prefix + "." + value.name().toLowerCase(Locale.ROOT));
    }

    private void addConflictButtons(int left) {
        MirrorTarget target = statusContext.target();
        if (conflictCount > 0 && ConflictActions.canResolve(target)) {
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
        String folder = !statusContext.currentWorldIsMirror() && DownloadManager.isNewMirrorSelected()
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
                "screen.worldmirror.status.lastSync", lastSync().getString(), x, contentY(38));
        if (!statusContext.currentWorldIsMirror() || hasLive || DownloadManager.isDownloadPreparing()
                || DownloadManager.hasDownloadPreparationReview()) {
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

    private void renderSettings(Canvas graphics) {
        graphics.centered(Component.translatable("screen.worldmirror.tab.settingsHeader"), width / 2,
                contentY(14), 0xFFE0E0E0);
        line(graphics, "screen.worldmirror.status.outputPath",
                statusContext.output() == null ? "—" : statusContext.output().toString(), left(),
                contentY(28));
        line(graphics, "screen.worldmirror.status.xaeroOverlay", bridgeStatus().getString(), left(),
                contentY(42));
        if (settingsFailure != null) {
            graphics.centered(
                    settingsFailure, width / 2, contentY(52), settingsSucceeded ? 0xFF55FF55 : 0xFFFF5555);
        }
    }

    private boolean canSyncWorldSettings() {
        return WorldGraphService.instance().available() && Minecraft.getInstance().level != null
                && !statusContext.currentWorldIsMirror() && statusContext.target() != null
                && !DownloadManager.isCaptureEnabled() && !DownloadManager.isDownloadPreparing()
                && !DownloadManager.hasDownloadPreparationReview() && !DownloadManager.isExportInProgress();
    }

    private void syncWorldSetting(WorldStructureCreator.Setting setting) {
        Minecraft client = Minecraft.getInstance();
        if (!canSyncWorldSettings() || !statusContext.equals(resolveStatusContext(client))) {
            settingsSucceeded = false;
            settingsFailure = Component.translatable("screen.worldmirror.status.syncSettingUnavailable");
            return;
        }
        WorldSettingsSnapshot snapshot = WorldStructureCreator.captureWorldSettings(client.level);
        MirrorTarget target = statusContext.target();
        OfflineTasks.start("WM-SyncWorldSetting",
                ()
                        -> WorldStructureCreator.syncWorldSetting(target, snapshot, setting),
                failure
                -> {
                    OperationFailures.report(target.nodeId(), "setting_sync", target.folder(), failure);
                    return false;
                },
                client::execute,
                succeeded
                -> {
                    if (!target.equals(resolveStatusContext(client).target()))
                        return;
                    settingsSucceeded = succeeded;
                    settingsFailure =
                            Component.translatable(succeeded ? "screen.worldmirror.status.syncSettingDone"
                                                             : "screen.worldmirror.status.syncSettingFailed");
                },
                () -> {});
    }

    private void renderConflicts(Canvas graphics) {
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

    private void line(Canvas graphics, String key, String value, int x, int y) {
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
        settingsFailure = null;
        nextStatusReadMs = 0L;
        rebuildWidgets();
    }

    private int contentY(int offset) {
        return tabContentTop() + offset;
    }

    private int left() {
        return (width - panelWidth()) / 2;
    }

    private int right() {
        return left() + columnWidth() + 4;
    }

    private int columnWidth() {
        return (panelWidth() - 4) / 2;
    }

    private int panelWidth() {
        return Math.min(MAX_PANEL_WIDTH, width - 24);
    }

    private static Component bridgeStatus() {
        if (!ModConfig.get().chunkMap.showXaeroWorldMapOverlay) {
            return Component.translatable("screen.worldmirror.status.xaeroOverlay.disabled");
        }
        return FabricLoader.getInstance().isModLoaded("xaero_world_map_bridge")
                ? Component.translatable("screen.worldmirror.status.xaeroOverlay.bridge")
                : Component.translatable("screen.worldmirror.status.xaeroOverlay.missing");
    }

    private boolean conflictOperationInProgress;

    private void resolveAllConflicts(MirrorTarget target, boolean overwrite) {
        var client = Minecraft.getInstance();
        if (conflictOperationInProgress || !target.equals(resolveStatusContext(client).target()))
            return;
        conflictOperationInProgress = true;
        ConflictActions.resolveAll(target, overwrite, () -> {
            conflictOperationInProgress = false;
            if (ClientDialogs.currentScreen(client) == this) {
                nextStatusReadMs = 0L;
                refresh();
            }
        });
    }

    private record PersistentStatus(long lastSyncTime, int conflicts) {}

    private void updateStatusReadTarget() {
        MirrorTarget target = statusContext.target();
        if (!java.util.Objects.equals(target, statusReadTarget)) {
            statusReadTarget = target;
            lastSyncTime = target == null ? 0L : -1L;
            conflictCount = target == null ? 0 : -1;
            nextStatusReadMs = 0L;
        }
        pollPersistentStatus();
    }

    private void pollPersistentStatus() {
        MirrorTarget target = statusReadTarget;
        long now = System.nanoTime() / 1_000_000L;
        if (target == null || readingStatus || (nextStatusReadMs != 0L && now - nextStatusReadMs < 0L)
                || hasSelectionPopup())
            return;
        var client = Minecraft.getInstance();
        boolean readConflicts = activeTab == 2;
        readingStatus = true;
        nextStatusReadMs = now + (readConflicts ? 2_000L : 1_000L);
        OfflineTasks.start("WM-ReadMirrorStatus",
                ()
                        -> {
                    var metadata = WorldMetadata.loadIfPresent(target.folder()).filter(target::matches);
                    if (metadata.isEmpty()) {
                        boolean exists =
                                Files.isRegularFile(target.folder().resolve(WorldMetadata.FILE_NAME));
                        return new PersistentStatus(exists ? -1L : 0L, exists ? -1 : 0);
                    }
                    int count = -1;
                    if (readConflicts) {
                        count = ConflictManager.countAllConflicts(target.folder());
                        if (!target.matchesSavedIdentity())
                            return new PersistentStatus(-1L, -1);
                        OperationFailures.recovered(target.nodeId(), "conflict_read");
                    }
                    return new PersistentStatus(metadata.get().lastSyncTime, count);
                },
                failure
                -> {
                    OperationFailures.report(target.nodeId(), "conflict_read", target.folder(), failure);
                    return new PersistentStatus(-1L, -1);
                },
                client::execute,
                status
                -> {
                    if (ClientDialogs.currentScreen(client) != this || !target.equals(statusReadTarget))
                        return;
                    lastSyncTime = status.lastSyncTime();
                    if (readConflicts && activeTab == 2 && !hasSelectionPopup()) {
                        boolean changedActions = (conflictCount > 0) != (status.conflicts() > 0);
                        conflictCount = status.conflicts();
                        if (changedActions)
                            rebuildWidgets();
                    }
                },
                () -> readingStatus = false);
    }

    private StatusContext resolveStatusContext(Minecraft client) {
        WorldGraphService service = WorldGraphService.instance();
        var observation = service.source();
        var statusTarget =
                viewOpenedSave ? ChunkStatusCache.targetFor(client) : ChunkStatusCache.downloadTarget(client);
        if (statusTarget != null) {
            var target = statusTarget.mirror();
            String parent = statusTarget.currentWorldIsMirror()
                    ? MirrorWorldContext.current().metadata().parentNodeId
                    : service.sourceNode(observation);
            return new StatusContext(
                    observation, parent, target.folder(), target, statusTarget.currentWorldIsMirror());
        }
        boolean current = viewOpenedSave && MirrorWorldContext.current().isMirror();
        return new StatusContext(
                observation, service.sourceNode(observation), service.preview(observation), null, current);
    }

    private Component lastSync() {
        if (lastSyncTime < 0L)
            return Component.literal("?");
        if (lastSyncTime == 0L) {
            return Component.translatable("screen.worldmirror.status.lastSyncNever");
        }
        return formatAge((System.currentTimeMillis() - lastSyncTime) / 1_000L);
    }

    private static Component formatAge(long seconds) {
        if (seconds < 0)
            seconds = 0;
        if (seconds < 60) {
            return Component.translatable("screen.worldmirror.status.age.seconds", seconds);
        }
        if (seconds < 3_600) {
            return Component.translatable("screen.worldmirror.status.age.minutes", seconds / 60);
        }
        return Component.translatable("screen.worldmirror.status.age.hours", seconds / 3_600);
    }

    private ModConfig.SaveLocation resolveSaveLocation() {
        WorldGraphService service = WorldGraphService.instance();
        String nodeId = statusContext.mirrorNodeId();
        var node = nodeId == null ? null : service.graph().nodes().get(nodeId);
        if (node != null && node.location() != null) {
            return ModConfig.SaveLocation.valueOf(node.location().root().name());
        }
        return service.saveLocation(statusContext.sourceNodeId());
    }

    protected void refresh() {
        rebuildWidgets();
    }

    private void refreshWithSettingsResult(Component message, boolean succeeded) {
        settingsFailure = message;
        settingsSucceeded = succeeded;
        refresh();
    }

    public static void open() {
        showScreen(new StatusScreen());
    }
}
