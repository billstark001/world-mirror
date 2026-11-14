package io.github.billstark001.worldmirror.conflict;

/**
 * Replaces local terrain and defers protected seed terrain for explicit review.
 */
public class OverwriteResolver implements ConflictResolver {
    @Override
    public Decision decide(ConflictContext context) {
        return context.protectedSeed() ? Decision.DEFER : Decision.WRITE;
    }
}
