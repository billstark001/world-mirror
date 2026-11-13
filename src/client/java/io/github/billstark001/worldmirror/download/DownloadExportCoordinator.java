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
import io.github.billstark001.worldmirror.download.MirrorExportTransaction.ExportOutcome;
import io.github.billstark001.worldmirror.download.MirrorExportTransaction.OutputTarget;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ExportProgress;
import io.github.billstark001.worldmirror.ui.map.ChunkStatusCache;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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

/** Coordinates export work between the client thread and the native save writer. */
final class DownloadExportCoordinator {
    record DiagnosticSnapshot(long lastExportMillis, int written, int settled, int unreadable, long failures,
            ChunkExporter.ExportTimings timings, long durabilityIndexMillis, long workerWallMillis,
            long automaticSuppressed, long deferredCoalesced) {}

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
    private ChunkExporter.ExportTimings lastTimings = MirrorExportTransaction.EMPTY_TIMINGS;
    private record Completion(ExportJob job, ExportOutcome outcome, long elapsedMs, long cpuMs) {}

    DownloadExportCoordinator(DownloadCaptureQueue captureQueue, ExportScheduler scheduler,
            ThreadPoolExecutor executor, Function<Frozen, ExportJob> jobs) {
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

    boolean isInProgress() {
        return inFlight != null;
    }
    boolean hasQueuedWork() {
        return inFlight != null || scheduler.pending.hasWork();
    }
    boolean hasManualExport() {
        ExportJob active = inFlight;
        return scheduler.pending.hasManualRequest()
                || active != null && active.work().context().trigger() == Trigger.MANUAL;
    }
    void markEntitiesDirty() {
        entityRevision++;
    }
    boolean hasEntityWork() {
        return entityRevision > durableEntityRevision || EntityTracker.hasDirtyUpdates();
    }
    DiagnosticSnapshot snapshotDiagnostics() {
        var result = new DiagnosticSnapshot(lastExportMillis, lastWritten, lastSettled, lastUnreadable,
                failures, lastTimings, lastDurabilityIndexMillis, workerWallMillis, automaticSuppressed,
                deferredCoalesced);
        workerWallMillis = automaticSuppressed = deferredCoalesced = 0;
        return result;
    }
    void resetDiagnostics() {
        failures = automaticSuppressed = deferredCoalesced = workerWallMillis = lastExportMillis =
                lastDurabilityIndexMillis = 0;
        lastWritten = lastSettled = lastUnreadable = 0;
        lastTimings = MirrorExportTransaction.EMPTY_TIMINGS;
    }

    CapturePolicy.Sample sampleResources() {
        var terrain = new LongOpenHashSet(ChunkListener.getDirtyCount());
        var entities = new LongOpenHashSet();
        ChunkListener.forEachDirtyRevision(terrain::add);
        EntityTracker.forEachDirtyRevision(entities::add);
        if (!closed)
            scheduler.pending.collectRetained(terrain::add, entities::add);
        int active = 0;
        if (inFlight != null) {
            Frozen work = inFlight.work();
            work.collectRevisions(terrain::add, entities::add);
            active = work.terrainCount() + work.entities().values().stream().mapToInt(Map::size).sum();
        }
        Runtime runtime = Runtime.getRuntime();
        return new CapturePolicy.Sample(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                ChunkListener.getDirtyCount(), (long) terrain.size() + entities.size(),
                (closed ? 0 : scheduler.pending.frozenCount()), active);
    }

    void cancelRequest() {
        if (!closed)
            scheduler.pending.clearLive();
    }

    void request(Intent intent) {
        if (closed)
            return;
        if (!intent.context().trigger().automatic())
            scheduler.pending.retryNow(intent.context().session().id());
        if (scheduler.pending.request(intent))
            deferredCoalesced++;
    }
    void enqueue(Frozen work) {
        if (closed)
            return;
        scheduler.pending.retryNow(work.context().session().id());
        observe(() -> ExportStatusStore.global().queued(work));
        scheduler.pending.enqueue(work);
    }

    Dispatch dispatch(Minecraft client, CaptureBudget budget, CaptureSession session,
            boolean automaticEnabled, long now, ModConfig config, Function<Trigger, Intent> requests) {
        if (closed)
            return Dispatch.CLOSED;
        if (inFlight != null)
            return Dispatch.BUSY;
        scheduler.configure(config);
        ExportWork work =
                scheduler.pending.poll(budget.preExportScan() && captureQueue.hasPendingCaptures(), now);
        if (work == null && automaticEnabled && session != null && client.level != null) {
            boolean hasWork = ChunkListener.getDirtyCount() > 0 || EntityTracker.hasDirtyUpdates()
                    || (budget.refreshEntities() && hasEntityWork());
            Trigger trigger = scheduler.automatic(
                    now, ChunkListener.getDirtyCount(), hasWork, !budget.refreshEntities());
            if (trigger != null)
                work = requests.apply(trigger);
        }
        if (work == null)
            return Dispatch.IDLE;
        Dispatch result = dispatchWork(client, work, session, budget, now);
        scheduler.attempted(work.context().trigger(), result, now);
        if (work.context().trigger().automatic() && result != Dispatch.STARTED)
            automaticSuppressed++;
        return result;
    }

    private Dispatch dispatchWork(
            Minecraft client, ExportWork work, CaptureSession session, CaptureBudget budget, long now) {
        if (work.context().trigger() == Trigger.MANUAL)
            io.github.billstark001.worldmirror.io.FileReadFailures.invalidate();
        Frozen frozen;
        if (work instanceof Intent intent) {
            if (session == null) {
                request(intent);
                return Dispatch.WAITING_WORLD;
            }
            if (!intent.context().session().ownsSameCache(session))
                return Dispatch.WAITING_SOURCE;
            if (client.level == null) {
                request(intent);
                return Dispatch.WAITING_WORLD;
            }
            if (scheduler.pending.hasTerrainForTarget(intent.context().session().id()))
                return Dispatch.WAITING_SOURCE;
            if (intent.capture() == CapturePhase.REQUIRED && intent.captureNearby() && budget.preExportScan()
                    && client.player != null) {
                captureQueue.queueLoaded(client.level, client.player.getBlockX() >> 4,
                        client.player.getBlockZ() >> 4, 8, "pre-export");
                request(intent.ready());
                return Dispatch.WAITING_CAPTURE;
            }
            if (scheduler.pending.hasEntitiesForTarget(intent.context().session().id()))
                intent = intent.terrainOnly();
            frozen = freeze(client, intent, budget);
            if (intent.entityScope() == EntityScope.NONE && !frozen.hasTerrain())
                return Dispatch.IDLE;
        } else
            frozen = (Frozen) work;
        Optional<ExportJob> job = prepareJob(frozen, now);
        if (job.isEmpty())
            return Dispatch.PREPARATION_FAILED;
        submit(job.get(), now);
        return Dispatch.STARTED;
    }

    Frozen freeze(Minecraft client, Intent intent, CaptureBudget budget) {
        boolean diagnostic = ModConfig.get().performance.diagnosticPerformanceLogging;
        long started = diagnostic ? System.nanoTime() : 0L;
        long revision = entityRevision;
        boolean entities = intent.entityScope() == EntityScope.LIVE;
        boolean refresh = entities && budget.refreshEntities() && client.level != null;
        if (refresh && revision > durableEntityRevision)
            EntityTracker.captureEntitiesForWorld(client.level);
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot =
                entities ? EntityTracker.snapshot() : Map.of();
        long entitiesFinished = diagnostic ? System.nanoTime() : 0L;
        var terrain = snapshotReadyTerrain();
        long terrainFinished = diagnostic ? System.nanoTime() : 0L;
        Frozen frozen = new Frozen(intent.context(), terrain, entitySnapshot,
                intent.containers().orElseGet(ContainerTracker::snapshotSavedData), 0,
                refresh ? OptionalLong.of(revision) : OptionalLong.empty());
        if (diagnostic) {
            long finished = System.nanoTime();
            WMLogger.info("[perf] exportFreeze trigger=" + intent.context().trigger() + " chunks="
                    + frozen.terrainCount() + " entitiesUs=" + (entitiesFinished - started) / 1_000L
                    + " terrainUs=" + (terrainFinished - entitiesFinished) / 1_000L
                    + " bundleUs=" + (finished - terrainFinished) / 1_000L);
        }
        return frozen;
    }
    ChunkListener.DirtySnapshot snapshotReadyTerrain() {
        return ChunkListener.snapshotDirtyState(
                (dimension, pos) -> !captureQueue.isLightingPending(dimension, pos));
    }

    private Optional<ExportJob> prepareJob(Frozen work, long now) {
        observe(() -> ExportStatusStore.global().queued(work));
        try {
            return Optional.of(jobs.apply(work));
        } catch (Exception failure) {
            WMLogger.warn(
                    "Export output preparation failed source=" + work.context().session().id(), failure);
            failures++;
            observe(()
                            -> ExportNotifications.global().observe(
                                    ExportStatusStore.global().finish(work, ExportOutcome.failed(work))));
            scheduler.completed(work, false, now);
            return Optional.empty();
        }
    }

    static ExportJob createJob(Frozen work, ModConfig.DownloadPipelineMode mode) {
        ModConfig config = ModConfig.get();
        return new ExportJob(work,
                ()
                        -> WorldGraphService.instance()
                                .currentTarget(work.context().session().target())
                                .folder(),
                mode, config.performance.diagnosticPerformanceLogging,
                work.context().session().settings().invalidateAfterExport(),
                buildResolver(work.context().session().settings().strategy()),
                config.performance.diagnosticPerformanceLogging
                        ? Math.max(0L, config.performance.slowRegionMillis)
                        : -1L,
                (part, dimension,
                        chunks) -> ExportStatusStore.global().writing(work, part, dimension, chunks));
    }

    /** The only worker submission path. Live cache acknowledgements wait for drainCompletions(). */
    void submit(ExportJob job, long now) {
        if (closed || inFlight != null)
            throw new IllegalStateException("Export worker is unavailable");
        inFlight = job;
        scheduler.accepted(now, job.work().terrainCount());
        executor.execute(() -> completions.add(perform(job)));
    }
    private static Completion perform(ExportJob job) {
        long started = System.nanoTime(), cpuStart = currentThreadCpuTimeNs();
        ExportOutcome outcome;
        try {
            outcome = MirrorExportTransaction.write(
                    job.work(), job.target(), job.resolver(), job.slowRegionThresholdMs(), job.progress());
            if (outcome.changedSavedData() != null) {
                var target = job.work().context().session().target();
                ChunkStatusCache.invalidateSaved(
                        outcome.changedSavedData(), target.nodeId(), target.sourceId());
            }
        } catch (Exception failure) {
            WMLogger.warn("Export worker failed source=" + job.work().context().session().id(), failure);
            outcome = ExportOutcome.failed(job.work());
        }
        long cpuEnd = currentThreadCpuTimeNs();
        return new Completion(job, outcome, (System.nanoTime() - started) / 1_000_000,
                cpuStart < 0 || cpuEnd < cpuStart ? -1 : (cpuEnd - cpuStart) / 1_000_000);
    }
    void drainCompletions(CaptureSession currentSession) {
        if (closed)
            return;
        Completion completion;
        while ((completion = completions.poll()) != null) {
            var work = completion.job().work();
            boolean live = work.context().session().ownsSameCache(currentSession);
            try {
                settle(completion, live);
            } finally {
                if (live)
                    applyReceipts(work, completion.outcome(), completion.job().invalidate());
            }
        }
    }
    private void settle(Completion completion, boolean live) {
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
            observe(()
                            -> logPerformance(work.context(), job.mode(), work.terrainCount(), lastWritten,
                                    lastUnreadable, lastExportMillis, completion.cpuMs(),
                                    outcome.indexMillis(), result.timings()));
        }
        if (!outcome.successful())
            failures++;
        scheduler.completed(outcome.remaining(), outcome.successful(), System.nanoTime() / 1_000_000);
        inFlight = null;
        boolean successful = outcome.successful()
                && !scheduler.pending.hasEntitiesForTarget(work.context().session().id());
        String summary = "Export pass complete: status=" + (successful ? "success" : "partial")
                + " pipeline=" + job.mode() + " trigger=" + work.context().trigger() + " dirtySnapshot="
                + work.terrainCount() + " written=" + lastWritten + " unreadable=" + lastUnreadable
                + " settled=" + lastSettled + " elapsedMs=" + lastExportMillis;
        if (successful)
            WMLogger.info(summary);
        else
            WMLogger.debug(summary);
        observe(() -> ExportNotifications.global().observe(ExportStatusStore.global().finish(work, outcome)));
        if (live && successful && work.context().notifyPlayer()) {
            observe(()
                            -> WMPlayerMessages.sendSystemMessage(Minecraft.getInstance().player,
                                    Component.translatable("msg.worldmirror.exportDone")));
        }
    }

