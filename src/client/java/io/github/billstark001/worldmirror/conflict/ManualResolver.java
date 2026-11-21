package io.github.billstark001.worldmirror.conflict;

/** Defers conflicting server terrain for explicit review. */
public final class ManualResolver implements ConflictResolver {
    @Override
    public Decision decide(ConflictContext context) {
        return context.existsLocally() ? Decision.DEFER : Decision.WRITE;
    }
}
