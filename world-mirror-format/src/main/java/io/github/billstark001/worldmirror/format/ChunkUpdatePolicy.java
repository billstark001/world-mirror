package io.github.billstark001.worldmirror.format;

import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkRecord;

import java.util.Map;

/** The persistent index's timestamp and source-priority decision. */
public final class ChunkUpdatePolicy {
    private ChunkUpdatePolicy() {}

    public enum IndexStatus { UNINDEXED, BLOCKED, ELIGIBLE, UNKNOWN_SOURCE }

    public enum Destination { MISSING, EMPTY, PRESENT }

    public enum WritePolicy { LIVE_SYNC, FILL_EMPTY, REPLACE_OLDER }

    public enum Action { WRITE, RESOLVE_CONFLICT, PRESERVE, REPAIR_INDEX }

    /** Compare a capture with the indexed row using the priorities applicable to its dimension. */
    public static IndexStatus assess(
            ChunkUpdate incoming, ChunkRecord existing, Map<String, Integer> priorities) {
        if (existing == null)
            return IndexStatus.UNINDEXED;
        if (incoming.coordinate().x() != existing.x() || incoming.coordinate().z() != existing.z()) {
            throw new IllegalArgumentException("Index row has different chunk coordinates");
        }
        if (incoming.captureTime() <= existing.updateTime())
            return IndexStatus.BLOCKED;
        Integer existingPriority = priorities.get(existing.updateSource());
        if (existingPriority == null)
            return IndexStatus.UNKNOWN_SOURCE;
        int incomingPriority = priorities.getOrDefault(incoming.updateSource(), Integer.MAX_VALUE);
        return shouldSkip(existing.updateTime(), existingPriority, incoming.captureTime(), incomingPriority)
                ? IndexStatus.BLOCKED
                : IndexStatus.ELIGIBLE;
    }

    /** Apply the caller's overwrite policy to the same on-disk and index facts. */
    public static Action decide(WritePolicy policy, Destination destination, IndexStatus index) {
        if (policy == WritePolicy.LIVE_SYNC) {
            if (index == IndexStatus.BLOCKED) {
                return destination == Destination.MISSING ? Action.REPAIR_INDEX : Action.PRESERVE;
            }
            return Action.RESOLVE_CONFLICT;
        }
        if (destination != Destination.PRESENT)
            return Action.WRITE;
        return policy == WritePolicy.REPLACE_OLDER && index == IndexStatus.ELIGIBLE ? Action.WRITE
                                                                                    : Action.PRESERVE;
    }

    /** Lower numeric priority is stronger. Equal priorities allow newer captures. */
    public static boolean shouldSkip(
            long existingTime, int existingPriority, long incomingTime, int incomingPriority) {
        return incomingTime <= existingTime || existingPriority < incomingPriority;
    }
}