    /** Queue ownership transfers after the last client-thread request; native writes are never interrupted. */
    void shutdown() {
        if (closed)
            return;
        closed = true;
        executor.execute(() -> {
            Completion completed;
            while ((completed = completions.poll()) != null) settle(completed, false);
            scheduler.pending.prepareForShutdown();
            long now = System.nanoTime() / 1_000_000;
            Frozen work;
            while ((work = (Frozen) scheduler.pending.poll(true, now)) != null) {
                prepareJob(work, now).ifPresent(job -> settle(perform(job), false));
            }
            if (scheduler.pending.frozenCount() > 0)
                WMLogger.warn("Export shutdown finished with " + scheduler.pending.frozenCount()
                        + " unsaved frozen bundle(s); failed writes were not acknowledged.");
        });
        executor.shutdown();
    }

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
    /** Applies exact durable receipts only on the client thread. */
    void applyReceipts(Frozen work, ExportOutcome outcome, boolean invalidate) {
        var written = outcome.durableWritten();
        var result = outcome.result();
        Map<ResourceKey<Level>, Set<ChunkPos>> invalidated = new HashMap<>();
        ChunkListener.acknowledge(result.settledRevisions(), invalidate)
                .forEach((dimension, chunks)
                                 -> invalidated.computeIfAbsent(dimension, ignored -> new HashSet<>())
                                         .addAll(chunks));
        ChunkListener.acknowledge(written, invalidate)
                .forEach((dimension, chunks)
                                 -> invalidated.computeIfAbsent(dimension, ignored -> new HashSet<>())
                                         .addAll(chunks));
        invalidated.forEach(
                (dimension,
                        chunks) -> chunks.removeIf(pos -> captureQueue.hasPendingChunkWork(dimension, pos)));
        ContainerTracker.evictForChunks(invalidated, work.containers());
        EntityTracker.acknowledge(result.entityRevisions());
        acknowledgeEntityWork(work.liveEntitySignal(), result.entityWritesSuccessful());
    }
    void acknowledgeEntityWork(OptionalLong captured, boolean successful) {
        if (successful)
            captured.ifPresent(revision -> durableEntityRevision = Math.max(durableEntityRevision, revision));
    }
    private static void observe(Runnable update) {
        try {
            update.run();
        } catch (RuntimeException failure) {
            WMLogger.warnRateLimited("export-status-observer", 30_000,
                    "Export status observation failed; processing continues", failure);
        }
    }

