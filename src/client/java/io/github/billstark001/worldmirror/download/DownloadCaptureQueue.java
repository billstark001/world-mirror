package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Client-thread-owned capture queue, light windows, and diagnostics. Workers receive only frozen snapshots. */
final class DownloadCaptureQueue {
    private static final int LIGHT_UPDATE_COALESCE_TICKS = 2;

    record Key(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}
    record Pending(Key key, String reason, long queuedAt, boolean allowEmpty, boolean required) {}
    private record LoadedScan(ClientLevel world, CaptureRegionScan coordinates, String reason) {}

    record Metrics(int pendingCaptures, long oldestCaptureAgeMs, long coalescedHints, long droppedHints) {}

    private final LinkedHashMap<Key, Pending> pending = new LinkedHashMap<>();
    private final ArrayDeque<LoadedScan> scans = new ArrayDeque<>();
    // Changes to client-visible payloads must survive capacity pressure and unload.
    private final LinkedHashMap<Key, Pending> overflowChanges = new LinkedHashMap<>();
    private final Map<Key, Pending> failedChanges = new HashMap<>();
    private final CapturedChunkObservations capturedChunks = new CapturedChunkObservations();
    private final Map<Key, Long> pendingLightUpdates = new HashMap<>();
    private final CapturePendingStatus pendingStatus = new CapturePendingStatus();
    private boolean reconciliationNeeded;
    private long estimatedCaptureNs;
    private final BooleanSupplier recordPerformance;
    private final int reconciliationRange;
    private final IntSupplier pendingLimit;

    private long clientTick;
    final CaptureQueueDiagnostics diagnostics = new CaptureQueueDiagnostics();
    DownloadCaptureQueue(
            BooleanSupplier recordPerformance, int reconciliationRange, IntSupplier pendingLimit) {
        this.recordPerformance = recordPerformance;
        this.reconciliationRange = reconciliationRange;
        this.pendingLimit = pendingLimit;
    }

    Metrics metrics() {
        var first = pending.isEmpty() ? overflowChanges.firstEntry() : pending.firstEntry();
        Pending oldest = first == null ? null : first.getValue();
        long age = oldest == null ? 0L : Math.max(0L, (System.nanoTime() / 1_000_000L) - oldest.queuedAt());
        return new Metrics(pending.size() + overflowChanges.size(), age, diagnostics.coalescedHints(),
                diagnostics.droppedHints());
    }

    void queueChunk(ClientLevel world, ChunkPos pos, String reason) {
        if (world == null || pos == null || SeedChunkTracking.hidden(world, pos))
            return;
        queueObservation(key(world.dimension(), pos), reason);
    }

