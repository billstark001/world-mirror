package net.billstark001.worlddownloader.conflict;

/**
 * {@link ConflictResolver} that keeps the local copy whenever one exists.
 * New chunks (not yet downloaded) are written normally.
 */
public class IgnoreResolver implements ConflictResolver {

    @Override
    public boolean shouldWriteChunk(ConflictContext context) {
        // Write only if no local version exists yet
        return !context.existsLocally;
    }
}
