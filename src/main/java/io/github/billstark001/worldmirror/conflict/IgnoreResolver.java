package io.github.billstark001.worldmirror.conflict;

/**
 * {@link ConflictResolver} that keeps the local copy whenever one exists.
 * New chunks (not yet downloaded) are written normally.
 */
public class IgnoreResolver implements ConflictResolver {
    @Override
    public Decision decide(ConflictContext context) {
        return context.existsLocally() ? Decision.KEEP : Decision.WRITE;
    }
}
