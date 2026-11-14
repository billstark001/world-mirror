package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.ChunkRegionIndex;
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
import java.util.HashSet;
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
    record Pending(Key key, String reason, long queuedAt, boolean allowEmpty) {}
    private record LoadedScan(ClientLevel world, CaptureRegionScan coordinates, String reason) {}

    record Metrics(int pendingCaptures, long oldestCaptureAgeMs, long coalescedHints, long droppedHints) {}

    private final LinkedHashMap<Key, Pending> pending = new LinkedHashMap<>();
    private final ArrayDeque<LoadedScan> scans = new ArrayDeque<>();
    // Only authoritative changes in currently loaded chunks spill beyond the
    // bounded observation queue. They must survive capacity pressure and unload.
    private final Set<Key> overflowBlockChanges = new LinkedHashSet<>();
    private final Map<Key, Long> pendingLightUpdates = new HashMap<>();
    private final Map<ResourceKey<Level>, ChunkRegionIndex<Key>> pendingStatus = new HashMap<>();
    private long statusVersion;
    private final Map<ResourceKey<Level>, Long> statusVersions = new HashMap<>();
    private long resetVersion;
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
        var first = pending.firstEntry();
        Pending oldest = first == null ? null : first.getValue();
        long age = oldest == null ? 0L : Math.max(0L, (System.nanoTime() / 1_000_000L) - oldest.queuedAt());
        return new Metrics(pending.size() + overflowBlockChanges.size(), age, diagnostics.coalescedHints(),
                diagnostics.droppedHints());
    }

    void queueChunk(ClientLevel world, ChunkPos pos, String reason) {
        if (world == null || pos == null || SeedChunkTracking.hidden(world, pos))
            return;
        queue(new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4), reason, false);
    }

    void queueBlockChange(ClientLevel world, ChunkPos pos, String reason) {
        if (world == null || pos == null || SeedChunkTracking.hidden(world, pos))
            return;
        if (!(world.getChunk(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4, ChunkStatus.FULL, false)
                            instanceof LevelChunk))
            return;
        Key key = new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        queue(key, reason, true);
    }

    void captureBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (world == null || chunk == null)
            return;
        Key key = new Key(
                world.dimension(), chunk.getPos().getMinBlockX() >> 4, chunk.getPos().getMinBlockZ() >> 4);
        if (SeedChunkTracking.hidden(world, chunk)) {
            pending.remove(key);
            overflowBlockChanges.remove(key);
            pendingLightUpdates.remove(key);
            refreshPendingStatus(key);
            return;
        }
        captureBeforeUnload(key,
                () -> ChunkSerializer.isChunkEmpty(chunk), () -> ChunkSerializer.serialize(world, chunk));
    }

    /** The unload path remains available while ordinary tick capture is resource-paused. */
    boolean captureBeforeUnload(Key key, BooleanSupplier isEmpty, Supplier<CompoundTag> serialize) {
        long startedNs = System.nanoTime();
        ChunkPos pos = new ChunkPos(key.chunkX(), key.chunkZ());
        Pending lastHint = pending.remove(key);
        boolean authoritative =
                overflowBlockChanges.remove(key) || (lastHint != null && lastHint.allowEmpty());
        boolean pendingLight = pendingLightUpdates.remove(key) != null;
        refreshPendingStatus(key);

        boolean captured = false;
        try {
            boolean empty = isEmpty.getAsBoolean();
            if (authoritative || !empty) {
                ChunkListener.addChunkNbt(key.dimension(), pos, serialize.get(), authoritative && empty);
                captured = true;
            }
        } catch (Exception e) {
            diagnostics.unloadFailed();
            WMLogger.warnRateLimited("capture-unload", 30_000L,
                    "Final capture before unload failed chunk=" + pos + "; cached data may be stale", e);
        } finally {
            if (pendingLight && !captured) {
                ChunkListener.markChunkDirty(key.dimension(), new ChunkPos(key.chunkX(), key.chunkZ()));
            }
            if (recordPerformance.getAsBoolean()) {
                long elapsedUs = (System.nanoTime() - startedNs) / 1_000L;
                diagnostics.recordUnload(key, elapsedUs);
            }
        }
        return captured;
    }

    void queueLightUpdate(ClientLevel world, ChunkPos pos) {
        queueChunk(world, pos, "light-update-no-base");
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

    /** Finishes known block changes before an explicit stop clears queued observations. */
    void finishBlockChanges(ClientLevel world) {
        for (Key key : finalBlockChanges(world.dimension())) {
            ChunkAccess chunk = world.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false);
            if (chunk instanceof LevelChunk loaded)
                captureBeforeUnload(world, loaded);
        }
    }

    Set<Key> finalBlockChanges(ResourceKey<Level> dimension) {
        Set<Key> result = new LinkedHashSet<>();
        pending.values()
                .stream()
                .filter(Pending::allowEmpty)
                .map(Pending::key)
                .filter(key -> dimension.equals(key.dimension()))
                .forEach(result::add);
        overflowBlockChanges.stream().filter(key -> dimension.equals(key.dimension())).forEach(result::add);
        return result;
    }

    void tick(Minecraft client, CaptureBudget budget) {
        clientTick++;
        flushLightUpdates(false);
        if (budget.maxChunks() > 0)
            processPending(client, budget);
    }

    /** Marks already-applied light changes dirty before stopping or changing worlds. */
    void finishLightUpdates() {
        flushLightUpdates(true);
    }

    boolean isLightingPending(ResourceKey<Level> dimension, ChunkPos pos) {
        return pendingLightUpdates.containsKey(
                new Key(dimension, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4));
    }

    /** Lighting coalescing is per chunk and must not delay an unrelated live export. */
    boolean hasPendingCaptures() {
        return !pending.isEmpty() || !scans.isEmpty() || !overflowBlockChanges.isEmpty();
    }

    Set<ChunkPos> pendingChunkPositions(
            ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
        Set<ChunkPos> result = new HashSet<>();
        ChunkRegionIndex<Key> index = pendingStatus.get(dimension);
        if (index != null)
            index.forEachInRange(
                    minX, maxX, minZ, maxZ, key -> result.add(new ChunkPos(key.chunkX(), key.chunkZ())));

        return result;
    }

    long statusVersion(ResourceKey<Level> dimension) {
        return statusVersions.getOrDefault(dimension, resetVersion);
    }

    private void refreshPendingStatus(Key key) {
        ChunkRegionIndex<Key> index =
                pendingStatus.computeIfAbsent(key.dimension(), ignored -> new ChunkRegionIndex<>());
        boolean changed = pending.containsKey(key) || pendingLightUpdates.containsKey(key)
                        || overflowBlockChanges.contains(key)
                ? index.put(key.chunkX(), key.chunkZ(), key)
                : index.remove(key.chunkX(), key.chunkZ());
        if (changed)
            statusVersions.put(key.dimension(), ++statusVersion);
    }

    boolean hasPendingChunkWork(ResourceKey<Level> dimension, ChunkPos pos) {
        Key key = new Key(dimension, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        return pending.containsKey(key) || pendingLightUpdates.containsKey(key)
                || overflowBlockChanges.contains(key);
    }

    void clear() {
        pendingStatus.clear();
        statusVersions.clear();
        resetVersion = ++statusVersion;
        pending.clear();
        scans.clear();
        overflowBlockChanges.clear();
        pendingLightUpdates.clear();
        reconciliationNeeded = false;
        estimatedCaptureNs = 0L;
    }

    boolean queue(Key key, String reason, boolean allowEmpty) {
        var counters = recordPerformance.getAsBoolean() ? diagnostics.received(reason) : null;
        Pending previous = pending.get(key);
        if (previous != null) {
            if (allowEmpty && !previous.allowEmpty()) {
                pending.put(key, new Pending(key, previous.reason(), previous.queuedAt(), true));
            }
            diagnostics.coalesced(counters);
            return false;
        }
        if (overflowBlockChanges.contains(key)) {
            diagnostics.coalesced(counters);
            return false;
        }
        int limit = Math.max(1, pendingLimit.getAsInt());
        if (pending.size() >= limit) {
            if (allowEmpty) {
                overflowBlockChanges.add(key);
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
        pending.put(key, new Pending(key, reason, (System.nanoTime() / 1_000_000L), allowEmpty));
        refreshPendingStatus(key);
        diagnostics.queued(counters);

        return true;
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
        if (budget.scanChecks() > 0 && (!budget.scanOnlyWhenIdle() || pendingHintCount() == 0)) {
            fillLoadedScans(world, startedNs, budgetNs / 4, budget.scanChecks());
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
            if (!(chunk instanceof LevelChunk levelChunk) || SeedChunkTracking.hidden(world, levelChunk))
                continue;

            long captureStartedNs = System.nanoTime();
            boolean serializing = false;
            try {
                boolean empty = ChunkSerializer.isChunkEmpty(levelChunk);
                if (!request.allowEmpty() && empty)
                    continue;
                serializing = true;
                attemptedCapture = true;
                CompoundTag nbt = ChunkSerializer.serialize(world, levelChunk);
                ChunkListener.addChunkNbt(
                        request.key().dimension(), levelChunk.getPos(), nbt, request.allowEmpty() && empty);
                captured++;
            } catch (Exception e) {
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

    private void flushLightUpdates(boolean force) {
        Iterator<Map.Entry<Key, Long>> iterator = pendingLightUpdates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Long> entry = iterator.next();
            if (force || entry.getValue() <= clientTick) {
                Key key = entry.getKey();
                iterator.remove();
                refreshPendingStatus(key);
                ChunkListener.markChunkDirty(key.dimension(), new ChunkPos(key.chunkX(), key.chunkZ()));
            }
        }
    }

    boolean hasWork() {
        return !pending.isEmpty() || !pendingLightUpdates.isEmpty() || !scans.isEmpty()
                || !overflowBlockChanges.isEmpty();
    }

    Pending pollPending() {
        promoteBlockChanges();
        var first = pending.pollFirstEntry();
        if (first != null)
            refreshPendingStatus(first.getKey());

        return first == null ? null : first.getValue();
    }

    private int pendingHintCount() {
        return pending.size() + overflowBlockChanges.size();
    }

    private void fillLoadedScans(ClientLevel world, long startedNs, long budgetNs, int maxChecks) {
        promoteBlockChanges();
        while (!scans.isEmpty() && scans.peekFirst().world() != world) scans.removeFirst();
        if (scans.isEmpty()) {
            return;
        }
        LoadedScan scan = scans.peekFirst();
        scan.coordinates().fill(maxChecks,
                ()
                        -> pending.size() < Math.max(1, pendingLimit.getAsInt())
                        && System.nanoTime() - startedNs < budgetNs,
                key
                -> world.getChunk(key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false)
                                        instanceof LevelChunk chunk
                        && !SeedChunkTracking.hidden(world, chunk) && queue(key, scan.reason(), false));
        if (scan.coordinates().isComplete())
            scans.removeFirst();
    }

    private void promoteBlockChanges() {
        if (overflowBlockChanges.isEmpty())
            return;
        int limit = Math.max(1, pendingLimit.getAsInt());
        Iterator<Key> overflow = overflowBlockChanges.iterator();
        while (overflow.hasNext() && pending.size() < limit) {
            Key key = overflow.next();
            overflow.remove();
            queue(key, "block-change-overflow", true);
        }
    }
}
