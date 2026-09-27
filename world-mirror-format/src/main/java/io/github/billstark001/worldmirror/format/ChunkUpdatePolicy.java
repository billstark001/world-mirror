package io.github.billstark001.worldmirror.format;

/** The persistent index's timestamp and source-priority decision. */
public final class ChunkUpdatePolicy {
    private ChunkUpdatePolicy() { }

    /** Lower numeric priority is stronger. Equal priorities allow newer captures. */
    public static boolean shouldSkip(long existingTime, int existingPriority,
                                     long incomingTime, int incomingPriority) {
        return incomingTime <= existingTime || existingPriority < incomingPriority;
    }
}
