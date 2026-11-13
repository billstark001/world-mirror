package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.download.MirrorMigrationCoordinator;
import io.github.billstark001.worldmirror.download.MirrorMigrationPlan;
import io.github.billstark001.worldmirror.download.WorldMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Stable identity attached to an offline action. */
public record MirrorTarget(Path folder, String nodeId, String sourceId) {
    public MirrorTarget {
        folder = Objects.requireNonNull(folder).toAbsolutePath().normalize();
        if (nodeId == null || nodeId.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("Missing mirror target identity");
        }
    }

    public static Optional<MirrorTarget> at(Path folder) {
        return WorldMetadata.loadIfPresent(folder).map(metadata -> new MirrorTarget(folder, metadata.nodeId, metadata.sourceId));
    }

    public boolean matches(WorldMetadata metadata) {
        return metadata != null && nodeId.equals(metadata.nodeId) && sourceId.equals(metadata.sourceId);
    }

    /** Rechecks the on-disk identity around a read that does not take a save lease. */
    public boolean matchesSavedIdentity() {
        return WorldMetadata.loadIfPresent(folder).filter(this::matches).isPresent();
    }

    /** Writers recheck this after acquiring the native lease, immediately before mutation. */
    public void requireCurrent() throws IOException {
        var inspection = MirrorMigrationCoordinator.inspect(folder);
        var metadata = inspection.metadata();
        if (inspection.state() != MirrorMigrationPlan.State.CURRENT || !matches(metadata)) {
            throw new IOException("Unverified mirror target: " + folder + " node=" + nodeId
                    + " state=" + inspection.state());
        }
    }
}
