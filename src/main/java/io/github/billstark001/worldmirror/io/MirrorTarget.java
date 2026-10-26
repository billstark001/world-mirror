package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.download.MirrorMigrationCoordinator;
import io.github.billstark001.worldmirror.download.MirrorMigrationPlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Stable identity attached to an offline action; a folder name alone cannot authorize mutation. */
public record MirrorTarget(Path folder, String sourceId) {
    public MirrorTarget {
        folder = Objects.requireNonNull(folder).toAbsolutePath().normalize();
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("Missing target source");
    }

    /** Writers recheck this after acquiring the native lease, immediately before mutation. */
    public void requireCurrent() throws IOException {
        var inspection = MirrorMigrationCoordinator.inspect(folder);
        if (inspection.state() != MirrorMigrationPlan.State.CURRENT || inspection.metadata() == null
                || !sourceId.equals(inspection.metadata().sourceId)) {
            throw new IOException("Unverified mirror target: " + folder + " source=" + sourceId
                    + " state=" + inspection.state());
        }
    }
}
