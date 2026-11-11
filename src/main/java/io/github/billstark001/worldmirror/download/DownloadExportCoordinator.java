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
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import io.github.billstark001.worldmirror.download.ExportScheduler.Dispatch;

/** Owns the export worker and durable transactions; scheduling state belongs to the client thread. */
final class DownloadExportCoordinator {
    record Metrics(long lastExportMillis, int lastWritten, int lastSettled,
                   int lastUnreadable, long failures) { }

    record DiagnosticSnapshot(ChunkExporter.ExportTimings timings,
                              long durabilityIndexMillis,
                              long workerWallMillis,
                              long automaticSuppressed,
                              long deferredCoalesced) { }

    private final DownloadCaptureQueue captureQueue;
    private final ExportScheduler scheduler;
    private final ThreadPoolExecutor executor;
    private final Function<Frozen, ExportJob> jobs;
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private volatile ExportJob inFlight;
    private boolean closed;
    private long entityRevision = 1, durableEntityRevision;
    private long failures, automaticSuppressed, deferredCoalesced, workerWallMillis;
    private volatile long lastExportMillis;
    private volatile int lastWritten, lastSettled, lastUnreadable;
    private long lastDurabilityIndexMillis;
    private ChunkExporter.ExportTimings lastTimings = emptyTimings();
    private record Completion(ExportJob job, ExportOutcome outcome, long elapsedMs, long cpuMs) { }

    DownloadExportCoordinator(DownloadCaptureQueue captureQueue, ExportScheduler scheduler, ThreadPoolExecutor executor, Function<Frozen, ExportJob> jobs) {
        this.captureQueue = captureQueue;
        this.scheduler = scheduler;
        this.executor = executor;
        this.jobs = jobs;
    }

