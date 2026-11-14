package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class WorldLabels {
    static final String LOCATION = "screen.worldmirror.status.saveLoc";

    private WorldLabels() {}

    static Component kind(WorldGraph.Kind kind) {
        return Component.translatable(
                "screen.worldmirror.routing.kind." + kind.name().toLowerCase(Locale.ROOT));
    }

    static Component location(ModConfig.SaveLocation location) {
        return Component.translatable(
                "config.worldmirror.saveLoc." + location.name().toLowerCase(Locale.ROOT));
    }

    static Component conflictStrategy(ModConfig.ConflictStrategy strategy) {
        return Component.translatable(
                "config.worldmirror.conflictStrategy." + strategy.name().toLowerCase(Locale.ROOT));
    }

    public static String path(WorldGraph.Location location) {
        String root = location.root() == WorldGraph.Root.SAVES ? "saves" : "downloaded_worlds";
        return root + "/" + location.folder();
    }
}