    void queuePayloadChange(ClientLevel world, ChunkPos pos, String reason, boolean allowEmpty) {
        if (world == null || pos == null || SeedChunkTracking.hidden(world, pos))
            return;
        Key key = key(world.dimension(), pos);
        if (world.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false) instanceof LevelChunk)
            queuePayloadChange(key, reason, allowEmpty);
    }

    boolean queuePayloadChange(Key key, String reason, boolean allowEmpty) {
        return queueChangedObservation(key, reason, allowEmpty, true);
    }

    void captureBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (world == null || chunk == null)
            return;
        var key = key(world.dimension(), chunk.getPos());
        try {
            if (canSample(world, chunk)) {
                captureLoaded(world, chunk, key);
            } else {
                takeHint(key);
                removeLightWindow(key);
            }
        } finally {
            capturedChunks.remove(key);
            failedChanges.remove(key);
        }
    }

    boolean captureLoaded(ClientLevel world, LevelChunk chunk) {
        return canSample(world, chunk) && captureLoaded(world, chunk, key(world.dimension(), chunk.getPos()));
    }

    private boolean captureLoaded(ClientLevel world, LevelChunk chunk, Key key) {
        return captureLoaded(key, remoteChunkIdentity(chunk),
                () -> ChunkSerializer.isChunkEmpty(chunk), () -> ChunkSerializer.serialize(world, chunk));
    }

    /** Final sampling remains available while ordinary tick capture is resource-paused. */
    boolean captureLoaded(
            Key key, Object chunkIdentity, BooleanSupplier isEmpty, Supplier<CompoundTag> serialize) {
        boolean unchanged = capturedChunks.contains(key, chunkIdentity);
        var hint = takeHint(key);
        removeLightWindow(key);
        if (unchanged && (hint == null || !hint.required()))
            return false;
        long startedNs = System.nanoTime();
        try {
            boolean empty = isEmpty.getAsBoolean();
            if (empty && (hint == null || !hint.allowEmpty()))
                return false;
            sample(key, chunkIdentity, serialize.get(), empty);
            return true;
        } catch (Exception e) {
            if (hint != null)
                retainFailedChange(hint);
            diagnostics.unloadFailed();
            WMLogger.warnRateLimited("capture-unload", 30_000L,
                    "Final chunk capture failed chunk=" + key.chunkX() + "," + key.chunkZ()
                            + "; cached data may be stale",
                    e);
            return false;
        } finally {
            if (recordPerformance.getAsBoolean()) {
                long elapsedUs = (System.nanoTime() - startedNs) / 1_000L;
                diagnostics.recordUnload(key, elapsedUs);
            }
        }
    }

    private Pending takeHint(Key key) {
        var hint = pending.remove(key);
        var overflow = overflowChanges.remove(key);
        if (hint != null || overflow != null)
            refreshPendingStatus(key);
        var failed = failedChanges.remove(key);
        Pending queued = hint == null ? overflow : hint;
        return queued != null ? queued : failed;
    }

    private void removeLightWindow(Key key) {
        if (pendingLightUpdates.remove(key) != null)
            refreshPendingStatus(key);
    }

    private static boolean canSample(ClientLevel world, LevelChunk chunk) {
        return RemoteChunkProvenance.canSample(world, chunk.getPos())
                && !SeedChunkTracking.hidden(world, chunk);
    }

    private static Object remoteChunkIdentity(LevelChunk chunk) {
        // Server-side changes to an integrated world can bypass observed packet hooks.
        return Minecraft.getInstance().getSingleplayerServer() == null ? chunk : null;
    }

    private static Key key(ResourceKey<Level> dimension, ChunkPos pos) {
        return new Key(dimension, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
    }

    private void sample(Key key, Object chunkIdentity, CompoundTag nbt, boolean authoritativeEmpty) {
        ChunkListener.addChunkNbt(
                key.dimension(), new ChunkPos(key.chunkX(), key.chunkZ()), nbt, authoritativeEmpty);
        capturedChunks.record(key, chunkIdentity);
    }

    boolean queueObservation(Key key, String reason) {
        return queueChangedObservation(key, reason, false, false);
    }

    private boolean queueChangedObservation(Key key, String reason, boolean allowEmpty, boolean required) {
        capturedChunks.remove(key);
        return queue(key, reason, allowEmpty, required);
    }

    void markLightDirty(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null || SeedChunkTracking.hidden(world, pos))
            return;
        markLightDirty(new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4));
    }

    void markLightDirty(Key key) {
        pendingLightUpdates.putIfAbsent(key, clientTick + LIGHT_UPDATE_COALESCE_TICKS);
        refreshPendingStatus(key);
    }

    boolean queueLoaded(ClientLevel world, int playerCX, int playerCZ, int range, String reason) {
        if (world == null || range <= 0)
            return false;
        // Repeated recoveries need only the latest recovery scan, not an unbounded scan backlog.
        if ("resource-recovery".equals(reason)) {
            scans.removeIf(scan -> scan.world() == world && reason.equals(scan.reason()));
        }
        scans.addLast(new LoadedScan(
                world, new CaptureRegionScan(world.dimension(), playerCX, playerCZ, range), reason));

        return true;
    }

    /** Finishes known payload changes before an explicit stop clears queued observations. */
    void finishPayloadChanges(ClientLevel world) {
        for (Key key : finalPayloadChanges(world.dimension())) {
            ChunkAccess chunk = world.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false);
            if (chunk instanceof LevelChunk loaded && canSample(world, loaded))
                captureLoaded(world, loaded, key);
        }
    }

    Set<Key> finalPayloadChanges(ResourceKey<Level> dimension) {
        Set<Key> result = new LinkedHashSet<>();
        pending.values()
                .stream()
                .filter(Pending::required)
                .map(Pending::key)
                .filter(key -> dimension.equals(key.dimension()))
                .forEach(result::add);
        overflowChanges.keySet()
                .stream()
                .filter(key -> dimension.equals(key.dimension()))
                .forEach(result::add);
        failedChanges.keySet().stream().filter(key -> dimension.equals(key.dimension())).forEach(result::add);
        return result;
    }

    void tick(Minecraft client, CaptureBudget budget) {
        clientTick++;
        flushLightUpdates(false);
        if (budget.maxChunks() > 0)
            processPending(client, budget);
    }

    /** Releases already-applied light revisions before stopping or changing worlds. */
    void finishLightUpdates() {
        flushLightUpdates(true);
    }

    boolean isLightingPending(ResourceKey<Level> dimension, ChunkPos pos) {
        return pendingLightUpdates.containsKey(
                new Key(dimension, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4));
    }

    /** Lighting coalescing is per chunk and must not delay an unrelated live export. */
    boolean hasPendingCaptures() {
        return !pending.isEmpty() || !scans.isEmpty() || !overflowChanges.isEmpty();
    }

    Set<ChunkPos> pendingChunkPositions(
            ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
        return pendingStatus.positions(dimension, minX, maxX, minZ, maxZ);
    }

    long statusVersion(ResourceKey<Level> dimension) {
        return pendingStatus.version(dimension);
    }

    private void refreshPendingStatus(Key key) {
        pendingStatus.update(key,
                pending.containsKey(key) || pendingLightUpdates.containsKey(key)
                        || overflowChanges.containsKey(key));
    }

    boolean hasPendingChunkWork(ResourceKey<Level> dimension, ChunkPos pos) {
        Key key = new Key(dimension, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        return pending.containsKey(key) || pendingLightUpdates.containsKey(key)
                || overflowChanges.containsKey(key) || failedChanges.containsKey(key);
    }

    void clear() {
        pendingStatus.clear();
        pending.clear();
        scans.clear();
        overflowChanges.clear();
        failedChanges.clear();
        pendingLightUpdates.clear();
        capturedChunks.clear();
        reconciliationNeeded = false;
        estimatedCaptureNs = 0L;
    }

    private boolean queue(Key key, String reason, boolean allowEmpty, boolean required) {
        var counters = recordPerformance.getAsBoolean() ? diagnostics.received(reason) : null;
        var failed = failedChanges.remove(key);
        if (failed != null) {
            allowEmpty |= failed.allowEmpty();
            required = true;
        }
        Pending previous = pending.get(key);
        if (previous != null) {
            pending.put(key, combine(previous, allowEmpty, required));
            diagnostics.coalesced(counters);
            return false;
        }
        previous = overflowChanges.get(key);
        if (previous != null) {
            overflowChanges.put(key, combine(previous, allowEmpty, required));
            diagnostics.coalesced(counters);
            return false;
        }
        int limit = Math.max(1, pendingLimit.getAsInt());
        if (pending.size() >= limit) {
            if (required) {
                overflowChanges.put(
                        key, new Pending(key, reason, (System.nanoTime() / 1_000_000L), allowEmpty, true));
                refreshPendingStatus(key);
                diagnostics.coalesced(counters);

                return false;
            }
            diagnostics.dropped(counters);
            reconciliationNeeded = true;
            WMLogger.warnRateLimited("capture-queue-capacity", 30_000L,
                    "Capture-hint queue reached its configured limit (" + limit
                            + "); coalescing overflow and scheduling a loaded-chunk reconciliation.");
            return false;
        }
        pending.put(key, new Pending(key, reason, (System.nanoTime() / 1_000_000L), allowEmpty, required));
        refreshPendingStatus(key);
        diagnostics.queued(counters);

        return true;
    }

    private static Pending combine(Pending previous, boolean allowEmpty, boolean required) {
        if ((!allowEmpty || previous.allowEmpty()) && (!required || previous.required()))
            return previous;
        return new Pending(previous.key(), previous.reason(), previous.queuedAt(),
                previous.allowEmpty() || allowEmpty, previous.required() || required);
    }

    private void processPending(Minecraft client, CaptureBudget budget) {
        ClientLevel world = client.level;
        if (world == null)
            return;
        ResourceKey<Level> currentDimension = world.dimension();
        int processed = 0;
        int captured = 0;
        boolean attemptedCapture = false;
        long startedNs = System.nanoTime();
        long budgetNs = budget.tickNanos();
        boolean diagnostics = recordPerformance.getAsBoolean();
        boolean remote = client.getSingleplayerServer() == null;
        if (budget.scanChecks() > 0 && (!budget.scanOnlyWhenIdle() || pendingHintCount() == 0)) {
            fillLoadedScans(world, remote, startedNs, budgetNs / 4, budget.scanChecks());
        }

        while (processed < budget.maxChunks()) {
            long expectedCost = attemptedCapture ? estimatedCaptureNs : 0L;
            if (processed > 0 && !budget.fitsAnotherCapture(System.nanoTime() - startedNs, expectedCost))
                break;
            Pending request = pollPending();
            if (request == null)
                break;
            processed++;
            if (!currentDimension.equals(request.key().dimension()))
                continue;
            ChunkAccess chunk =
                    world.getChunk(request.key().chunkX(), request.key().chunkZ(), ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk levelChunk)
                    || !RemoteChunkProvenance.canSample(world, request.key().chunkX(), request.key().chunkZ())
                    || SeedChunkTracking.hidden(world, levelChunk))
                continue;

            long captureStartedNs = System.nanoTime();
            boolean serializing = false;
            try {
                boolean empty = ChunkSerializer.isChunkEmpty(levelChunk);
                if (!request.allowEmpty() && empty)
                    continue;
                serializing = true;
                attemptedCapture = true;
                sample(request.key(), remote ? levelChunk : null,
                        ChunkSerializer.serialize(world, levelChunk), request.allowEmpty() && empty);
                captured++;
            } catch (Exception e) {
                retainFailedChange(request);
                WMLogger.warnRateLimited("capture-incremental-" + request.reason(), 30_000L,
                        "Incremental capture failed chunk=" + levelChunk.getPos()
                                + " reason=" + request.reason(),
                        e);
            } finally {
                long elapsedNs = System.nanoTime() - captureStartedNs;
                if (serializing) {
                    // Smooth nearby costs and cap the influence of an isolated pause.
                    long sample = Math.min(elapsedNs, budgetNs);
                    estimatedCaptureNs = estimatedCaptureNs == 0L
                            ? sample
                            : estimatedCaptureNs + (sample - estimatedCaptureNs) / 4;
                }
                if (diagnostics) {
                    long elapsedUs = elapsedNs / 1_000L;
                    this.diagnostics.recordIncremental(request, elapsedUs);
                }
            }
        }

        if (processed > 0 && diagnostics) {
            this.diagnostics.recordTick(processed, captured, System.nanoTime() - startedNs, budgetNs);
        }
        if (!hasWork() && reconciliationNeeded) {
            reconciliationNeeded = false;
            if (client.player != null) {
                queueLoaded(world, client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4,
                        reconciliationRange, "initial-capture");
            }
        }
    }

    void retainFailedChange(Pending request) {
        // Preserve authority for a new hint or the final sample without spinning on a bad payload.
        if (request.required())
            failedChanges.put(request.key(), request);
    }

    private void flushLightUpdates(boolean force) {
        // The packet hook assigned the dirty revision when it accepted the light payload.
        Iterator<Map.Entry<Key, Long>> iterator = pendingLightUpdates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Long> entry = iterator.next();
            if (force || entry.getValue() <= clientTick) {
                Key key = entry.getKey();
                iterator.remove();
                refreshPendingStatus(key);
            }
        }
    }

    boolean hasWork() {
        return !pending.isEmpty() || !pendingLightUpdates.isEmpty() || !scans.isEmpty()
                || !overflowChanges.isEmpty();
    }

    Pending pollPending() {
        promotePayloadChanges();
        var first = pending.pollFirstEntry();
        if (first != null)
            refreshPendingStatus(first.getKey());

        return first == null ? null : first.getValue();
    }

    private int pendingHintCount() {
        return pending.size() + overflowChanges.size();
    }

    private void fillLoadedScans(
            ClientLevel world, boolean remote, long startedNs, long budgetNs, int maxChecks) {
        promotePayloadChanges();
        while (!scans.isEmpty() && scans.peekFirst().world() != world) scans.removeFirst();
        if (scans.isEmpty()) {
            return;
        }
        LoadedScan scan = scans.peekFirst();
        scan.coordinates().fill(maxChecks,
                ()
                        -> pending.size() < Math.max(1, pendingLimit.getAsInt())
                        && System.nanoTime() - startedNs < budgetNs,
                key -> enqueueLoaded(world, remote, scan.reason(), key));
        if (scan.coordinates().isComplete())
            scans.removeFirst();
    }

    private boolean enqueueLoaded(ClientLevel world, boolean remote, String reason, Key key) {
        if (!(world.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false) instanceof LevelChunk chunk)
                || !canSample(world, chunk))
            return false;
        return queueScannedChunk(key, remote ? chunk : null, reason);
    }

    boolean queueScannedChunk(Key key, Object chunkIdentity, String reason) {
        // Manual export can run with downloading disabled, so refresh its loaded terrain.
        return ("pre-export".equals(reason) || !capturedChunks.contains(key, chunkIdentity))
                && queue(key, reason, false, false);
    }

    private void promotePayloadChanges() {
        if (overflowChanges.isEmpty())
            return;
        int limit = Math.max(1, pendingLimit.getAsInt());
        Iterator<Map.Entry<Key, Pending>> overflow = overflowChanges.entrySet().iterator();
        while (overflow.hasNext() && pending.size() < limit) {
            var entry = overflow.next();
            pending.put(entry.getKey(), entry.getValue());
            overflow.remove();
        }
    }
}
