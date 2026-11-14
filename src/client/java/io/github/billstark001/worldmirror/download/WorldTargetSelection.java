package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphService.Target;

import java.util.Map;

/** One selection decision shared by route previews, editors and target preparation. */
public final class WorldTargetSelection {
    public enum State { EXISTING, CREATE, PIN_UNAVAILABLE, CHOICE_UNAVAILABLE, DEFERRED }

    public record Decision(State state, Target target, String requestedMirrorId) {}

    private WorldTargetSelection() {}

    static Decision resolve(Snapshot graph, WorldGraphConfig.Snapshot config, Map<String, Target> targets,
            String sourceId, String sessionChoice, boolean createMirror) {
        if (sourceId == null || !graph.isUnambiguous(sourceId))
            return new Decision(State.DEFERRED, null, null);

        var settings = config.sourceSettings().get(sourceId);
        if (sessionChoice != null)
            return requested(targets, sourceId, sessionChoice, State.CHOICE_UNAVAILABLE);
        String hint = settings == null ? null : settings.lastAutomaticMirrorId();
        if (!createMirror) {
            var pin = settings == null ? null : settings.pinnedMirrorId();
            if (pin != null)
                return requested(targets, sourceId, pin, State.PIN_UNAVAILABLE);
            var automatic = target(targets, sourceId, hint);
            if (automatic == null)
                automatic = firstTarget(graph, targets, sourceId);
            if (automatic != null)
                return new Decision(State.EXISTING, automatic, null);
        }
        boolean pending = WorldGraphInventory.hasDeferredSource(config, sourceId)
                || awaitingKnownTarget(config, hint) || graph.hasDuplicateDownloadFor(sourceId);
        return new Decision(pending ? State.DEFERRED : State.CREATE, null, null);
    }

    private static boolean awaitingKnownTarget(WorldGraphConfig.Snapshot config, String id) {
        var location = id == null ? null : config.locations().get(id);
        return location != null
                && config.pendingImports().containsKey(WorldInventoryFolders.locationKey(location));
    }

    private static Target firstTarget(Snapshot graph, Map<String, Target> targets, String sourceId) {
        for (var child : graph.childrenOf(sourceId)) {
            var target = target(targets, sourceId, child.id());
            if (target != null)
                return target;
        }
        return null;
    }

    private static Decision requested(
            Map<String, Target> targets, String sourceId, String id, State unavailable) {
        var target = target(targets, sourceId, id);
        return new Decision(target == null ? unavailable : State.EXISTING, target, id);
    }

    private static Target target(Map<String, Target> targets, String sourceId, String id) {
        Target target = id == null ? null : targets.get(id);
        return target != null && sourceId.equals(target.parentNodeId()) ? target : null;
    }
}
