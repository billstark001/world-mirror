package io.github.billstark001.worldmirror.ui;

import static io.github.billstark001.worldmirror.ui.StatusScreen.BUTTON_HEIGHT;
import static io.github.billstark001.worldmirror.ui.StatusScreenApi.showScreen;
import static io.github.billstark001.worldmirror.ui.WorldMirrorTabbedScreen.selectionLabel;
import static io.github.billstark001.worldmirror.ui.WorldMirrorTabbedScreen.selectionValue;

import io.github.billstark001.worldmirror.config.EnumDropdownGui;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.DownloadRoute;
import io.github.billstark001.worldmirror.download.OperationFailures;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.io.MirrorTarget;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.ui.StatusScreenApi.Canvas;
import io.github.billstark001.worldmirror.ui.WorldMirrorTabbedScreen.SelectionOption;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

/** Configures the destination selected by the status screen. */
final class StatusSettingsTab {
    private final StatusScreen screen;
    private Button chooseMirrorButton;
    private Component settingsMessage;
    private boolean settingsSucceeded;
    private boolean savingRouting;

    StatusSettingsTab(StatusScreen screen) {
        this.screen = screen;
    }

    void addControls(int left) {
        var context = screen.statusContext();
        WorldGraphService service = WorldGraphService.instance();
        ModConfig.SaveLocation saveLocation = resolveSaveLocation();
        ModConfig.ConflictStrategy strategy =
                service.conflictStrategy(context.mirrorNodeId(), context.sourceNodeId());
        Button location = addEnumDropdown(left, screen.contentY(58), WorldLabels.LOCATION, saveLocation,
                ModConfig.SaveLocation.values(), WorldLabels::location, this::selectSaveLocation);
        location.active =
                service.available() && context.sourceNodeId() != null && !context.currentWorldIsMirror();
        Button conflict = addEnumDropdown(left, screen.contentY(80),
                "screen.worldmirror.status.conflictStrategy", strategy, ModConfig.ConflictStrategy.values(),
                WorldLabels::conflictStrategy, this::selectConflictStrategy);
        conflict.active = service.available() && (context.sourceNodeId() != null || context.target() != null);
        int settingWidth = (screen.panelWidth() - 8) / 3;
        boolean canSync = canSyncWorldSettings();
        Button syncTime = screen.addControl(
                Button.builder(Component.translatable("screen.worldmirror.status.syncTime"),
                              button -> syncWorldSetting(WorldStructureCreator.Setting.TIME))
                        .bounds(left, screen.contentY(102), settingWidth, BUTTON_HEIGHT)
                        .build());
        Button syncWeather = screen.addControl(
                Button.builder(Component.translatable("screen.worldmirror.status.syncWeather"),
                              button -> syncWorldSetting(WorldStructureCreator.Setting.WEATHER))
                        .bounds(left + settingWidth + 4, screen.contentY(102), settingWidth, BUTTON_HEIGHT)
                        .build());
        Button syncDifficulty = screen.addControl(
                Button.builder(Component.translatable("screen.worldmirror.status.syncDifficulty"),
                              button -> syncWorldSetting(WorldStructureCreator.Setting.DIFFICULTY))
                        .bounds(left + (settingWidth + 4) * 2, screen.contentY(102), settingWidth,
                                BUTTON_HEIGHT)
                        .build());
        syncTime.active = canSync;
        syncWeather.active = canSync;
        syncDifficulty.active = canSync;
        screen.addControl(
                Button.builder(Component.translatable("screen.worldmirror.status.openSettings"), button -> {
                          EnumDropdownGui.register();
                          showScreen(AutoConfigClient.getConfigScreen(ModConfig.class, screen).get());
                      }).bounds(left, screen.contentY(124), settingWidth, BUTTON_HEIGHT).build());
        chooseMirrorButton = screen.addControl(
                Button.builder(Component.translatable("screen.worldmirror.status.chooseMirror"),
                              button -> {
                                  String source = mirrorSource();
                                  if (source != null && StatusScreen.canChooseMirror())
                                      DownloadMirrorSelectionScreen.open(screen, source);
                              })
                        .bounds(left + settingWidth + 4, screen.contentY(124), settingWidth, BUTTON_HEIGHT)
                        .build());
        chooseMirrorButton.active = mirrorSource() != null && StatusScreen.canChooseMirror();
        screen.addControl(
                Button.builder(Component.translatable("screen.worldmirror.routing.title"),
                              button -> WorldRoutingScreen.open(screen))
                        .bounds(left + (settingWidth + 4) * 2, screen.contentY(124), settingWidth,
                                BUTTON_HEIGHT)
                        .build());
    }

    void clearMessage() {
        settingsMessage = null;
    }

    void updateChooseMirror() {
        if (chooseMirrorButton != null)
            chooseMirrorButton.active = mirrorSource() != null && StatusScreen.canChooseMirror();
    }

    void resetControls() {
        chooseMirrorButton = null;
    }

    private static String mirrorSource() {
        return WorldGraphService.instance().routing().currentResolution().sourceNodeId();
    }

    private <T extends Enum<T>> Button addEnumDropdown(int x, int y, String labelKey, T current, T[] values,
            Function<T, Component> valueLabel, Consumer<T> onSelect) {
        var options = Arrays.stream(values)
                              .map(value
                                      -> new SelectionOption(valueLabel.apply(value), value == current,
                                              () -> onSelect.accept(value)))
                              .toList();
        return screen.addControl(
                Button.builder(selectionLabel(selectionValue(labelKey, valueLabel.apply(current))),
                              button -> screen.openSelectionPopup(button, options))
                        .bounds(x, y, screen.panelWidth(), BUTTON_HEIGHT)
                        .build());
    }

