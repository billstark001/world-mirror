package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.platform.InputConstants;
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
import io.github.billstark001.worldmirror.util.OfflineTasks;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Native status UI; rendering and screen-installation differences live in StatusScreenApi. */
@Environment(EnvType.CLIENT)
public class StatusScreen extends StatusScreenApi {
    private static final int PANEL_WIDTH = 360;
    private static final int BUTTON_HEIGHT = 20;
    private static final int DROPDOWN_WIDTH = 220;
    private static final int DROPDOWN_ROW_HEIGHT = 18;
    private static final int DROPDOWN_ROW_GAP = 1;
    private static int activeTab;

    private boolean lastExportState;
    private boolean lastDownloadState;
    private Button toggleButton;
    private Button exportButton;
    private Component settingsFailure;
    private boolean settingsSucceeded;
    private boolean savingRouting;
    private long lastSyncTime;
    private long nextSyncTimeRefreshMs;
    private int conflictCount;
    private StatusContext statusContext;
    private boolean dropdownOpen;
    private Button dropdownOpener;
    private final List<Button> dropdownChoices = new ArrayList<>();

    private record StatusContext(WorldGraphService.Source observation, String sourceNodeId,
                                 Path output, MirrorTarget target, boolean currentWorldIsMirror) {
        String mirrorNodeId() {
            return target == null ? null : target.nodeId();
        }
    }

    protected StatusScreen() {
        super(Component.translatable("screen.worldmirror.status.title"));
        lastExportState = DownloadManager.isExportInProgress();
        lastDownloadState = DownloadManager.isCaptureEnabled();
    }

