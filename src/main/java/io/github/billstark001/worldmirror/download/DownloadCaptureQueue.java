package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Main-thread chunk capture queue, light coalescing, and capture diagnostics. */
final class DownloadCaptureQueue {
    private static final int MAX_CAPTURE_CHUNKS_PER_TICK = 64;
    private static final int MAX_DIAGNOSTIC_CAPTURE_REASONS = 32;
    private static final int LIGHT_UPDATE_COALESCE_TICKS = 2;
    private static final int MAX_SCAN_CHECKS_PER_TICK = 256;

    record Key(ResourceKey<Level> dimension, int chunkX, int chunkZ) { }
    record Pending(Key key, String reason, long enqueuedAtMs, boolean allowEmpty) { }
    private record LoadedScan(ClientLevel world, CaptureRegionScan coordinates, String reason) { }

    record Metrics(int pendingCaptures, long oldestCaptureAgeMs,
                   long coalescedHints, long droppedHints) { }

    record DiagnosticSnapshot(
            LatencyWindow.Summary captureTickLatency,
            LatencyWindow.Summary unloadLatency,
            long processed,
            long completed,
            long budgetOverruns,
            long maximumBudgetOverrunUs,
            long unloadFailures,
            String hintSummary,
            String reasonLatencySummary) { }

    private static final class HintCounters {
        private long received;
        private long queued;
        private long coalesced;
        private long dropped;
    }

    private final Object lock = new Object();
    private final LinkedHashMap<Key, Pending> pending = new LinkedHashMap<>();
    private final ArrayDeque<LoadedScan> scans = new ArrayDeque<>();
    // Only authoritative changes in currently loaded chunks spill beyond the
    // bounded observation queue. They must survive capacity pressure and unload.
    private final Set<Key> overflowBlockChanges = new LinkedHashSet<>();
    private final Map<Key, Long> pendingLightUpdates = new HashMap<>();
    private final AtomicBoolean reconciliationNeeded = new AtomicBoolean();
    private final AtomicBoolean captureInProgress = new AtomicBoolean();
    private final BooleanSupplier recordPerformance;
    private final int reconciliationRange;
    private final IntSupplier pendingLimit;

    private long clientTick;
    private long coalescedHints;
    private long droppedHints;
    private final Map<String, HintCounters> hintsByReason = new HashMap<>();
    private final Map<String, LatencyWindow> latenciesByReason = new HashMap<>();
    private final LatencyWindow captureTickLatencies = new LatencyWindow(4096);
    private final LatencyWindow unloadLatencies = new LatencyWindow(4096);
    private final AtomicLong unloadFailures = new AtomicLong();
    private final AtomicLong budgetOverruns = new AtomicLong();
    private final AtomicLong maximumBudgetOverrunUs = new AtomicLong();
    private final AtomicLong processedCaptures = new AtomicLong();
    private final AtomicLong completedCaptures = new AtomicLong();

    DownloadCaptureQueue(BooleanSupplier recordPerformance, int reconciliationRange) {
        this(recordPerformance, reconciliationRange, () -> ModConfig.get().performance.maxPendingCaptureHints);
    }

    DownloadCaptureQueue(BooleanSupplier recordPerformance, int reconciliationRange, IntSupplier pendingLimit) {
        this.recordPerformance = recordPerformance;
        this.reconciliationRange = reconciliationRange;
        this.pendingLimit = pendingLimit;
    }

    boolean isCaptureInProgress() {
        return captureInProgress.get();
    }

    Metrics metrics() {
        synchronized (lock) {
            var first = pending.firstEntry();
            Pending oldest = first == null ? null : first.getValue();
            long age = oldest == null ? 0L
                    : Math.max(0L, System.currentTimeMillis() - oldest.enqueuedAtMs());
            return new Metrics(pending.size() + overflowBlockChanges.size(), age, coalescedHints, droppedHints);
        }
    }

