package io.github.billstark001.worldmirror.download;

/** Observed export results. These values never grant a persistence acknowledgement. */
public final class ExportStatus {
    private ExportStatus() { }
    public enum State { QUEUED, WRITING, FAILED, PARTIAL, SUCCEEDED }
    public enum PartState { NOT_REQUIRED, QUEUED, WRITING, FAILED, SUCCEEDED }
    public enum FailureStage { NONE, PREPARATION, TERRAIN, ENTITIES, INDEX, METADATA }

    public record Part(long required, long committed, long failed, long writing) {
        public static final Part NONE = new Part(-1, -1, -1, -1);
        public boolean needed() { return required >= 0; }
        public boolean complete() { return needed() && committed >= required; }
        public boolean hasFailure() { return failed > committed; }
        public PartState state() {
            if (!needed()) return PartState.NOT_REQUIRED;
            if (complete()) return PartState.SUCCEEDED;
            if (writing >= 0) return PartState.WRITING;
            return failed >= required ? PartState.FAILED : PartState.QUEUED;
        }
        Part require(long revision) { return revision <= required ? this : new Part(revision, committed, failed, writing); }
        Part start(long revision) { return revision <= writing ? this : new Part(required, committed, failed, revision); }
        Part finish(long revision, boolean success) {
            if (writing != revision && (success ? revision <= committed : revision <= failed)) return this;
            return new Part(required, success ? Math.max(committed, revision) : committed,
                    success ? failed : Math.max(failed, revision), writing == revision ? -1 : writing);
        }
    }

    public record Chunk(int x, int z, Part terrain, Part entities, boolean metadataPending, long metadataFloor) {
        public static Chunk queued(int x, int z, long terrainRevision) {
            return new Chunk(x, z, Part.NONE.require(terrainRevision), Part.NONE, false, 0);
        }
        public Chunk withTerrain(long revision) {
            return withParts(terrain.require(revision), entities);
        }
        Chunk withParts(Part terrain, Part entities) {
            return this.terrain == terrain && this.entities == entities ? this : new Chunk(x, z, terrain, entities, metadataPending, metadataFloor);
        }
        Chunk withMetadata(boolean pending, long floor) {
            return metadataPending == pending && metadataFloor == floor ? this : new Chunk(x, z, terrain, entities, pending, floor);
        }
        public boolean hasFailure() { return terrain.hasFailure() || entities.hasFailure() || metadataPending; }
        public int confirmedParts() { return (terrain.complete() ? 1 : 0) + (entities.complete() ? 1 : 0); }
        public State state() {
            if (terrain.state() == PartState.WRITING || entities.state() == PartState.WRITING) return State.WRITING;
            if (terrain.state() == PartState.QUEUED || entities.state() == PartState.QUEUED) return State.QUEUED;
            if (hasFailure()) return confirmedParts() > 0 ? State.PARTIAL : State.FAILED;
            return State.SUCCEEDED;
        }
        public Counts counts() {
            State state = state();
            return new Counts(state == State.QUEUED ? 1 : 0, state == State.WRITING ? 1 : 0,
                    state == State.FAILED ? 1 : 0, state == State.PARTIAL ? 1 : 0,
                    state == State.SUCCEEDED ? 1 : 0, hasFailure() ? 1 : 0,
                    hasFailure() && confirmedParts() == 0 ? 1 : 0, metadataPending ? 1 : 0);
        }
    }

    /** Writer-owned counters; immutable Counts values are allocated only when publishing. */
    static final class Counter {
        private final int[] states = new int[State.values().length];
        private int unresolved, blocked, metadata;
        void replace(Chunk previous, Chunk next) {
            if (previous != null) add(previous, -1);
            add(next, 1);
        }
        private void add(Chunk chunk, int delta) {
            states[chunk.state().ordinal()] += delta;
            if (chunk.hasFailure()) {
                unresolved += delta;
                if (chunk.confirmedParts() == 0) blocked += delta;
            }
            if (chunk.metadataPending()) metadata += delta;
        }
        int unresolved() { return unresolved; }
        Counts snapshot() {
            return new Counts(states[State.QUEUED.ordinal()], states[State.WRITING.ordinal()], states[State.FAILED.ordinal()],
                    states[State.PARTIAL.ordinal()], states[State.SUCCEEDED.ordinal()], unresolved, blocked, metadata);
        }
    }

    /** Preserve all bucket states and unresolved failures, including while a retry is writing. */
    public record Counts(int queued, int writing, int failed, int partial, int succeeded,
                         int unresolved, int blocked, int metadata) {
        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0, 0, 0);
        public Counts plus(Counts other) { return combine(other, 1); }
        public Counts minus(Counts other) { return combine(other, -1); }
        private Counts combine(Counts b, int sign) {
            return new Counts(queued + sign * b.queued, writing + sign * b.writing, failed + sign * b.failed,
                    partial + sign * b.partial, succeeded + sign * b.succeeded, unresolved + sign * b.unresolved,
                    blocked + sign * b.blocked, metadata + sign * b.metadata);
        }
        public int total() { return queued + writing + failed + partial + succeeded; }
        public int pending() { return queued + writing + failed + partial; }
        public State dominant() {
            if (failed > 0) return State.FAILED;
            if (partial > 0) return State.PARTIAL;
            if (writing > 0) return State.WRITING;
            if (queued > 0) return State.QUEUED;
            return State.SUCCEEDED;
        }
    }

    public record Health(String source, Counts counts, boolean worldFailure, FailureStage stage) {
        public boolean degraded() { return worldFailure || counts.unresolved() > 0; }
        public boolean blocked() { return worldFailure || counts.blocked() > 0 || counts.metadata() > 0
                || stage == FailureStage.PREPARATION || stage == FailureStage.INDEX || stage == FailureStage.METADATA; }
        public boolean settled() { return !degraded() && counts.pending() == 0; }
    }
}