    private static ConflictResolver buildResolver(ModConfig.ConflictStrategy strategy) {
        return switch (strategy) {
            case IGNORE -> new IgnoreResolver();
            case MANUAL -> new ManualResolver();
            default -> new OverwriteResolver();
        };
    }

    private static void logPerformance(Context context, ModConfig.DownloadPipelineMode pipelineMode,
            int totalChunks, int written, int unreadable, long elapsedMs, long cpuMs, long indexMillis,
            ChunkExporter.ExportTimings timings) {
        WMLogger.info("[perf] export trigger=" + context.trigger() + " pipeline=" + pipelineMode
                + " dirtySnapshot=" + totalChunks + " written=" + written + " unreadable=" + unreadable
                + " dbLookupMs=" + timings.databaseLookupMs() + " materializeMs=" + timings.materializeMs()
                + " regionReadMs=" + timings.chunkReadMs() + " resolveMergeWriteMs="
                + timings.resolveMergeWriteMs() + " regionFlushMs=" + timings.flushMs()
                + " regionVerifyMs=" + timings.verificationMs() + " entityWriteMs=" + timings.entityMs()
                + " dbCommitMs=" + indexMillis + " workerCpuMs=" + cpuMs + " elapsedMs=" + elapsedMs);
    }

    private static long currentThreadCpuTimeNs() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime()
                : -1L;
    }
}