    void queueChunk(ClientLevel world, ChunkPos pos, String reason) {
        if (world == null || pos == null) return;
        queue(new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4), reason);
    }

    void queueBlockChange(ClientLevel world, ChunkPos pos, String reason) {
        if (world == null || pos == null) return;
        if (!(world.getChunk(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4,
                ChunkStatus.FULL, false) instanceof LevelChunk)) return;
        Key key = new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        queue(key, reason, true);
    }

    void captureBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (world == null || chunk == null) return;
        long startedNs = System.nanoTime();
        Key key = new Key(world.dimension(), chunk.getPos().getMinBlockX() >> 4,
                chunk.getPos().getMinBlockZ() >> 4);
        boolean pendingLight;
        Pending lastHint;
        boolean authoritative;
        synchronized (lock) {
            lastHint = pending.remove(key);
            authoritative = overflowBlockChanges.remove(key) || (lastHint != null && lastHint.allowEmpty());
            pendingLight = pendingLightUpdates.remove(key) != null;
            captureInProgress.set(hasWorkLocked());
        }
        boolean captured = false;
        try {
            boolean empty = ChunkSerializer.isChunkEmpty(chunk);
            if (authoritative || !empty) {
                ChunkListener.addChunkNbt(world.dimension(), chunk.getPos(),
                        ChunkSerializer.serialize(world, chunk), authoritative && empty);
                captured = true;
            }
        } catch (Exception e) {
            unloadFailures.incrementAndGet();
            WMLogger.warnRateLimited("capture-unload", 30_000L,
                    "Final capture before unload failed chunk=" + chunk.getPos()
                            + "; cached data may be stale", e);
        } finally {
            if (pendingLight && !captured) {
                ChunkListener.markChunkDirty(key.dimension(), new ChunkPos(key.chunkX(), key.chunkZ()));
            }
            if (recordPerformance.getAsBoolean()) {
                long elapsedUs = (System.nanoTime() - startedNs) / 1_000L;
                unloadLatencies.record(elapsedUs);
                recordReasonLatency("unload-final", elapsedUs);
                logSlowCapture("unload", "unload-final", key, 0L, elapsedUs);
            }
        }
    }

    void queueLightUpdate(ClientLevel world, ChunkPos pos) {
        queueChunk(world, pos, "light-update-no-base");
    }

    void markLightDirty(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;
        markLightDirty(new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4));
    }

    void markLightDirty(Key key) {
        synchronized (lock) {
            pendingLightUpdates.putIfAbsent(key, clientTick + LIGHT_UPDATE_COALESCE_TICKS);
            captureInProgress.set(hasWorkLocked());
        }
    }

    boolean queueLoaded(ClientLevel world, int playerCX, int playerCZ, int range, String reason) {
        if (world == null || range <= 0) return false;
        synchronized (lock) {
            scans.addLast(new LoadedScan(world,
                    new CaptureRegionScan(world.dimension(), playerCX, playerCZ, range), reason));
            captureInProgress.set(true);
        }
        return true;
    }

    /** Finishes known block changes before an explicit stop clears queued observations. */
    void finishBlockChanges(ClientLevel world) {
        for (Key key : finalBlockChanges(world.dimension())) {
            ChunkAccess chunk = world.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false);
            if (chunk instanceof LevelChunk loaded) captureBeforeUnload(world, loaded);
        }
    }

    Set<Key> finalBlockChanges(ResourceKey<Level> dimension) {
        synchronized (lock) {
            Set<Key> result = new LinkedHashSet<>();
            pending.values().stream().filter(Pending::allowEmpty).map(Pending::key)
                    .filter(key -> dimension.equals(key.dimension())).forEach(result::add);
            overflowBlockChanges.stream().filter(key -> dimension.equals(key.dimension())).forEach(result::add);
            return result;
        }
    }

    void tick(Minecraft client) {
        clientTick++;
        flushLightUpdates(false);
        processPending(client);
    }

    /** Marks already-applied light changes dirty before stopping or changing worlds. */
    void finishLightUpdates() {
        flushLightUpdates(true);
    }

    boolean hasPendingLightUpdates() {
        synchronized (lock) {
            return !pendingLightUpdates.isEmpty();
        }
    }

    Set<ChunkPos> pendingChunkPositions(ResourceKey<Level> dimension,
                                        int minX, int maxX, int minZ, int maxZ) {
        Set<ChunkPos> result = new HashSet<>();
        synchronized (lock) {
            for (Pending request : pending.values()) {
                Key key = request.key();
                if (dimension.equals(key.dimension()) && key.chunkX() >= minX && key.chunkX() <= maxX
                        && key.chunkZ() >= minZ && key.chunkZ() <= maxZ) {
                    result.add(new ChunkPos(key.chunkX(), key.chunkZ()));
                }
            }
            for (Key key : pendingLightUpdates.keySet()) {
                if (dimension.equals(key.dimension()) && key.chunkX() >= minX && key.chunkX() <= maxX
                        && key.chunkZ() >= minZ && key.chunkZ() <= maxZ) {
                    result.add(new ChunkPos(key.chunkX(), key.chunkZ()));
                }
            }
            for (Key key : overflowBlockChanges) {
                if (dimension.equals(key.dimension()) && key.chunkX() >= minX && key.chunkX() <= maxX
                        && key.chunkZ() >= minZ && key.chunkZ() <= maxZ) {
                    result.add(new ChunkPos(key.chunkX(), key.chunkZ()));
                }
            }
        }
        return result;
    }

    boolean hasWork() {
        synchronized (lock) {
            return hasWorkLocked();
        }
    }

    boolean hasPendingChunkWork(ResourceKey<Level> dimension, ChunkPos pos) {
        Key key = new Key(dimension, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        synchronized (lock) {
            return pending.containsKey(key) || pendingLightUpdates.containsKey(key) || overflowBlockChanges.contains(key);
        }
    }

    void clear() {
        synchronized (lock) {
            pending.clear();
            scans.clear();
            overflowBlockChanges.clear();
            pendingLightUpdates.clear();
            reconciliationNeeded.set(false);
            captureInProgress.set(false);
        }
    }

    void resetDiagnostics() {
        synchronized (lock) {
            coalescedHints = 0L;
            droppedHints = 0L;
            hintsByReason.clear();
            latenciesByReason.clear();
        }
        captureTickLatencies.reset();
        unloadLatencies.reset();
        unloadFailures.set(0L);
        budgetOverruns.set(0L);
        maximumBudgetOverrunUs.set(0L);
        processedCaptures.set(0L);
        completedCaptures.set(0L);
    }

    DiagnosticSnapshot snapshotDiagnostics() {
        return new DiagnosticSnapshot(
                captureTickLatencies.snapshotAndReset(),
                unloadLatencies.snapshotAndReset(),
                processedCaptures.getAndSet(0L),
                completedCaptures.getAndSet(0L),
                budgetOverruns.getAndSet(0L),
                maximumBudgetOverrunUs.getAndSet(0L),
                unloadFailures.get(),
                hintSummary(),
                reasonLatencySummary());
    }

    private boolean queue(Key key, String reason) {
        return queue(key, reason, false);
    }

    boolean queue(Key key, String reason, boolean allowEmpty) {
        synchronized (lock) {
            HintCounters counters = null;
            if (recordPerformance.getAsBoolean()) {
                String metricReason = metricReasonLocked(reason, hintsByReason);
                counters = hintsByReason.computeIfAbsent(metricReason, ignored -> new HintCounters());
                counters.received++;
            }
            Pending previous = pending.get(key);
            if (previous != null) {
                if (allowEmpty && !previous.allowEmpty()) {
                    pending.put(key, new Pending(key, previous.reason(), previous.enqueuedAtMs(), true));
                }
                coalescedHints++;
                if (counters != null) counters.coalesced++;
                return false;
            }
            if (overflowBlockChanges.contains(key)) {
                coalescedHints++;
                if (counters != null) counters.coalesced++;
                return false;
            }
            int limit = Math.max(1, pendingLimit.getAsInt());
            if (pending.size() >= limit) {
                if (allowEmpty) {
                    overflowBlockChanges.add(key);
                    coalescedHints++;
                    if (counters != null) counters.coalesced++;
                    captureInProgress.set(true);
                    return false;
                }
                droppedHints++;
                if (counters != null) counters.dropped++;
                reconciliationNeeded.set(true);
                WMLogger.warnRateLimited("capture-queue-capacity", 30_000L,
                        "Capture-hint queue reached its configured limit (" + limit
                                + "); coalescing overflow and scheduling a loaded-chunk reconciliation.");
                return false;
            }
            pending.put(key, new Pending(key, reason, System.currentTimeMillis(), allowEmpty));
            if (counters != null) counters.queued++;
            captureInProgress.set(true);
            return true;
        }
    }

    private void processPending(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null) return;
        ResourceKey<Level> currentDimension = world.dimension();
        int processed = 0;
        int captured = 0;
        long startedNs = System.nanoTime();
        long budgetNs = Math.max(250L, ModConfig.get().performance.captureBudgetMicros) * 1_000L;
        fillLoadedScans(world, startedNs, budgetNs);

        while (processed < MAX_CAPTURE_CHUNKS_PER_TICK
                && (processed == 0 || System.nanoTime() - startedNs < budgetNs)) {
            Pending request;
            request = pollPending();
            if (request == null) break;
            processed++;
            if (!currentDimension.equals(request.key().dimension())) continue;
            ChunkAccess chunk = world.getChunk(request.key().chunkX(), request.key().chunkZ(),
                    ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk levelChunk)) continue;

            long captureStartedNs = System.nanoTime();
            try {
                boolean empty = ChunkSerializer.isChunkEmpty(levelChunk);
                if (!request.allowEmpty() && empty) continue;
                CompoundTag nbt = ChunkSerializer.serialize(world, levelChunk);
                ChunkListener.addChunkNbt(request.key().dimension(), levelChunk.getPos(), nbt,
                        request.allowEmpty() && empty);
                captured++;
            } catch (Exception e) {
                WMLogger.warnRateLimited("capture-incremental-" + request.reason(), 30_000L,
                        "Incremental capture failed chunk=" + levelChunk.getPos()
                                + " reason=" + request.reason(), e);
            } finally {
                if (recordPerformance.getAsBoolean()) {
                    long elapsedUs = (System.nanoTime() - captureStartedNs) / 1_000L;
                    String metricReason = recordReasonLatency(request.reason(), elapsedUs);
                    logSlowCapture("incremental", metricReason, request.key(),
                            Math.max(0L, System.currentTimeMillis() - request.enqueuedAtMs()), elapsedUs);
                }
            }
        }

        if (processed > 0 && recordPerformance.getAsBoolean()) {
            long elapsedNs = System.nanoTime() - startedNs;
            captureTickLatencies.record(elapsedNs / 1_000L);
            processedCaptures.addAndGet(processed);
            completedCaptures.addAndGet(captured);
            if (elapsedNs > budgetNs) {
                budgetOverruns.incrementAndGet();
                maximumBudgetOverrunUs.accumulateAndGet((elapsedNs - budgetNs) / 1_000L, Math::max);
            }
        }
        if (!captureInProgress.get() && reconciliationNeeded.compareAndSet(true, false)) {
            if (client.player != null) {
                queueLoaded(world, client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4,
                        reconciliationRange, "initial-capture");
            }
        }
    }

    private void flushLightUpdates(boolean force) {
        List<Key> ready = new ArrayList<>();
        synchronized (lock) {
            Iterator<Map.Entry<Key, Long>> iterator = pendingLightUpdates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Key, Long> entry = iterator.next();
                if (force || entry.getValue() <= clientTick) {
                    ready.add(entry.getKey());
                    iterator.remove();
                }
            }
            captureInProgress.set(hasWorkLocked());
        }
        for (Key key : ready) {
            ChunkListener.markChunkDirty(key.dimension(), new ChunkPos(key.chunkX(), key.chunkZ()));
        }
    }

    private boolean hasWorkLocked() {
        return !pending.isEmpty() || !pendingLightUpdates.isEmpty() || !scans.isEmpty()
                || !overflowBlockChanges.isEmpty();
    }

    Pending pollPending() {
        synchronized (lock) {
            promoteBlockChangesLocked();
            var first = pending.pollFirstEntry();
            captureInProgress.set(hasWorkLocked());
            return first == null ? null : first.getValue();
        }
    }

    private void fillLoadedScans(ClientLevel world, long startedNs, long budgetNs) {
        synchronized (lock) {
            promoteBlockChangesLocked();
            while (!scans.isEmpty() && scans.peekFirst().world() != world) scans.removeFirst();
            if (scans.isEmpty()) {
                captureInProgress.set(hasWorkLocked());
                return;
            }
            LoadedScan scan = scans.peekFirst();
            scan.coordinates().fill(MAX_SCAN_CHECKS_PER_TICK,
                    () -> pending.size() < Math.max(1, pendingLimit.getAsInt())
                            && System.nanoTime() - startedNs < budgetNs,
                    key -> world.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false) instanceof LevelChunk
                            && queue(key, scan.reason()));
            if (scan.coordinates().isComplete()) scans.removeFirst();
            captureInProgress.set(hasWorkLocked());
        }
    }

    private void promoteBlockChangesLocked() {
        Iterator<Key> overflow = overflowBlockChanges.iterator();
        while (overflow.hasNext() && pending.size() < Math.max(1, pendingLimit.getAsInt())) {
            Key key = overflow.next();
            overflow.remove();
            queue(key, "block-change-overflow", true);
        }
    }

    private String recordReasonLatency(String reason, long elapsedUs) {
        synchronized (lock) {
            String metricReason = metricReasonLocked(reason, latenciesByReason);
            latenciesByReason.computeIfAbsent(metricReason, ignored -> new LatencyWindow(512))
                    .record(elapsedUs);
            return metricReason;
        }
    }

    private static String metricReasonLocked(String reason, Map<String, ?> metrics) {
        String normalized = reason == null || reason.isBlank() ? "unknown" : reason;
        if (metrics.containsKey(normalized)) return normalized;
        return metrics.size() < MAX_DIAGNOSTIC_CAPTURE_REASONS - 1 ? normalized : "other";
    }

    private void logSlowCapture(String origin, String reason, Key key,
                                long queueAgeMs, long elapsedUs) {
        long thresholdUs = Math.max(5_000L,
                (long) ModConfig.get().performance.captureBudgetMicros * 4L);
        if (elapsedUs < thresholdUs) return;
        WMLogger.infoRateLimited("slow-capture-" + origin + '-' + reason, 30_000L,
                "[perf] slowCapture origin=" + origin
                        + " reason=" + reason
                        + " dimension=" + key.dimension().identifier()
                        + " chunk=" + key.chunkX() + ',' + key.chunkZ()
                        + " queueAgeMs=" + queueAgeMs
                        + " elapsedUs=" + elapsedUs
                        + " thresholdUs=" + thresholdUs);
    }

    private String hintSummary() {
        synchronized (lock) {
            if (hintsByReason.isEmpty()) return "none";
            List<Map.Entry<String, HintCounters>> entries = new ArrayList<>(hintsByReason.entrySet());
            entries.sort((left, right) -> Long.compare(
                    right.getValue().received, left.getValue().received));
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, HintCounters> entry : entries) {
                if (summary.length() > 0) summary.append('|');
                HintCounters counters = entry.getValue();
                summary.append(entry.getKey()).append(':').append(counters.received)
                        .append('/').append(counters.queued)
                        .append('/').append(counters.coalesced)
                        .append('/').append(counters.dropped);
            }
            return summary.toString();
        }
    }

    private String reasonLatencySummary() {
        synchronized (lock) {
            if (latenciesByReason.isEmpty()) return "none";
            List<Map.Entry<String, LatencyWindow.Summary>> entries = new ArrayList<>();
            latenciesByReason.forEach((reason, latency) ->
                    entries.add(Map.entry(reason, latency.snapshotAndReset())));
            entries.removeIf(entry -> entry.getValue().observations() == 0L);
            if (entries.isEmpty()) return "none";
            entries.sort((left, right) -> Long.compare(
                    right.getValue().observations(), left.getValue().observations()));
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, LatencyWindow.Summary> entry : entries) {
                if (summary.length() > 0) summary.append('|');
                LatencyWindow.Summary latency = entry.getValue();
                summary.append(entry.getKey()).append(':').append(latency.observations())
                        .append('/').append(latency.average())
                        .append('/').append(latency.p95())
                        .append('/').append(latency.p99())
                        .append('/').append(latency.maximum());
            }
            return summary.toString();
        }
    }
}
