package net.billstark001.worlddownloader.conflict;

/**
 * {@link ConflictResolver} that always overwrites the local chunk with the
 * incoming server version.
 */
public class OverwriteResolver implements ConflictResolver {

    @Override
    public boolean shouldWriteChunk(ConflictContext context) {
        return true;
    }
}
