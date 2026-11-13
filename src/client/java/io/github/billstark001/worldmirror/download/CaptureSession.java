package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;

import java.util.Objects;

/** A live cache owner. Frozen work keeps this identity even after the connection changes. */
record CaptureSession(long connectionEpoch, long sourceEpoch, String sourceNodeId,
        WorldGraphService.Target target, Settings settings) {
    record Settings(ModConfig.ConflictStrategy strategy, WorldSettingsSnapshot worldSettings,
            boolean invalidateAfterExport) {
        Settings {
            Objects.requireNonNull(strategy);
            Objects.requireNonNull(worldSettings);
        }
    }

    CaptureSession {
        Objects.requireNonNull(target);
        Objects.requireNonNull(settings);
        if (!target.parentNodeId().equals(sourceNodeId))
            throw new IllegalArgumentException("Capture source differs from the target's parent");
    }

    String id() {
        return target.nodeId();
    }
}
