package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Selects a download destination for this connection without changing the saved default. */
final class DownloadMirrorSelectionScreen extends StatusScreenApi {
    private static final int BUTTON_WIDTH = 280;
    private final StatusScreen parent;
    private final String sourceId;
    private final WorldGraphService.Source observation;
    private final net.minecraft.client.multiplayer.ClientPacketListener connection;
    private int page;
    private Component error;

    DownloadMirrorSelectionScreen(StatusScreen parent, String sourceId) {
        super(Component.translatable("screen.worldmirror.status.chooseMirror"));
        this.parent = parent;
        this.sourceId = sourceId;
        this.observation = WorldGraphService.instance().source();
        this.connection = Minecraft.getInstance().getConnection();
    }

    @Override
    protected void init() {
        List<WorldGraphService.Target> targets = WorldGraphService.instance().targetsForSource(sourceId);
        int rows = Math.max(1, (height - 105) / 22);
        int pages = Math.max(1, (targets.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        int center = width / 2;
        int rowWidth = Math.min(BUTTON_WIDTH, width - 16);
        int left = center - rowWidth / 2;
        boolean newSelected = DownloadManager.isNewMirrorSelected();
        var current = newSelected ? null : DownloadManager.captureTarget();
        if (current == null && !newSelected)
            current = WorldGraphService.instance().target(observation);
        for (int i = page * rows; i < Math.min((page + 1) * rows, targets.size()); i++) {
            var target = targets.get(i);
            String label = target.folder().getParent().getFileName() + "/" + target.folder().getFileName();
            if (current != null && current.nodeId().equals(target.nodeId()))
                label = "✓ " + label;
            addRenderableWidget(
                    Button.builder(Component.literal(font.plainSubstrByWidth(label, rowWidth - 12)),
                                  button -> select(target.nodeId(), false))
                            .bounds(left, 36 + (i % rows) * 22, rowWidth, 20)
                            .build());
        }
        int navigation = height - 62;
        if (pages > 1) {
            Button previous =
                    addRenderableWidget(Button.builder(Component.literal("‹"), button -> changePage(-1))
                                    .bounds(center - 55, navigation, 52, 20)
                                    .build());
            Button next = addRenderableWidget(Button.builder(Component.literal("›"), button -> changePage(1))
                            .bounds(center + 3, navigation, 52, 20)
                            .build());
            previous.active = page > 0;
            next.active = page + 1 < pages;
        }
        addRenderableWidget(
                Button.builder(Component.translatable("screen.worldmirror.status.defaultMirror"),
                              button -> select(null, false))
                        .bounds(center - 146, height - 36, 94, 20)
                        .build());
        Component newLabel = Component.translatable("screen.worldmirror.status.newMirror");
        if (newSelected)
            newLabel = Component.literal("✓ ").append(newLabel);
        addRenderableWidget(Button.builder(newLabel, button -> select(null, true))
                        .bounds(center - 47, height - 36, 94, 20)
                        .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                        .bounds(center + 52, height - 36, 94, 20)
                        .build());
    }

    private void changePage(int by) {
        page += by;
        rebuildWidgets();
    }

    private void select(String targetId, boolean create) {
        var graph = WorldGraphService.instance();
        Minecraft client = Minecraft.getInstance();
        if (connection == client.getConnection() && graph.source() == observation
                && graph.observationIsCurrent(client)
                && sourceId.equals(graph.currentResolution().sourceNodeId())
                && StatusScreen.canChooseMirror()) {
            onClose();
            try {
                if (create)
                    DownloadManager.createDownloadMirror();
                else
                    DownloadManager.selectMirror(targetId);
                return;
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // The connection or mirror list can change while the chooser is open.
                showScreen(this);
            }
        }
        error = Component.translatable("screen.worldmirror.status.mirrorSelectionUnavailable");
    }

    @Override
    protected void renderContent(Canvas canvas) {
        canvas.centered(title, width / 2, 14, 0xFFFFFFFF);
        if (error != null)
            canvas.centered(error, width / 2, height - 76, 0xFFFF6060);
    }

    @Override
    public void onClose() {
        showScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
