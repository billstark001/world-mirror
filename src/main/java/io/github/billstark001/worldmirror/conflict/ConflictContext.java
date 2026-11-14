package io.github.billstark001.worldmirror.conflict;

/**
 * Local terrain facts needed by the conflict strategy.
 *
 * @param existsLocally Whether local terrain already occupies the slot and needs a decision.
 * @param protectedSeed Whether the local seed terrain needs explicit approval before replacement.
 */
public record ConflictContext(boolean existsLocally, boolean protectedSeed) {}
