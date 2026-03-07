package net.billstark001.worlddownloader.conflict;

/**
 * Strategy for deciding what to do when a chunk is about to be written to the
 * mirror world and a local copy already exists.
 * <p>
 * Implementations must be stateless (or thread-safe) and are reused across
 * sync sessions.
 */
public interface ConflictResolver {

    /**
     * Called once per chunk during an export/sync pass.
     *
     * @param context Information about the chunk and its local state.
     * @return {@code true} to overwrite the existing local chunk with the
     *         server version; {@code false} to keep the local version.
     */
    boolean shouldWriteChunk(ConflictContext context);
}
