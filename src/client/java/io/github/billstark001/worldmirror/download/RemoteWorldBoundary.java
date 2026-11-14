package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderState;

/** Authorization can pause while the live cache remains owned by its original source. */
final class RemoteWorldBoundary {
    static final long DISCOVERY_NS = 2_000_000_000L;

    enum State { OPEN, AFTER_RESPAWN, WAITING_ID, NEEDS_CONFIRMATION }

    private State state = State.OPEN;
    private long epoch;
    private long deadline;
    private String previousSource;

    void enter(long worldEpoch, long now, boolean needsFreshIdentity, String source) {
        if (!blocksCapture() || source != null)
            previousSource = source;
        epoch = worldEpoch;
        deadline = now + DISCOVERY_NS;
        // Even an authorized vanilla respawn must finish replacing its player and level.
        state = needsFreshIdentity ? State.WAITING_ID : State.AFTER_RESPAWN;
    }

    boolean blocksCapture() {
        return state != State.OPEN;
    }

    boolean needsConfirmation() {
        return state == State.NEEDS_CONFIRMATION;
    }

    boolean needsIdentity() {
        return state == State.WAITING_ID || state == State.NEEDS_CONFIRMATION;
    }

    long epoch() {
        return epoch;
    }

    String previousSource() {
        return previousSource;
    }

    void reset() {
        state = State.OPEN;
        previousSource = null;
    }

    boolean advance(long now) {
        if (state == State.AFTER_RESPAWN) {
            reset();
            return true;
        }
        if (state == State.WAITING_ID && now - deadline >= 0)
            state = State.NEEDS_CONFIRMATION;
        return false;
    }

    boolean attest(long worldEpoch, ProviderObservation observation) {
        if (!blocksCapture() || epoch != worldEpoch || observation.state() != ProviderState.ADVERTISED)
            return false;
        reset();
        return true;
    }

    boolean confirm(long worldEpoch, boolean identityRequired) {
        if (!needsConfirmation() || epoch != worldEpoch || identityRequired)
            return false;
        reset();
        return true;
    }
}
