package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.conflict.IgnoreResolver;
import io.github.billstark001.worldmirror.conflict.ManualResolver;
import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.download.ExportWork.*;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ExportProgress;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Serializes export requests and owns the complete background durability transaction. */
final class DownloadExportCoordinator {
    enum Trigger {
        MANUAL(false, 2),
        STOP(false, 3),
        RETRY(false, 1),
        PERIODIC(true, 0),
        ADAPTIVE_HIGH_WATERMARK(true, 0),
        ADAPTIVE_MAX_LATENCY(true, 0);

        private final boolean automatic;
        private final int priority;

        Trigger(boolean automatic, int priority) {
            this.automatic = automatic;
            this.priority = priority;
        }
    }

    record Metrics(long lastExportMillis, int lastWritten, int lastSettled,
                   int lastUnreadable, long failures) { }

    record DiagnosticSnapshot(ChunkExporter.ExportTimings timings,
                              long durabilityIndexMillis,
                              long workerWallMillis,
                              long automaticSuppressed,
                              long deferredCoalesced) { }

    private final DownloadCaptureQueue captureQueue;
    private final Supplier<DownloadPipeline> pipelineSupplier;
    private final Consumer<Runnable> clientExecutor;
    private final AtomicBoolean inProgress = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicLong entityRevision = new AtomicLong(1L);
    private final AtomicLong durableEntityRevision = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong automaticSuppressed = new AtomicLong();
    private final AtomicLong deferredCoalesced = new AtomicLong();
    private final AtomicLong workerWallMillis = new AtomicLong();
    private final Object requestLock = new Object();
    private final DeferredRequests deferred = new DeferredRequests();

    /** Source FIFO is enforced independently for terrain and entity revisions. Guarded by requestLock. */
    static final class DeferredRequests {
        private static final class Scheduled {
            private Frozen work;
            private long readyAtNs;
            Scheduled(Frozen work, long readyAtNs) { this.work = work; this.readyAtNs = readyAtNs; }
        }
        private final ArrayDeque<Scheduled> frozen = new ArrayDeque<>();
        private Intent live;
        boolean offer(ExportWork work) {
            if (work instanceof Frozen prepared) {
                frozen.addLast(new Scheduled(prepared, Long.MIN_VALUE));
                return false;
            }
            Intent intent = (Intent) work;
            boolean coalesced = live != null && live.context().source().equals(intent.context().source());
            live = coalesced ? mergeIntents(live, intent) : intent;
            return coalesced;
        }
        ExportWork poll(boolean capturesPending) { return poll(capturesPending, System.nanoTime()); }
        ExportWork poll(boolean capturesPending, long nowNs) {
            Set<Source> blockedTerrain = new HashSet<>(), blockedEntities = new HashSet<>();
            Iterator<Scheduled> iterator = frozen.iterator();
            while (iterator.hasNext()) {
                Scheduled scheduled = iterator.next();
                Frozen work = scheduled.work;
                Source source = work.context().source();
                boolean ready = scheduled.readyAtNs == Long.MIN_VALUE || nowNs - scheduled.readyAtNs >= 0;
                if (ready && !(work.hasTerrain() && blockedTerrain.contains(source))) {
                    if (work.hasEntities() && blockedEntities.contains(source)) {
                        if (work.hasTerrain()) { scheduled.work = work.entitiesOnly(); return work.terrainOnly(); }
                    } else { iterator.remove(); return work; }
                }
                if (work.hasTerrain()) blockedTerrain.add(source);
                if (work.hasEntities()) blockedEntities.add(source);
            }
            if (capturesPending || live == null || blockedTerrain.contains(live.context().source())) return null;
            Intent work = live;
            live = null;
            return blockedEntities.contains(work.context().source()) ? work.terrainOnly() : work;
        }
        void retry(Frozen work, long readyAtNs) { frozen.addFirst(new Scheduled(work, readyAtNs)); }
        boolean hasTerrainForSource(Source source) { return frozen.stream().anyMatch(item -> source.equals(item.work.context().source()) && item.work.hasTerrain()); }
        boolean hasEntitiesForSource(Source source) { return frozen.stream().anyMatch(item -> source.equals(item.work.context().source()) && item.work.hasEntities()); }
        void clearLive() { live = null; }
        void prepareForShutdown() { frozen.forEach(item -> item.readyAtNs = Long.MIN_VALUE); clearLive(); }
        int frozenCount() { return frozen.size(); }
    }

