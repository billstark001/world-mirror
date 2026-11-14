package io.github.billstark001.worldmirror.conflict;

/**
 * Strategy for deciding what to do when a chunk is about to be written to the
 * mirror world and a local copy already exists.
 * <p>
 * Implementations must be stateless (or thread-safe) and are reused across
 * sync sessions.
 */
public interface ConflictResolver {
    enum Decision { WRITE, KEEP, DEFER }

    /**
     * Called once per chunk during an export/sync pass.
     *
     * @param context Information about the chunk and its local state.
     * @return How the exporter should handle the incoming chunk.
     */
    Decision decide(ConflictContext context);
}