    static ThreadPoolExecutor createExportExecutor() {
        return new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "WM-Export");
            thread.setDaemon(false);
            return thread;
        });
    }

    boolean isInProgress() { return inFlight != null; }
    void markEntitiesDirty() { entityRevision++; }
    boolean hasEntityWork() { return entityRevision > durableEntityRevision || EntityTracker.hasDirtyUpdates(); }
    Metrics metrics() { return new Metrics(lastExportMillis, lastWritten, lastSettled, lastUnreadable, failures); }
    DiagnosticSnapshot snapshotDiagnostics() {
        var result = new DiagnosticSnapshot(lastTimings, lastDurabilityIndexMillis, workerWallMillis, automaticSuppressed, deferredCoalesced);
        workerWallMillis = automaticSuppressed = deferredCoalesced = 0;
        return result;
    }
    void resetDiagnostics() {
        failures = automaticSuppressed = deferredCoalesced = workerWallMillis = lastExportMillis = lastDurabilityIndexMillis = 0;
        lastWritten = lastSettled = lastUnreadable = 0;
        lastTimings = emptyTimings();
    }

    CapturePolicy.Sample sampleResources() {
        Set<Long> terrain = new HashSet<>(), entities = new HashSet<>();
        ChunkListener.forEachDirtyChunk(chunk -> terrain.add(chunk.revision()));
        EntityTracker.forEachDirtyRevision(entities::add);
        if (!closed) scheduler.pending.collectRetained(terrain, entities);
        int active = 0;
        if (inFlight != null) {
            Frozen work = inFlight.work();
            work.terrain().chunks().values().forEach(chunks -> chunks.values().forEach(chunk -> terrain.add(chunk.revision())));
            work.entities().values().forEach(chunks -> chunks.values().forEach(update -> entities.add(update.revision())));
            active = work.terrainCount() + work.entities().values().stream().mapToInt(Map::size).sum();
        }
        Runtime runtime = Runtime.getRuntime();
        return new CapturePolicy.Sample(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                ChunkListener.getDirtyCount(), (long) terrain.size() + entities.size(), (closed ? 0 : scheduler.pending.frozenCount()), active);
    }

    void cancelRequest() {
        if (!closed) scheduler.pending.clearLive();
    }

    void request(Intent intent) {
        if (closed) return;
        if (!intent.context().trigger().automatic()) scheduler.pending.retryNow(intent.context().source());
        if (scheduler.pending.offer(intent)) deferredCoalesced++;
    }
    void enqueue(Frozen work) {
        if (closed) return;
        scheduler.pending.retryNow(work.context().source());
        observe(() -> ExportStatusStore.global().queued(work));
        scheduler.pending.offer(work);
    }

    Dispatch dispatch(Minecraft client, CaptureBudget budget, boolean automaticEnabled, long now,
                      ModConfig config, Function<Trigger, Intent> requests) {
        if (closed) return Dispatch.CLOSED;
        if (inFlight != null) return Dispatch.BUSY;
        scheduler.configure(config);
        ExportWork work = scheduler.pending.poll(budget.preExportScan() && captureQueue.hasPendingCaptures(), now);
        if (work == null && automaticEnabled && client.level != null) {
            boolean hasWork = ChunkListener.getDirtyCount() > 0 || EntityTracker.hasDirtyUpdates()
                    || (budget.refreshEntities() && hasEntityWork());
            Trigger trigger = scheduler.automatic(now, ChunkListener.getDirtyCount(), hasWork, !budget.refreshEntities());
            if (trigger != null) work = requests.apply(trigger);
        }
        if (work == null) return Dispatch.IDLE;
        Dispatch result = dispatchWork(client, work, budget, now);
        scheduler.attempted(work.context().trigger(), result, now);
        if (work.context().trigger().automatic() && result != Dispatch.STARTED) automaticSuppressed++;
        return result;
    }

    private Dispatch dispatchWork(Minecraft client, ExportWork work, CaptureBudget budget, long now) {
        if (work.context().trigger() == Trigger.MANUAL) io.github.billstark001.worldmirror.io.FileReadFailures.invalidate();
        Frozen frozen;
        if (work instanceof Intent intent) {
            if (client.level == null) { request(intent); return Dispatch.WAITING_WORLD; }
            if (scheduler.pending.hasTerrainForSource(intent.context().source())) return Dispatch.WAITING_SOURCE;
            if (intent.capture() == CapturePhase.REQUIRED && intent.captureNearby() && budget.preExportScan() && client.player != null) {
                captureQueue.queueLoaded(client.level, client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4, 8, "pre-export");
                request(intent.ready());
                return Dispatch.WAITING_CAPTURE;
            }
            if (scheduler.pending.hasEntitiesForSource(intent.context().source())) intent = intent.terrainOnly();
            frozen = freeze(client, intent, budget);
            if (intent.entityScope() == EntityScope.NONE && !frozen.hasTerrain()) return Dispatch.IDLE;
        } else frozen = (Frozen) work;
        Optional<ExportJob> job = prepareJob(frozen, now);
        if (job.isEmpty()) return Dispatch.PREPARATION_FAILED;
        submit(job.get(), now);
        return Dispatch.STARTED;
    }

    Frozen freeze(Minecraft client, Intent intent, CaptureBudget budget) {
        long revision = entityRevision;
        boolean entities = intent.entityScope() == EntityScope.LIVE;
        boolean refresh = entities && budget.refreshEntities() && client.level != null;
        if (refresh && revision > durableEntityRevision) EntityTracker.captureEntitiesForWorld(client.level);
        return new Frozen(intent.context(), snapshotReadyTerrain(), entities ? EntityTracker.snapshot() : Map.of(),
                intent.containers().orElseGet(ContainerTracker::snapshotSavedData), intent.settings(), 0,
                refresh ? OptionalLong.of(revision) : OptionalLong.empty());
    }
    ChunkListener.DirtySnapshot snapshotReadyTerrain() {
        return ChunkListener.snapshotDirtyState((dimension, pos) -> !captureQueue.isLightingPending(dimension, pos));
    }

    private Optional<ExportJob> prepareJob(Frozen work, long now) {
        observe(() -> ExportStatusStore.global().queued(work));
        try {
            return Optional.of(jobs.apply(work));
        } catch (Exception failure) {
            WMLogger.warn("Export output preparation failed source=" + work.context().source().id(), failure);
            failures++;
            observe(() -> ExportNotifications.global().observe(ExportStatusStore.global().finish(work, ExportOutcome.failed(work))));
            scheduler.completed(work, false, now);
            return Optional.empty();
        }
    }

    static ExportJob createJob(Frozen work, ModConfig.DownloadPipelineMode mode) {
        ModConfig config = ModConfig.get();
        return new ExportJob(work, () -> MirrorMapping.getInstance().claimOutputDirectory(work.context().source().id()),
                mode, config.performance.diagnosticPerformanceLogging, config.cache.invalidateAfterExport,
                buildResolver(work.context().source().id()), config.performance.diagnosticPerformanceLogging
                ? Math.max(0L, config.performance.slowRegionMillis) : -1L,
                (part, dimension, chunks) -> ExportStatusStore.global().writing(work, part, dimension, chunks));
    }

    /** The only worker submission path. Live cache acknowledgements wait for drainCompletions(). */
    void submit(ExportJob job, long now) {
        if (closed || inFlight != null) throw new IllegalStateException("Export worker is unavailable");
        inFlight = job;
        scheduler.accepted(now, job.work().terrainCount());
        executor.execute(() -> completions.add(perform(job)));
    }
    private static Completion perform(ExportJob job) {
        long started = System.nanoTime(), cpuStart = currentThreadCpuTimeNs();
        ExportOutcome outcome;
        try { outcome = write(job); }
        catch (Exception failure) {
            WMLogger.warn("Export worker failed source=" + job.work().context().source().id(), failure);
            outcome = ExportOutcome.failed(job.work());
        }
        long cpuEnd = currentThreadCpuTimeNs();
        return new Completion(job, outcome, (System.nanoTime() - started) / 1_000_000,
                cpuStart < 0 || cpuEnd < cpuStart ? -1 : (cpuEnd - cpuStart) / 1_000_000);
    }
    void drainCompletions() {
        if (closed) return;
        Completion completion;
        while ((completion = completions.poll()) != null) {
            try { settle(completion); }
            finally { applyReceipts(completion.job().work(), completion.outcome(), completion.job().invalidate()); }
        }
    }
    private void settle(Completion completion) {
        var job = completion.job();
        var work = job.work();
        var outcome = completion.outcome();
        var result = outcome.result();
        lastTimings = result.timings();
        lastDurabilityIndexMillis = outcome.indexMillis();
        lastWritten = result.totalWritten();
        lastUnreadable = result.unreadableChunks().values().stream().mapToInt(Set::size).sum();
        lastSettled = result.settledWithoutWrite().values().stream().mapToInt(Map::size).sum();
        lastExportMillis = completion.elapsedMs();
        if (job.diagnostic()) {
            workerWallMillis += lastExportMillis;
            observe(() -> logPerformance(work.context(), job.mode(), work.terrainCount(), lastWritten, lastUnreadable,
                    lastExportMillis, completion.cpuMs(), outcome.indexMillis(), result.timings()));
        }
        if (!outcome.successful()) failures++;
        scheduler.completed(outcome.remaining(), outcome.successful(), System.nanoTime() / 1_000_000);
        inFlight = null;
        boolean successful = outcome.successful() && !scheduler.pending.hasEntitiesForSource(work.context().source());
        String summary = "Export pass complete: status=" + (successful ? "success" : "partial") + " pipeline=" + job.mode()
                + " trigger=" + work.context().trigger() + " dirtySnapshot=" + work.terrainCount() + " written=" + lastWritten
                + " unreadable=" + lastUnreadable + " settled=" + lastSettled + " elapsedMs=" + lastExportMillis;
        if (successful) WMLogger.info(summary); else WMLogger.debug(summary);
        observe(() -> ExportNotifications.global().observe(ExportStatusStore.global().finish(work, outcome)));
        if (!closed && successful && work.context().notifyPlayer()) {
            observe(() -> WMPlayerMessages.sendSystemMessage(Minecraft.getInstance().player, Component.translatable("msg.worldmirror.exportDone")));
        }
    }

    /** Queue ownership transfers after the last client-thread request; native writes are never interrupted. */
    void shutdown() {
        if (closed) return;
        closed = true;
        executor.execute(() -> {
            Completion completed;
            while ((completed = completions.poll()) != null) settle(completed);
            scheduler.pending.prepareForShutdown();
            long now = System.nanoTime() / 1_000_000;
            Frozen work;
            while ((work = (Frozen) scheduler.pending.poll(true, now)) != null) {
                prepareJob(work, now).ifPresent(job -> settle(perform(job)));
            }
            if (scheduler.pending.frozenCount() > 0) WMLogger.warn("Export shutdown finished with "
                    + scheduler.pending.frozenCount() + " unsaved frozen bundle(s); failed writes were not acknowledged.");
        });
        executor.shutdown();
    }

    @FunctionalInterface
    interface OutputTarget { Path claim() throws java.io.IOException; }

    record ExportJob(Frozen work, OutputTarget target, ModConfig.DownloadPipelineMode mode,
                     boolean diagnostic, boolean invalidate, ConflictResolver resolver, long slowRegionThresholdMs,
                     ExportProgress progress) {
        ExportJob {
            Objects.requireNonNull(work);
            Objects.requireNonNull(target);
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

    /** The native transaction never mutates live capture state or waits for a game-thread task. */
    static ExportOutcome write(ExportJob job) {
        Frozen work = job.work();
        Path worldFolder;
        try { worldFolder = job.target().claim(); }
        catch (Exception preparationFailure) {
            OperationFailures.report(work.context().source().id(), "output_claim", null, preparationFailure);
            return ExportOutcome.failed(work);
        }
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
            String reservedSource = createFreshWorld ? MirrorClaim.source(worldFolder) : readiness.metadata().sourceId;
            if (reservedSource != null && !sourceId.equals(reservedSource)) {
                WMLogger.warn("Export target identity changed before the native lease was acquired; retained source=" + sourceId);
                return ExportOutcome.failed(work);
            }
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
                MirrorClaim.finish(worldFolder, sourceId);
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

    /** Applies exact durable receipts only on the client thread. */
    void applyReceipts(Frozen work, ExportOutcome outcome, boolean invalidate) {
        var written = outcome.durableWritten();
        var result = outcome.result();
        Map<ResourceKey<Level>, Set<ChunkPos>> invalidated = new HashMap<>();
        ChunkListener.acknowledge(result.settledRevisions(), invalidate).forEach((dimension, chunks) ->
                invalidated.computeIfAbsent(dimension, ignored -> new HashSet<>()).addAll(chunks));
        ChunkListener.acknowledge(written, invalidate).forEach((dimension, chunks) ->
                invalidated.computeIfAbsent(dimension, ignored -> new HashSet<>()).addAll(chunks));
        invalidated.forEach((dimension, chunks) -> chunks.removeIf(pos -> captureQueue.hasPendingChunkWork(dimension, pos)));
        ContainerTracker.evictForChunks(invalidated, work.containers());
        EntityTracker.acknowledge(result.entityRevisions());
        acknowledgeEntityWork(work.liveEntitySignal(), result.entityWritesSuccessful());
    }
    void acknowledgeEntityWork(OptionalLong captured, boolean successful) {
        if (successful) captured.ifPresent(revision -> durableEntityRevision = Math.max(durableEntityRevision, revision));
    }
    private static void observe(Runnable update) {
        try { update.run(); }
        catch (RuntimeException failure) {
            WMLogger.warnRateLimited("export-status-observer", 30_000, "Export status observation failed; processing continues", failure);
        }
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

    private static void logPerformance(Context context, ModConfig.DownloadPipelineMode pipelineMode,
                                int totalChunks, int written, int unreadable, long elapsedMs,
                                long cpuMs, long indexMillis, ChunkExporter.ExportTimings timings) {
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
                + " dbCommitMs=" + indexMillis
                + " workerCpuMs=" + cpuMs + " elapsedMs=" + elapsedMs);
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