    private volatile long lastExportMillis;
    private volatile int lastWritten;
    private volatile int lastSettled;
    private volatile int lastUnreadable;
    private volatile long lastDurabilityIndexMillis;
    private volatile ChunkExporter.ExportTimings lastTimings = emptyTimings();

    private final ThreadPoolExecutor executor;

    static ThreadPoolExecutor createExportExecutor() {
        // One accepted export and one shutdown drain may briefly wait behind a finishing worker.
        return new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "WM-Export");
            thread.setDaemon(false);
            return thread;
        });
    }

    DownloadExportCoordinator(DownloadCaptureQueue captureQueue,
                              Supplier<DownloadPipeline> pipelineSupplier) {
        this(captureQueue, pipelineSupplier, task -> Minecraft.getInstance().execute(task));
    }

    DownloadExportCoordinator(DownloadCaptureQueue captureQueue,
                              Supplier<DownloadPipeline> pipelineSupplier,
                              Consumer<Runnable> clientExecutor) {
        this(captureQueue, pipelineSupplier, clientExecutor, createExportExecutor());
    }

    DownloadExportCoordinator(DownloadCaptureQueue captureQueue,
                              Supplier<DownloadPipeline> pipelineSupplier,
                              Consumer<Runnable> clientExecutor, ThreadPoolExecutor executor) {
        this.captureQueue = captureQueue;
        this.pipelineSupplier = pipelineSupplier;
        this.clientExecutor = clientExecutor;
        this.executor = executor;
    }

    /** Never interrupts an atomic write or waits on a client-thread acknowledgement. */
    void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        executor.execute(this::drainAtShutdown);
        executor.shutdown();
    }

    private void drainAtShutdown() {
        long nowNs = System.nanoTime();
        synchronized (requestLock) { deferred.prepareForShutdown(); }
        while (true) {
            Frozen work;
            synchronized (requestLock) { work = (Frozen) deferred.poll(true, nowNs); }
            if (work == null) break;
            prepareJob(work).ifPresent(this::runWorker);
        }
        synchronized (requestLock) {
            if (deferred.frozenCount() > 0) WMLogger.warn("Export shutdown finished with " + deferred.frozenCount()
                    + " unsaved frozen bundle(s); failed writes were not acknowledged.");
        }
    }

    boolean isInProgress() {
        return inProgress.get();
    }

    void markEntitiesDirty() {
        entityRevision.incrementAndGet();
    }

    boolean hasEntityWork() {
        return entityRevision.get() > durableEntityRevision.get()
                || EntityTracker.hasDirtyUpdates();
    }

    Metrics metrics() {
        return new Metrics(lastExportMillis, lastWritten, lastSettled, lastUnreadable,
                failures.get());
    }

    DiagnosticSnapshot snapshotDiagnostics() {
        return new DiagnosticSnapshot(lastTimings, lastDurabilityIndexMillis,
                workerWallMillis.getAndSet(0L), automaticSuppressed.getAndSet(0L),
                deferredCoalesced.getAndSet(0L));
    }

    void resetDiagnostics() {
        failures.set(0L);
        automaticSuppressed.set(0L);
        deferredCoalesced.set(0L);
        workerWallMillis.set(0L);
        lastExportMillis = 0L;
        lastWritten = 0;
        lastSettled = 0;
        lastUnreadable = 0;
        lastDurabilityIndexMillis = 0L;
        lastTimings = emptyTimings();
    }

    void clearDeferred() {
        synchronized (requestLock) {
            deferred.clearLive();
        }
    }

    record ExportJob(Frozen work, Path worldFolder, ModConfig.DownloadPipelineMode mode,
                     boolean diagnostic, boolean invalidate, ConflictResolver resolver, long slowRegionThresholdMs,
                     ExportProgress progress) {
        ExportJob {
            Objects.requireNonNull(work);
            Objects.requireNonNull(worldFolder);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(resolver);
            Objects.requireNonNull(progress);
        }
    }
    record ExportOutcome(ChunkExporter.ExportResult result,
                         Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten,
                         Frozen remaining, boolean indexCommitted, boolean metadataCommitted, long indexMillis) {
        ExportOutcome {
            Objects.requireNonNull(result);
            Objects.requireNonNull(remaining);
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> copy = new HashMap<>();
            durableWritten.forEach((dimension, receipts) -> copy.put(dimension, Map.copyOf(receipts)));
            durableWritten = Map.copyOf(copy);
        }
        boolean successful() { return result.chunkWritesSuccessful() && result.entityWritesSuccessful() && indexCommitted && metadataCommitted; }
        ExportStatus.FailureStage failureStage() {
            if (!indexCommitted && !result.chunkWritesSuccessful() && !result.entityWritesSuccessful()
                    && result.written().isEmpty() && result.entityRevisions().isEmpty()) return ExportStatus.FailureStage.PREPARATION;
            if (!indexCommitted) return ExportStatus.FailureStage.INDEX;
            if (!result.chunkWritesSuccessful()) return ExportStatus.FailureStage.TERRAIN;
            if (!result.entityWritesSuccessful()) return ExportStatus.FailureStage.ENTITIES;
            return metadataCommitted ? ExportStatus.FailureStage.NONE : ExportStatus.FailureStage.METADATA;
        }
        static ExportOutcome failed(Frozen work) {
            return new ExportOutcome(new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(), Map.of(), false, false, emptyTimings()),
                    Map.of(), work, false, false, 0L);
        }
    }

    boolean start(Minecraft client, ExportWork work) { return start(client, work, false); }

    private boolean start(Minecraft client, ExportWork work, boolean fromQueue) {
        if (stopping.get()) return false;
        if (work instanceof Intent intent) work = resolveIntent(client, intent);
        Context context = work.context();
        boolean entitiesBlocked, previousSourceWork;
        synchronized (requestLock) {
            entitiesBlocked = !(fromQueue && work instanceof Frozen) && deferred.hasEntitiesForSource(context.source());
            previousSourceWork = !fromQueue && (deferred.hasTerrainForSource(context.source()) || entitiesBlocked && work instanceof Frozen);
        }
        if (inProgress.get() || previousSourceWork) {
            if (context.trigger().automatic) { automaticSuppressed.incrementAndGet(); return false; }
            if (work instanceof Intent intent) {
                intent = intent.containers().isPresent() ? intent : intent.withContainers(ContainerTracker.snapshotSavedData());
                work = intent.capture() == CapturePhase.READY ? freeze(client, intent, false) : intent;
            }
            defer(work);
            return false;
        }
        if (work instanceof Intent intent && intent.capture() == CapturePhase.REQUIRED) {
            if (captureQueue.hasPendingLightUpdates()) {
                if (context.trigger().automatic) automaticSuppressed.incrementAndGet(); else defer(intent);
                return false;
            }
            if (context.notifyPlayer() && ModConfig.get().lifecycle.captureNearbyBeforeExport && client.level != null && client.player != null) {
                boolean scheduled = captureQueue.queueLoaded(client.level, client.player.getBlockX() >> 4,
                        client.player.getBlockZ() >> 4, 8, "pre-export");
                if (scheduled || captureQueue.isCaptureInProgress()) { defer(intent.ready()); return false; }
            }
        }
        Frozen frozen;
        if (work instanceof Intent intent) {
            frozen = freeze(client, entitiesBlocked ? intent.terrainOnly() : intent, true);
            if (entitiesBlocked && !frozen.hasTerrain()) return false;
        } else frozen = (Frozen) work;
        Optional<ExportJob> job = prepareJob(frozen);
        if (job.isEmpty()) return false;
        inProgress.set(true);
        pipelineSupplier.get().onExportStarted(System.currentTimeMillis(), frozen.terrainCount(), ModConfig.get());
        executor.execute(() -> runWorker(job.get()));
        return true;
    }

    private Optional<ExportJob> prepareJob(Frozen work) {
        observe(() -> ExportStatusStore.global().queued(work));
        try {
            Path folder = MirrorMapping.getInstance().claimOutputDirectory(work.context().source().id());
            return Optional.of(new ExportJob(work, folder, pipelineSupplier.get().mode(),
                    ModConfig.get().performance.diagnosticPerformanceLogging, ModConfig.get().cache.invalidateAfterExport,
                    buildResolver(work.context().source().id()), ModConfig.get().performance.diagnosticPerformanceLogging
                    ? Math.max(0L, ModConfig.get().performance.slowRegionMillis) : -1L,
                    (part, dimension, chunks) -> ExportStatusStore.global().writing(work, part, dimension, chunks)));
        } catch (Exception failure) {
            WMLogger.warn("Export output preparation failed source=" + work.context().source().id(), failure);
            failures.incrementAndGet();
            observe(() -> ExportNotifications.global().observe(ExportStatusStore.global().finish(work, ExportOutcome.failed(work))));
            retainForRetry(work);
            return Optional.empty();
        }
    }

    private static Intent resolveIntent(Minecraft client, Intent intent) {
        if (intent.context().source().id().equals("unknown")) intent = intent.forSource(new Source(WorldMetadata.detectSourceId(client), WorldMetadata.detectSourceType(client)));
        if (intent.settings().isEmpty()) intent = intent.withSettings(WorldStructureCreator.resolveNewWorldSettings(WorldStructureCreator.captureWorldSettings(client.level)));
        return intent;
    }

    private Frozen freeze(Minecraft client, Intent intent, boolean acknowledgeLiveSignal) {
        long revision = entityRevision.get();
        boolean entities = intent.entityScope() == EntityScope.LIVE;
        if (entities && client.level != null && (!acknowledgeLiveSignal || revision > durableEntityRevision.get())) EntityTracker.captureEntitiesForWorld(client.level);
        return new Frozen(intent.context(), ChunkListener.snapshotDirtyState(), entities ? EntityTracker.snapshot() : Map.of(),
                intent.containers().orElseGet(ContainerTracker::snapshotSavedData), intent.settings().orElseThrow(), 0L,
                acknowledgeLiveSignal && entities ? OptionalLong.of(revision) : OptionalLong.empty());
    }

    void tryStartDeferred(Minecraft client) {
        ExportWork work;
        synchronized (requestLock) {
            if (stopping.get() || inProgress.get()) return;
            work = deferred.poll(captureQueue.hasWork() || client.level == null);
        }
        if (work != null) start(client, work, true);
    }

    private void runWorker(ExportJob job) {
        long startedNs = System.nanoTime(), cpuStartedNs = currentThreadCpuTimeNs();
        Frozen work = job.work();
        ExportOutcome outcome = ExportOutcome.failed(work);
        inProgress.set(true);
        try {
            outcome = write(job);
            var result = outcome.result();
            lastTimings = result.timings();
            lastDurabilityIndexMillis = outcome.indexMillis();
            lastWritten = result.totalWritten();
            lastUnreadable = result.unreadableChunks().values().stream().mapToInt(Set::size).sum();
            lastSettled = result.settledWithoutWrite().values().stream().mapToInt(Map::size).sum();
            dispatchAcknowledgements(work, outcome, job.invalidate());
            boolean pendingEntities;
            synchronized (requestLock) { pendingEntities = !result.entityWritesSuccessful() || deferred.hasEntitiesForSource(work.context().source()); }
            boolean successful = outcome.successful() && !pendingEntities;
            long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
            if (job.diagnostic()) logPerformance(work.context(), job.mode(), work.terrainCount(), lastWritten, lastUnreadable, elapsedMs, cpuStartedNs, result.timings());
            String summary = "Export pass complete: status=" + (successful ? "success" : "partial")
                    + " pipeline=" + job.mode() + " trigger=" + work.context().trigger() + " dirtySnapshot=" + work.terrainCount()
                    + " written=" + lastWritten + " unreadable=" + lastUnreadable + " settled=" + lastSettled + " elapsedMs=" + elapsedMs;
            if (successful) WMLogger.info(summary);
            else WMLogger.debug(summary);
            if (!stopping.get() && work.context().notifyPlayer() && successful) notifyPlayer("msg.worldmirror.exportDone");
        } finally {
            ExportOutcome completed = outcome;
            observe(() -> ExportNotifications.global().observe(ExportStatusStore.global().finish(work, completed)));
            lastExportMillis = (System.nanoTime() - startedNs) / 1_000_000L;
            if (job.diagnostic()) workerWallMillis.addAndGet(lastExportMillis);
            if (!outcome.successful()) { failures.incrementAndGet(); retainForRetry(outcome.remaining()); }
            inProgress.set(false);
            if (!stopping.get()) clientExecutor.accept(() -> tryStartDeferred(Minecraft.getInstance()));
        }
    }

    /** The native transaction never mutates live capture state or waits for a game-thread task. */
    static ExportOutcome write(ExportJob job) {
        Frozen work = job.work();
        Path worldFolder = job.worldFolder();
        String sourceId = work.context().source().id(), sourceType = work.context().source().type();
        var snapshot = work.terrain();
        var entitySnapshot = work.entities();
        var containerSnapshot = work.containers();
        var worldSettings = work.settings();
        ChunkDatabase db = null;
        var result = ExportOutcome.failed(work).result();
        Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten = new HashMap<>();
        Frozen remaining = work;
        long latestDurableCaptureTime = work.durableCaptureTime(), indexMillis = 0L;
        boolean indexCommitted = false, metadataCommitted = false;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            MirrorMigrationPlan.Inspection readiness = MirrorMigrationCoordinator.inspect(worldFolder);
            if (!readiness.mayCreateOrWriteWithoutMigration()) {
                WMLogger.warn("Mirror requires an explicit upgrade or is not writable ("
                        + readiness.state() + "); export aborted without modifying it.");
                return ExportOutcome.failed(work);
            }

            boolean createFreshWorld = readiness.state() == MirrorMigrationPlan.State.NEW;
            WorldMetadata metadata = createFreshWorld
                    ? WorldMetadata.create(sourceId, sourceType, "synchronized")
                    : readiness.metadata();
            boolean worldgenReady = WorldStructureCreator.createLoadableWorld(
                    worldFolder, sourceId, createFreshWorld, createFreshWorld, worldSettings);
            if (!worldgenReady) {
                WMLogger.warn("World generation setup failed; export aborted before writing chunks.");
                return ExportOutcome.failed(work);
            }
            if (createFreshWorld) {
                metadata.markWorldgenCurrent(
                        net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),
                        MirrorWorldgenAssets.ASSET_REVISION);
                if (!metadata.save(worldFolder)) {
                    return ExportOutcome.failed(work);
                }
            }

            try {
                db = ChunkDatabase.open(worldFolder, sourceId);
            } catch (SQLException e) {
                WMLogger.warn("Chunk database open failed; export aborted world=" + worldFolder, e);
                return ExportOutcome.failed(work);
            }
            if (!metadata.migrateAndCleanChunkTimes(db, worldFolder)) {
                return ExportOutcome.failed(work);
            }
            result = ChunkExporter.exportChunks(
                    worldFolder, snapshot, entitySnapshot, containerSnapshot,
                    job.resolver(), db, job.slowRegionThresholdMs(), job.progress());

            indexCommitted = true;
            long durabilityStartedNs = System.nanoTime();
            for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> dimension
                    : result.unreadableChunks().entrySet()) {
                if (dimension.getValue().isEmpty()) continue;
                String dimensionId = dimension.getKey().identifier().toString();
                if (db.removeUnreadableUpdates(dimensionId, dimension.getValue())) {
                    WMLogger.warn("Removed stale durability claims for unreadable region chunks dimension="
                            + dimensionId + " chunks=" + dimension.getValue().size());
                } else {
                    indexCommitted = false;
                }
            }
            for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkExporter.WriteStamp>> dimension
                    : result.written().entrySet()) {
                String dimensionId = dimension.getKey().identifier().toString();
                Map<ChunkPos, Long> timestamps = new HashMap<>();
                Map<ChunkPos, Long> revisions = new HashMap<>();
                dimension.getValue().forEach((pos, stamp) -> {
                    timestamps.put(pos, stamp.capturedAtMs());
                    revisions.put(pos, stamp.revision());
                });
                if (db.recordUpdates(dimensionId, timestamps, "world_mirror")) {
                    durableWritten.put(dimension.getKey(), revisions);
                    for (long capturedAtMs : timestamps.values()) {
                        latestDurableCaptureTime = Math.max(latestDurableCaptureTime, capturedAtMs);
                    }
                } else {
                    indexCommitted = false;
                    WMLogger.warn("Durability-index commit failed for [" + dimensionId
                            + "]; retaining " + revisions.size() + " revision(s) for retry.");
                }
            }
            indexMillis = (System.nanoTime() - durabilityStartedNs) / 1_000_000L;
            remaining = work.remaining(result, durableWritten, latestDurableCaptureTime);
            metadataCommitted = metadata.markSyncComplete(worldFolder, latestDurableCaptureTime);
        } catch (Exception failure) {
            WMLogger.warn("Export pass failed trigger=" + work.context().trigger() + " world=" + worldFolder, failure);
        } finally {
            if (db != null) db.close();
        }
        return new ExportOutcome(result, durableWritten, remaining, indexCommitted, metadataCommitted, indexMillis);
    }

    private void defer(ExportWork work) {
        if (work instanceof Frozen frozen) observe(() -> ExportStatusStore.global().queued(frozen));
        synchronized (requestLock) { if (deferred.offer(work)) deferredCoalesced.incrementAndGet(); }
    }
    private void retainForRetry(Frozen work) {
        long seconds = work.context().trigger() == Trigger.RETRY ? 60L : 10L;
        synchronized (requestLock) { deferred.retry(work.retry(), System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)); }
        WMLogger.debug("Retained frozen export source=" + work.context().source().id() + " for retry after " + seconds + "s");
    }

    /** UI observations cannot prevent acknowledgement dispatch, retries, or shutdown draining. */
    private static void observe(Runnable update) {
        try { update.run(); }
        catch (RuntimeException failure) {
            WMLogger.warnRateLimited("export-status-observer", 30_000L,
                    "Export status observation failed; native export and retry processing continue", failure);
        }
    }

    void dispatchAcknowledgements(Frozen work, ExportOutcome outcome, boolean invalidate) {
        if (stopping.get()) return;
        var written = outcome.durableWritten();
        var result = outcome.result();
        clientExecutor.accept(() -> {
            Map<ResourceKey<Level>, Set<ChunkPos>> invalidated = new HashMap<>();
            ChunkListener.acknowledge(result.settledRevisions(), invalidate).forEach((dimension, chunks) ->
                    invalidated.computeIfAbsent(dimension, ignored -> new HashSet<>()).addAll(chunks));
            ChunkListener.acknowledge(written, invalidate).forEach((dimension, chunks) ->
                    invalidated.computeIfAbsent(dimension, ignored -> new HashSet<>()).addAll(chunks));
            invalidated.forEach((dimension, chunks) -> chunks.removeIf(pos -> captureQueue.hasPendingChunkWork(dimension, pos)));
            ContainerTracker.evictForChunks(invalidated, work.containers());
            EntityTracker.acknowledge(result.entityRevisions());
            acknowledgeEntityWork(work.liveEntitySignal(), result.entityWritesSuccessful());
        });
    }
    void acknowledgeEntityWork(OptionalLong captured, boolean successful) {
        if (successful) captured.ifPresent(revision -> durableEntityRevision.accumulateAndGet(revision, Math::max));
    }
    private static Intent mergeIntents(Intent pending, Intent next) {
        Trigger trigger = next.context().trigger().priority >= pending.context().trigger().priority ? next.context().trigger() : pending.context().trigger();
        Context context = new Context(trigger, pending.context().notifyPlayer() || next.context().notifyPlayer(), next.context().source());
        CapturePhase phase = pending.capture() == CapturePhase.READY && next.capture() == CapturePhase.READY ? CapturePhase.READY : CapturePhase.REQUIRED;
        return new Intent(context, phase, next.entityScope(), next.containers().isPresent() ? next.containers() : pending.containers(),
                next.settings().isPresent() ? next.settings() : pending.settings());
    }

    private static ConflictResolver buildResolver(String sourceId) {
        ModConfig.ConflictStrategy strategy = ModConfig.get().defaultConflictStrategy;
        String perWorld = MirrorMapping.getInstance().getPerWorldConflictStrategy(sourceId);
        if (perWorld != null) {
            try {
                strategy = ModConfig.ConflictStrategy.valueOf(perWorld);
            } catch (IllegalArgumentException ignored) { }
        }
        return switch (strategy) {
            case IGNORE -> new IgnoreResolver();
            case MANUAL -> new ManualResolver();
            default -> new OverwriteResolver();
        };
    }

    private void logPerformance(Context context, ModConfig.DownloadPipelineMode pipelineMode,
                                int totalChunks, int written, int unreadable, long elapsedMs,
                                long startedCpuNs, ChunkExporter.ExportTimings timings) {
        long cpuNowNs = currentThreadCpuTimeNs();
        long cpuMs = startedCpuNs < 0L || cpuNowNs < startedCpuNs
                ? -1L : (cpuNowNs - startedCpuNs) / 1_000_000L;
        WMLogger.info("[perf] export trigger=" + context.trigger()
                + " pipeline=" + pipelineMode + " dirtySnapshot=" + totalChunks
                + " written=" + written + " unreadable=" + unreadable
                + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs()
                + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs()
                + " regionFlushMs=" + timings.flushMs()
                + " regionVerifyMs=" + timings.verificationMs()
                + " entityWriteMs=" + timings.entityMs()
                + " dbCommitMs=" + lastDurabilityIndexMillis
                + " workerCpuMs=" + cpuMs + " elapsedMs=" + elapsedMs);
    }

    private void notifyPlayer(String key) {
        clientExecutor.accept(() -> WMPlayerMessages.sendSystemMessage(Minecraft.getInstance().player, Component.translatable(key)));
    }
    private static long currentThreadCpuTimeNs() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime() : -1L;
    }

    private static ChunkExporter.ExportTimings emptyTimings() {
        return new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0);
    }
}
