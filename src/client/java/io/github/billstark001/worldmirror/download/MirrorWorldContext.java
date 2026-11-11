package io.github.billstark001.worldmirror.download;

import java.nio.file.Path;

/**
 * Read-only identity of the locally-open world.  This deliberately does not
 * infer ownership from a folder name: a mirror is identified by its metadata.
 */
public final class MirrorWorldContext {

    public enum State { NOT_MIRROR, CURRENT, OUTDATED, FUTURE, UNREADABLE }

    public record Snapshot(Path worldFolder, State state, WorldMetadata metadata) {
        public static final Snapshot NONE = new Snapshot(null, State.NOT_MIRROR, null);

        public boolean isMirror() {
            return state == State.CURRENT || state == State.OUTDATED || state == State.FUTURE;
        }

        public String sourceId() {
            return metadata != null ? metadata.sourceId : null;
        }
    }

    private static volatile Snapshot current = Snapshot.NONE;

    private MirrorWorldContext() {}

    public static Snapshot current() {
        return current;
    }

    /** Updates the current local-save identity after JOIN. */
    public static void enter(Path worldFolder, int dataVersion) {
        current = inspect(worldFolder, dataVersion);
    }

    public static void leave() {
        current = Snapshot.NONE;
    }

    public static Snapshot inspect(Path worldFolder, int dataVersion) {
        if (worldFolder == null) return Snapshot.NONE;
        MirrorMigrationPlan.Inspection inspection = MirrorMigrationPlan.inspect(worldFolder, dataVersion);
        return switch (inspection.state()) {
            case CURRENT -> new Snapshot(inspection.worldFolder(), State.CURRENT, inspection.metadata());
            case OUTDATED -> new Snapshot(inspection.worldFolder(), State.OUTDATED, inspection.metadata());
            case FUTURE -> new Snapshot(inspection.worldFolder(), State.FUTURE, inspection.metadata());
            case UNREADABLE -> new Snapshot(inspection.worldFolder(), State.UNREADABLE, inspection.metadata());
            default -> Snapshot.NONE;
        };
    }
}
