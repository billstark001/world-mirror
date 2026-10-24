package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.ExportStatus;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Shared tooltip wording for process-local observations; old SQLite rows do not prove entity completion. */
public final class ExportStatusText {
    private static final String PREFIX = "screen.worldmirror.export.";
    private ExportStatusText() { }
    public static void append(List<Component> lines, ExportStatus.Chunk status, boolean savedTerrain) {
        if (status == null) {
            if (savedTerrain) lines.add(Component.translatable(PREFIX + "historical"));
            return;
        }
        lines.add(Component.translatable(PREFIX + "state", label("state.", status.state())));
        lines.add(Component.translatable(PREFIX + "parts", label("part.", status.terrain().state()),
                label("part.", status.entities().state())));
        if (status.metadataPending()) lines.add(Component.translatable(PREFIX + "metadata"));
        if (status.hasFailure() && (status.state() == ExportStatus.State.QUEUED || status.state() == ExportStatus.State.WRITING))
            lines.add(Component.translatable(PREFIX + "unresolved"));
    }
    public static Component health(ExportStatus.Health health) {
        var counts = health.counts();
        return Component.translatable(PREFIX + "health", counts.failed(), counts.partial(),
                counts.queued() + counts.writing(), counts.succeeded());
    }
    public static Component stage(ExportStatus.Health health) { return label("stage.", health.stage()); }
    private static Component label(String type, Enum<?> value) {
        return Component.translatable(PREFIX + type + value.name().toLowerCase(Locale.ROOT));
    }
}