    private void selectSaveLocation(ModConfig.SaveLocation target) {
        if (!canChangeRouting())
            return;
        var context = screen.statusContext();
        if (target == resolveSaveLocation()) {
            screen.refresh();
        } else if (context.target() != null) {
            showScreen(new SaveLocationMoveScreen(screen, context.target(), target));
        } else {
            saveRouting("save_location",
                    () -> WorldGraphService.instance().setSourceSaveLocation(context.sourceNodeId(), target));
        }
    }

    private void selectConflictStrategy(ModConfig.ConflictStrategy strategy) {
        if (!canChangeRouting())
            return;
        var context = screen.statusContext();
        saveRouting("conflict_strategy",
                ()
                        -> WorldGraphService.instance().setConflictStrategy(
                                context.mirrorNodeId(), context.sourceNodeId(), strategy));
    }

    private boolean canChangeRouting() {
        if (DownloadManager.isCaptureEnabled() || DownloadRoute.isDownloadPreparing()
                || DownloadRoute.hasDownloadPreparationReview()) {
            showFailure(Component.translatable("screen.worldmirror.move.failure.download_active"));
            return false;
        }
        if (DownloadManager.isExportInProgress()) {
            showFailure(Component.translatable("screen.worldmirror.move.failure.export_in_progress"));
            return false;
        }
        if (savingRouting)
            return false;
        if (!screen.statusContext().equals(screen.resolveStatusContext(Minecraft.getInstance()))) {
            screen.refresh();
            return false;
        }
        return true;
    }

    @FunctionalInterface
    private interface RoutingChange {
        void run() throws java.io.IOException;
    }

    private void saveRouting(String operation, RoutingChange change) {
        var context = screen.statusContext();
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
                    if (ClientDialogs.currentScreen(client) != screen)
                        return;
                    if (success)
                        screen.refresh();
                    else
                        showFailure(Component.translatable("screen.worldmirror.move.failure.io_error"));
                },
                () -> savingRouting = false);
    }

    void render(Canvas graphics) {
        var context = screen.statusContext();
        graphics.centered(Component.translatable("screen.worldmirror.tab.settingsHeader"), screen.width / 2,
                screen.contentY(14), 0xFFE0E0E0);
        screen.line(graphics, "screen.worldmirror.status.outputPath",
                context.output() == null ? "—" : context.output().toString(), screen.left(),
                screen.contentY(28));
        screen.line(graphics, "screen.worldmirror.status.xaeroOverlay", bridgeStatus().getString(),
                screen.left(), screen.contentY(42));
        if (settingsMessage != null) {
            graphics.centered(settingsMessage, screen.width / 2, screen.contentY(52),
                    settingsSucceeded ? 0xFF55FF55 : 0xFFFF5555);
        }
    }

    private boolean canSyncWorldSettings() {
        var context = screen.statusContext();
        return WorldGraphService.instance().available() && Minecraft.getInstance().level != null
                && !context.currentWorldIsMirror() && context.target() != null
                && !DownloadManager.isCaptureEnabled() && !DownloadRoute.isDownloadPreparing()
                && !DownloadRoute.hasDownloadPreparationReview() && !DownloadManager.isExportInProgress();
    }

    private void syncWorldSetting(WorldStructureCreator.Setting setting) {
        Minecraft client = Minecraft.getInstance();
        var context = screen.statusContext();
        if (!canSyncWorldSettings() || !context.equals(screen.resolveStatusContext(client))) {
            settingsSucceeded = false;
            settingsMessage = Component.translatable("screen.worldmirror.status.syncSettingUnavailable");
            return;
        }
        WorldSettingsSnapshot snapshot = WorldStructureCreator.captureWorldSettings(client.level);
        MirrorTarget target = context.target();
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
                    if (!target.equals(screen.resolveStatusContext(client).target()))
                        return;
                    settingsSucceeded = succeeded;
                    settingsMessage =
                            Component.translatable(succeeded ? "screen.worldmirror.status.syncSettingDone"
                                                             : "screen.worldmirror.status.syncSettingFailed");
                },
                () -> {});
    }

    private static Component bridgeStatus() {
        if (!ModConfig.get().chunkMap.showXaeroWorldMapOverlay) {
            return Component.translatable("screen.worldmirror.status.xaeroOverlay.disabled");
        }
        return FabricLoader.getInstance().isModLoaded("xaero_world_map_bridge")
                ? Component.translatable("screen.worldmirror.status.xaeroOverlay.bridge")
                : Component.translatable("screen.worldmirror.status.xaeroOverlay.missing");
    }

    private ModConfig.SaveLocation resolveSaveLocation() {
        var context = screen.statusContext();
        WorldGraphService service = WorldGraphService.instance();
        String nodeId = context.mirrorNodeId();
        var node = nodeId == null ? null : service.graph().nodes().get(nodeId);
        if (node != null && node.location() != null) {
            return ModConfig.SaveLocation.valueOf(node.location().root().name());
        }
        return service.saveLocation(context.sourceNodeId());
    }

    private void showFailure(Component message) {
        settingsMessage = message;
        settingsSucceeded = false;
        screen.refresh();
    }
}
