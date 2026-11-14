package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.DownloadRoute;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;

/** Selects a download destination for this connection without changing the saved default. */
final class DownloadMirrorSelectionScreen {
    private static final String DEFAULT = "\0default";
    private static final String NEW = "\0new";

    private DownloadMirrorSelectionScreen() {}

    static void open(StatusScreen parent, String sourceId) {
        Minecraft client = Minecraft.getInstance();
        var graph = WorldGraphService.instance();
        var observation = graph.routing().source();
        var connection = client.getConnection();
        var snapshot = graph.graph();
        var choices = new ArrayList<WorldSelectionScreen.Choice>();
        choices.add(WorldSelectionScreen.Choice.action(DEFAULT, text("defaultMirror")));
        choices.add(WorldSelectionScreen.Choice.action(NEW, text("newMirror")));
        for (var target : graph.targetsForSource(sourceId)) {
            var node = snapshot.nodes().get(target.nodeId());
            if (node != null && node.location() != null) {
                choices.add(WorldSelectionScreen.Choice.world(target.nodeId(),
                        Component.literal(node.location().folder()), node.location(), node.kind()));
            }
        }
        boolean newSelected = DownloadRoute.isNewMirrorSelected();
        var current = newSelected ? null : DownloadRoute.captureTarget();
        if (current == null && !newSelected)
            current = graph.target(observation);
        String selected = newSelected ? NEW : current == null ? DEFAULT : current.nodeId();
        StatusScreenApi.showScreen(new WorldSelectionScreen(
                parent, text("chooseMirror"), choices, selected, text("chooseMirror"), choice -> {
                    if (connection == client.getConnection() && graph.routing().source() == observation
                            && graph.routing().observationIsCurrent(client)
                            && sourceId.equals(graph.routing().currentResolution().sourceNodeId())
                            && StatusScreen.canChooseMirror()) {
                        try {
                            switch (choice.id()) {
                                case NEW -> DownloadRoute.createDownloadMirror();
                                case DEFAULT -> DownloadRoute.selectMirror(null);
                                default -> DownloadRoute.selectMirror(choice.id());
                            }
                            return null;
                        } catch (IllegalArgumentException | IllegalStateException rejected) {
                            // The connection or mirror list can change while the chooser is open.
                        }
                    }
                    return text("mirrorSelectionUnavailable");
                }));
    }

    private static Component text(String key) {
        return Component.translatable("screen.worldmirror.status." + key);
    }
}
