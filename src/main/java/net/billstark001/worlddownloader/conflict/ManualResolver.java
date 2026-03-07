package net.billstark001.worlddownloader.conflict;

import net.billstark001.worlddownloader.util.WDLogger;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ConflictResolver} that defers decisions on conflicting chunks.
 * <p>
 * When the in-game UI (roadmap §6) is implemented it will read
 * {@link #getPendingConflicts()} and present the player with per-chunk options
 * (overwrite / ignore / decide later).
 * <p>
 * Until the UI is available, conflicts are queued and the local copy is kept
 * (same as {@link IgnoreResolver}).
 */
public class ManualResolver implements ConflictResolver {

    private static final List<ChunkPos> pendingConflicts =
            Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean shouldWriteChunk(ConflictContext context) {
        if (context.existsLocally) {
            pendingConflicts.add(context.pos);
            WDLogger.warn("Conflict queued for chunk " + context.pos
                    + " — keeping local copy until resolved.");
            return false;
        }
        return true;
    }

    /**
     * Returns an unmodifiable view of all chunks that are waiting for manual
     * resolution.
     */
    public static List<ChunkPos> getPendingConflicts() {
        return Collections.unmodifiableList(pendingConflicts);
    }

    /** Clears all queued conflicts (e.g. after the user bulk-resolves them). */
    public static void clearPendingConflicts() {
        pendingConflicts.clear();
    }
}