    @Override
    protected void init() {
        loadPersistentStatus();
        int left = left();
        int tabWidth = (PANEL_WIDTH - 8) / 3;
        addRenderableWidget(Button.builder(tabLabel("screen.worldmirror.tab.status", 0),
                        button -> switchTab(0))
                .bounds(left, 32, tabWidth, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(tabLabel("screen.worldmirror.tab.settings", 1),
                        button -> switchTab(1))
                .bounds(left + tabWidth + 4, 32, tabWidth, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(tabLabel("screen.worldmirror.tab.conflicts", 2),
                        button -> switchTab(2))
                .bounds(left + (tabWidth + 4) * 2, 32, tabWidth, BUTTON_HEIGHT).build());

        switch (activeTab) {
            case 0 -> addStatusButtons(left);
            case 1 -> addSettingsButtons(left);
            case 2 -> addConflictButtons(left);
            default -> activeTab = 0;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        button -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, BUTTON_HEIGHT).build());
    }

    @Override
    public boolean isPauseScreen() {
        return !DownloadManager.hasManualExport();
    }

    @Override
    public void tick() {
        super.tick();
        boolean export = DownloadManager.isExportInProgress();
        boolean downloading = DownloadManager.isCaptureEnabled();
        if (exportButton != null && !dropdownOpen) exportButton.active = !DownloadManager.isManualExportChecking();
        if (!dropdownOpen && !resolveStatusContext(Minecraft.getInstance()).equals(statusContext)) {
            refresh();
            return;
        }
        boolean exportFinished = lastExportState && !export;
        if (export != lastExportState || downloading != lastDownloadState) {
            lastExportState = export;
            lastDownloadState = downloading;
            if (toggleButton != null) {
                toggleButton.setMessage(Component.translatable(downloading
                        ? "screen.worldmirror.status.stopDownload"
                        : "screen.worldmirror.status.startDownload"));
            }
        }
        if (exportFinished) {
            refresh();
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= nextSyncTimeRefreshMs) {
            refreshLastSyncTime();
            nextSyncTimeRefreshMs = now + 1_000L;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (dropdownOpen && input.key() == InputConstants.KEY_ESCAPE) {
            refresh();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (dropdownOpen && !dropdownOpener.isMouseOver(click.x(), click.y())
                && dropdownChoices.stream().noneMatch(
                        choice -> choice.isMouseOver(click.x(), click.y()))) {
            refresh();
            return true;
        }
        boolean handled = super.mouseClicked(click, doubled);
        if (!handled && dropdownOpen) {
            refresh();
            return true;
        }
        return handled;
    }

    @Override
    protected void renderContent(Canvas graphics) {
        graphics.centered(title, width / 2, 12, 0xFFFFFFFF);
        switch (activeTab) {
            case 0 -> renderStatus(graphics);
            case 1 -> renderSettings(graphics);
            case 2 -> renderConflicts(graphics);
            default -> { }
        }
    }

    private void addStatusButtons(int left) {
        toggleButton = addRenderableWidget(Button.builder(Component.translatable(
                        DownloadManager.isCaptureEnabled()
                                ? "screen.worldmirror.status.stopDownload"
                                : "screen.worldmirror.status.startDownload"),
                button -> DownloadManager.toggle(Minecraft.getInstance()))
                .bounds(left, 140, 178, BUTTON_HEIGHT).build());
        exportButton = addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.exportNow"),
                button -> {
                    DownloadManager.exportNow(Minecraft.getInstance());
                    button.active = !DownloadManager.isManualExportChecking();
                })
                .bounds(left + 182, 140, 178, BUTTON_HEIGHT).build());
        exportButton.active = !DownloadManager.isManualExportChecking();
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.clearData"),
                button -> { DownloadManager.clearAll(Minecraft.getInstance()); refresh(); })
                .bounds(left, 164, 178, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.exportNearby"),
                button -> ExportNearbyScreen.open(this))
                .bounds(left + 182, 164, 178, BUTTON_HEIGHT).build());
    }

    private void addSettingsButtons(int left) {
        WorldGraphService service = WorldGraphService.instance();
        ModConfig.SaveLocation saveLocation = resolveSaveLocation();
        ModConfig.ConflictStrategy strategy = service.conflictStrategy(
                statusContext.mirrorNodeId(), statusContext.sourceNodeId());
        Button location = addEnumDropdown(left, 116, "screen.worldmirror.status.saveLoc",
                saveLocation, ModConfig.SaveLocation.values(),
                value -> enumLabel("config.worldmirror.saveLoc", value),
                this::selectSaveLocation);
        location.active = service.available() && statusContext.sourceNodeId() != null && !statusContext.currentWorldIsMirror();
        Button conflict = addEnumDropdown(left, 140, "screen.worldmirror.status.conflictStrategy",
                strategy, ModConfig.ConflictStrategy.values(),
                value -> enumLabel("config.worldmirror.conflictStrategy", value),
                this::selectConflictStrategy);
        conflict.active = service.available() && (statusContext.sourceNodeId() != null || statusContext.target() != null);
        int settingWidth = (PANEL_WIDTH - 8) / 3;
        boolean canSync = canSyncWorldSettings();
        Button syncTime = addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.syncTime"),
                button -> syncWorldSetting(WorldStructureCreator.Setting.TIME))
                .bounds(left, 164, settingWidth, BUTTON_HEIGHT).build());
        Button syncWeather = addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.syncWeather"),
                button -> syncWorldSetting(WorldStructureCreator.Setting.WEATHER))
                .bounds(left + settingWidth + 4, 164, settingWidth, BUTTON_HEIGHT).build());
        Button syncDifficulty = addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.syncDifficulty"),
                button -> syncWorldSetting(WorldStructureCreator.Setting.DIFFICULTY))
                .bounds(left + (settingWidth + 4) * 2, 164, settingWidth, BUTTON_HEIGHT).build());
        syncTime.active = canSync;
        syncWeather.active = canSync;
        syncDifficulty.active = canSync;
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.openSettings"),
                button -> {
                    EnumDropdownGui.register();
                    showScreen(AutoConfigClient.getConfigScreen(ModConfig.class, this).get());
                })
                .bounds(left, 188, PANEL_WIDTH, BUTTON_HEIGHT).build());
    }

    private <T extends Enum<T>> Button addEnumDropdown(
            int x, int y, String labelKey, T current, T[] values,
            Function<T, Component> valueLabel, Consumer<T> onSelect) {
        return addRenderableWidget(Button.builder(
                        dropdownLabel(labelKey, valueLabel.apply(current), false),
                        button -> {
                            if (dropdownOpen) {
                                refresh();
                            } else {
                                openDropdown(button, labelKey, current, values,
                                        valueLabel, onSelect);
                            }
                        })
                .bounds(x, y, PANEL_WIDTH, BUTTON_HEIGHT).build());
    }

    private <T extends Enum<T>> void openDropdown(
            Button opener, String labelKey, T current, T[] values,
            Function<T, Component> valueLabel, Consumer<T> onSelect) {
        dropdownOpen = true;
        dropdownOpener = opener;
        dropdownChoices.clear();
        opener.setMessage(dropdownLabel(labelKey, valueLabel.apply(current), true));
        for (GuiEventListener child : children()) {
            if (child instanceof Button button && button != opener) button.active = false;
        }

        int rowStep = DROPDOWN_ROW_HEIGHT + DROPDOWN_ROW_GAP;
        int listHeight = values.length * rowStep - DROPDOWN_ROW_GAP;
        int listY = opener.getY() + BUTTON_HEIGHT;
        if (listY + listHeight > height - 32) listY = opener.getY() - listHeight;
        int listX = opener.getX() + opener.getWidth() - DROPDOWN_WIDTH;
        Button selected = null;
        for (int index = 0; index < values.length; index++) {
            T value = values[index];
            Component message = value == current
                    ? Component.literal("§e✓ ").append(valueLabel.apply(value))
                    : Component.literal("  ").append(valueLabel.apply(value));
            Button choice = addRenderableWidget(Button.builder(message,
                            button -> onSelect.accept(value))
                    .bounds(listX, listY + index * rowStep,
                            DROPDOWN_WIDTH, DROPDOWN_ROW_HEIGHT)
                    .build());
            dropdownChoices.add(choice);
            if (value == current) selected = choice;
        }
        if (selected != null) setFocused(selected);
    }

    private void selectSaveLocation(ModConfig.SaveLocation target) {
        if (!canChangeRouting()) return;
        if (target == resolveSaveLocation()) {
            refresh();
        } else if (statusContext.target() != null) {
            showScreen(new SaveLocationMoveScreen(this, statusContext.target(), target));
        } else {
            var observation = statusContext.observation();
            saveRouting("save_location", () -> WorldGraphService.instance().setSaveLocation(observation, target));
        }
    }

    private void selectConflictStrategy(ModConfig.ConflictStrategy strategy) {
        if (!canChangeRouting()) return;
        var context = statusContext;
        saveRouting("conflict_strategy", () -> WorldGraphService.instance().setConflictStrategy(
                context.mirrorNodeId(), context.sourceNodeId(), strategy));
    }

    private boolean canChangeRouting() {
        if (DownloadManager.isCaptureEnabled()) {
            refreshWithSettingsResult(Component.translatable(
                    "screen.worldmirror.move.failure.download_active"), false);
            return false;
        }
        if (DownloadManager.isExportInProgress()) {
            refreshWithSettingsResult(Component.translatable(
                    "screen.worldmirror.move.failure.export_in_progress"), false);
            return false;
        }
        if (savingRouting) return false;
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
        OfflineTasks.start("WM-ChangeRouting", () -> {
            change.run();
            return true;
        }, failure -> {
            String scope = context.mirrorNodeId() != null ? context.mirrorNodeId() : context.sourceNodeId();
            OperationFailures.report(scope, operation, context.output(), failure);
            return false;
        }, client::execute, success -> {
            savingRouting = false;
            if (ClientDialogs.currentScreen(client) != this) return;
            if (success) refresh();
            else refreshWithSettingsResult(Component.translatable("screen.worldmirror.move.failure.io_error"), false);
        }, () -> {});
    }

    private static Component dropdownLabel(String labelKey, Component value, boolean open) {
        return Component.translatable(labelKey).append(": ").append(value)
                .append(open ? "  ▲" : "  ▼");
    }

    private static Component enumLabel(String prefix, Enum<?> value) {
        return Component.translatable(prefix + "."
                + value.name().toLowerCase(Locale.ROOT));
    }

    private void addConflictButtons(int left) {
        MirrorTarget target = statusContext.target();
        if (conflictCount > 0 && ConflictActions.canResolve(target)) {
            addRenderableWidget(Button.builder(Component.translatable(
                            "screen.worldmirror.status.overwriteAll"),
                    button -> resolveAllConflicts(target, true))
                    .bounds(left, 108, 178, BUTTON_HEIGHT).build());
            addRenderableWidget(Button.builder(Component.translatable(
                            "screen.worldmirror.status.discardAll"),
                    button -> resolveAllConflicts(target, false))
                    .bounds(left + 182, 108, 178, BUTTON_HEIGHT).build());
        }
        addRenderableWidget(Button.builder(Component.translatable(
                        "screen.worldmirror.status.openChunkMap"),
                button -> ChunkMapScreen.open())
                .bounds(left, 142, PANEL_WIDTH, BUTTON_HEIGHT).build());
    }

    private void renderStatus(Canvas graphics) {
        Path output = statusContext.output();
        String folder = output == null ? "—" : output.getFileName().toString();
        var observation = statusContext.observation();
        String label = observation == null ? "—" : observation.label();
        int x = left();
        pairLine(graphics, "screen.worldmirror.status.sourceId", label,
                "screen.worldmirror.status.mirrorPath", folder, x, 62);
        int savedCount = ChunkStatusCache.getOrScheduleSavedCount(statusContext.target(), 1_000L);
        pairLine(graphics, "screen.worldmirror.status.savedChunks",
                savedCount < 0 ? "…" : String.valueOf(savedCount),
                "screen.worldmirror.status.chunks",
                String.valueOf(ChunkListener.getTotalCount()), x, 76);
        pairLine(graphics, "screen.worldmirror.status.pendingWrite",
                String.valueOf(ChunkListener.getDirtyCount()),
                "screen.worldmirror.status.lastSync", lastSync().getString(), x, 90);
        graphics.text(Component.translatable(DownloadManager.captureStatusKey()), x, 104, 0xFFE0E0E0);
        graphics.text(Component.translatable(DownloadManager.isExportInProgress() || DownloadManager.hasManualExport()
                ? "screen.worldmirror.status.exportRunning"
                : "screen.worldmirror.status.exportIdle"), x + 182, 104, 0xFFE0E0E0);
        if (statusContext.target() != null) {
            var health = ExportStatusStore.global().health(statusContext.mirrorNodeId());
            graphics.text(ExportStatusText.health(health), x, 128, health.degraded() ? 0xFFFF6060 : 0xFFE0E0E0);
            if (health.degraded()) graphics.text(ExportStatusText.stage(health), x, 190, 0xFFFF6060);
        }
        MirrorWorldContext.Snapshot mirror = MirrorWorldContext.current();
        if (mirror.isMirror()) {
            graphics.centered(Component.translatable("screen.worldmirror.status.currentMirror."
                    + mirror.state().name().toLowerCase()), width / 2, 118, 0xFFFFFF55);
        }
    }

    private void renderSettings(Canvas graphics) {
        graphics.centered(Component.translatable("screen.worldmirror.tab.settingsHeader"),
                width / 2, 66, 0xFFE0E0E0);
        line(graphics, "screen.worldmirror.status.outputPath",
                statusContext.output() == null ? "—" : statusContext.output().toString(), left(), 80);
        line(graphics, "screen.worldmirror.status.xaeroOverlay",
                bridgeStatus().getString(), left(), 94);
        if (settingsFailure != null) {
            graphics.centered(settingsFailure, width / 2, 104,
                    settingsSucceeded ? 0xFF55FF55 : 0xFFFF5555);
        }
    }

    private boolean canSyncWorldSettings() {
        return WorldGraphService.instance().available() && Minecraft.getInstance().level != null
                && !statusContext.currentWorldIsMirror() && statusContext.target() != null
                && !DownloadManager.isCaptureEnabled()
                && !DownloadManager.isExportInProgress();
    }

    private void syncWorldSetting(WorldStructureCreator.Setting setting) {
        Minecraft client = Minecraft.getInstance();
        if (!canSyncWorldSettings() || !statusContext.equals(resolveStatusContext(client))) {
            settingsSucceeded = false;
            settingsFailure = Component.translatable(
                    "screen.worldmirror.status.syncSettingUnavailable");
            return;
        }
        WorldSettingsSnapshot snapshot = WorldStructureCreator.captureWorldSettings(client.level);
        MirrorTarget target = statusContext.target();
        OfflineTasks.start("WM-SyncWorldSetting",
                () -> WorldStructureCreator.syncWorldSetting(target, snapshot, setting), failure -> {
                    OperationFailures.report(target.nodeId(), "setting_sync", target.folder(), failure);
                    return false;
                }, client::execute, succeeded -> {
                    if (!target.equals(resolveStatusContext(client).target())) return;
                    settingsSucceeded = succeeded;
                    settingsFailure = Component.translatable(succeeded
                            ? "screen.worldmirror.status.syncSettingDone"
                            : "screen.worldmirror.status.syncSettingFailed");
                }, () -> {});
    }

    private void renderConflicts(Canvas graphics) {
        graphics.centered(Component.translatable("screen.worldmirror.tab.conflictsHeader"),
                width / 2, 66, 0xFFE0E0E0);
        if (conflictCount == 0) {
            graphics.centered(Component.translatable("screen.worldmirror.status.noConflicts"),
                    width / 2, 88, 0xFFE0E0E0);
        } else {
            line(graphics, "screen.worldmirror.status.conflicts",
                    conflictCount < 0 ? "?" : String.valueOf(conflictCount), left(), 88);
        }
    }

    private void line(Canvas graphics, String key, String value, int x, int y) {
        graphics.text(valueLine(key, value, PANEL_WIDTH), x, y, 0xFFE0E0E0);
    }

    private void pairLine(Canvas graphics, String leftKey, String leftValue,
                          String rightKey, String rightValue, int x, int y) {
        graphics.text(valueLine(leftKey, leftValue, 178), x, y, 0xFFE0E0E0);
        graphics.text(valueLine(rightKey, rightValue, 178), x + 182, y, 0xFFE0E0E0);
    }

    private Component valueLine(String key, String value, int maximumWidth) {
        Component prefix = Component.translatable(key).append(": ");
        int valueWidth = maximumWidth - font.width(prefix);
        if (font.width(value) > valueWidth) {
            value = font.plainSubstrByWidth(value,
                    Math.max(0, valueWidth - font.width("…"))) + "…";
        }
        return prefix.copy().append(value);
    }

    private Component tabLabel(String key, int tab) {
        return activeTab == tab
                ? Component.literal("§l").append(Component.translatable(key))
                : Component.translatable(key);
    }

    private void switchTab(int tab) {
        activeTab = tab;
        refresh();
    }

    private int left() {
        return width / 2 - PANEL_WIDTH / 2;
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
        if (conflictOperationInProgress || !target.equals(resolveStatusContext(client).target())) return;
        conflictOperationInProgress = true;
        ConflictActions.resolveAll(target, overwrite, () -> {
            conflictOperationInProgress = false;
            if (ClientDialogs.currentScreen(client) == this) refresh();
        });
    }

    private void loadPersistentStatus() {
        Minecraft client = Minecraft.getInstance();
        statusContext = resolveStatusContext(client);
        MirrorTarget target = statusContext.target();
        conflictCount = 0;
        try {
            if (target != null && ChunkStatusCache.hasIdentity(target)) {
                conflictCount = ConflictManager.countAllConflicts(target.folder());
                if (!ChunkStatusCache.hasIdentity(target)) conflictCount = 0;
                OperationFailures.recovered(target.nodeId(), "conflict_read");
            }
        } catch (java.io.IOException failure) {
            conflictCount = -1;
            OperationFailures.report(target.nodeId(), "conflict_read", target.folder(), failure);
        }
        refreshLastSyncTime();
    }

    private void refreshLastSyncTime() {
        MirrorTarget target = statusContext.target();
        if (target == null) {
            lastSyncTime = 0L;
            return;
        }
        lastSyncTime = WorldMetadata.loadIfPresent(target.folder())
                .filter(target::matches)
                .map(metadata -> metadata.lastSyncTime)
                .orElseGet(() -> Files.isRegularFile(target.folder().resolve(WorldMetadata.FILE_NAME)) ? -1L : 0L);
    }

    private static StatusContext resolveStatusContext(Minecraft client) {
        WorldGraphService service = WorldGraphService.instance();
        var observation = service.source();
        var statusTarget = ChunkStatusCache.targetFor(client);
        if (statusTarget != null) {
            var target = statusTarget.mirror();
            String parent = statusTarget.currentWorldIsMirror()
                    ? MirrorWorldContext.current().metadata().parentNodeId : service.sourceNode(observation);
            return new StatusContext(observation, parent, target.folder(), target, statusTarget.currentWorldIsMirror());
        }
        return new StatusContext(observation, service.sourceNode(observation), service.preview(observation), null,
                MirrorWorldContext.current().isMirror());
    }

    private Component lastSync() {
        if (lastSyncTime < 0L) return Component.literal("?");
        if (lastSyncTime == 0L) {
            return Component.translatable("screen.worldmirror.status.lastSyncNever");
        }
        return formatAge((System.currentTimeMillis() - lastSyncTime) / 1_000L);
    }

    private static Component formatAge(long seconds) {
        if (seconds < 0) seconds = 0;
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
        showScreen(new StatusClientScreen());
    }

    private void refreshWithSettingsResult(Component message, boolean succeeded) {
        StatusScreen screen = new StatusClientScreen();
        screen.settingsFailure = message;
        screen.settingsSucceeded = succeeded;
        showScreen(screen);
    }

    public static void open() {
        showScreen(new StatusClientScreen());
    }
}
