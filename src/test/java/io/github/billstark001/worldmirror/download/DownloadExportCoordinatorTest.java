package io.github.billstark001.worldmirror.download;

import java.util.OptionalLong;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadExportCoordinatorTest {
    @Test
    void newerTerrainSuccessDoesNotResetAnOlderEntityRetryBackoff() {
        var scheduler = new ExportScheduler();
        var entities = prepared("server:backoff").entitiesOnly();
        scheduler.completed(entities, false, 0);
        scheduler.completed(prepared("server:backoff").terrainOnly(), true, 1_000);
        assertNull(scheduler.pending.poll(true, 9_999));
        var retry = (ExportWork.Frozen) scheduler.pending.poll(true, 10_000);
        assertTrue(retry.hasEntities());
        scheduler.completed(retry, false, 10_000);
        assertNull(
                scheduler.pending.poll(true, 29_999), "the second entity retry still waits twenty seconds");
        assertTrue(((ExportWork.Frozen) scheduler.pending.poll(true, 30_000)).hasEntities());
    }

    @Test
    void shutdownTransfersFailedInFlightWorkAndDrainsOtherSourcesOnce(@TempDir Path worlds) throws Exception {
        var scheduler = new ExportScheduler();
        var executor = DownloadExportCoordinator.createExportExecutor();
        var attempts = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        java.util.function.Function<ExportWork.Frozen, DownloadExportCoordinator.ExportJob> jobs = work
                -> new DownloadExportCoordinator.ExportJob(work,
                        ()
                                -> {
                            String source = work.context().source().id();
                            attempts.merge(source, 1, Integer::sum);
                            if (source.equals("mirror:server:broken"))
                                throw new java.io.IOException("storage unavailable");
                            return ExportTestTargets.reserve(worlds.resolve("healthy"), work);
                        },
                        io.github.billstark001.worldmirror.config.ModConfig.DownloadPipelineMode
                                .STABLE_PERIODIC,
                        false, true, new io.github.billstark001.worldmirror.conflict.OverwriteResolver(), -1,
                        io.github.billstark001.worldmirror.io.ExportProgress.NONE);
        var coordinator = new DownloadExportCoordinator(
                new DownloadCaptureQueue(() -> false, 1, () -> 8), scheduler, executor, jobs);
        ChunkListener.addChunkNbt(Level.OVERWORLD, new ChunkPos(0, 0), new CompoundTag());
        try {
            coordinator.submit(jobs.apply(prepared("server:broken").terrainOnly()), 0);
            executor.submit(() -> {}).get(15, TimeUnit.SECONDS);
            coordinator.enqueue(prepared("server:healthy").terrainOnly());
            coordinator.shutdown();
            coordinator.cancelRequest();
            coordinator.drainCompletions(null);
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            assertEquals(2, attempts.get("mirror:server:broken"), "one accepted write and one final attempt");
            assertEquals(1, attempts.get("mirror:server:healthy"));
            assertTrue(Files.exists(io.github.billstark001.worldmirror.io.WorldSaveLayout
                            .regionDirectory(worlds.resolve("healthy"), Level.OVERWORLD)
                            .resolve("r.0.0.mca")));
            assertEquals(1, scheduler.pending.frozenCount());
            assertEquals(1, ChunkListener.getDirtyCount(), "shutdown must not acknowledge live state");
        } finally {
            coordinator.shutdown();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void priorSessionReceiptsDoNotAcknowledgeTheCurrentSessionForTheSameTarget(@TempDir Path world)
            throws Exception {
        var scheduler = new ExportScheduler();
        var executor = DownloadExportCoordinator.createExportExecutor();
        java.util.function.Function<ExportWork.Frozen, DownloadExportCoordinator.ExportJob> jobs = work
                -> new DownloadExportCoordinator.ExportJob(work,
                        ()
                                -> ExportTestTargets.reserve(world, work),
                        io.github.billstark001.worldmirror.config.ModConfig.DownloadPipelineMode
                                .STABLE_PERIODIC,
                        false, true, new io.github.billstark001.worldmirror.conflict.OverwriteResolver(), -1,
                        io.github.billstark001.worldmirror.io.ExportProgress.NONE);
        var coordinator = new DownloadExportCoordinator(
                new DownloadCaptureQueue(() -> false, 1, () -> 8), scheduler, executor, jobs);
        var first = ExportTestTargets.source("server:same-target");
        var next = ExportTestTargets.source("server:same-target");
        assertEquals(first, next, "durable scheduling groups work for the same target");
        ChunkPos position = new ChunkPos(0, 0);
        var chunk = new CompoundTag();
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, position, chunk);
        try {
            var work = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false, first),
                    ChunkListener.snapshotDirtyState(), Map.of(), Map.of(), WorldSettingsSnapshot.defaults(),
                    0L, OptionalLong.empty());
            coordinator.submit(jobs.apply(work), 0);
            executor.submit(() -> {}).get(15, TimeUnit.SECONDS);
            coordinator.drainCompletions(next);
            assertEquals(1, ChunkListener.getDirtyCount(), "receipts apply only to the owning live session");
            assertEquals(1, ChunkDatabase.countAllReadOnly(world, first.target().sourceId()),
                    "the old write still commits");
        } finally {
            coordinator.shutdown();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void continuousLightUpdatesExcludeOnlyTheirOwnChunkAndNeverAcknowledgeIt(@TempDir Path world) {
        var queue = new DownloadCaptureQueue(() -> false, 1, () -> 8);
        var coordinator = new DownloadExportCoordinator(queue, new ExportScheduler(),
                DownloadExportCoordinator.createExportExecutor(),
                work -> { throw new IllegalStateException("No target configured"); });
        ChunkPos settling = new ChunkPos(0, 0), ready = new ChunkPos(1, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, settling, new CompoundTag());
        ChunkListener.addChunkNbt(Level.OVERWORLD, ready, new CompoundTag());
        var key = new DownloadCaptureQueue.Key(Level.OVERWORLD, 0, 0);
        queue.markLightDirty(key);
        assertTrue(queue.isLightingPending(Level.OVERWORLD, settling));
        assertFalse(queue.hasPendingCaptures(), "light alone must not block deferred live exports");
        var first = coordinator.snapshotReadyTerrain();
        assertEquals(Set.of(ready), first.chunks().get(Level.OVERWORLD).keySet());
        var work = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.RESOURCE_PRESSURE, false,
                                                 ExportTestTargets.source("server:light-test")),
                first, Map.of(), Map.of(), WorldSettingsSnapshot.defaults(), 0, OptionalLong.empty());
        var outcome = MirrorExportTransaction.write(work,
                ()
                        -> ExportTestTargets.reserve(world, work),
                new io.github.billstark001.worldmirror.conflict.OverwriteResolver(), -1,
                io.github.billstark001.worldmirror.io.ExportProgress.NONE);
        assertTrue(outcome.successful());
        assertEquals(Set.of(ready), outcome.durableWritten().get(Level.OVERWORLD).keySet());
        coordinator.applyReceipts(work, outcome, true);
        assertTrue(ChunkListener.getDimension(Level.OVERWORLD).containsKey(settling));
        for (int tick = 0; tick < 4; tick++) {
            queue.tick(null, CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 1500));
            queue.markLightDirty(key);
            assertTrue(coordinator.snapshotReadyTerrain().chunks().isEmpty());
        }
        queue.tick(null, CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 1500));
        queue.tick(null, CaptureBudget.forMode(CapturePolicy.Mode.PAUSED, 1500));
        assertEquals(
                Set.of(settling), coordinator.snapshotReadyTerrain().chunks().get(Level.OVERWORLD).keySet());
    }

    @Test
    void retryResourceAccountingCountsSharedSnapshotsOnce() {
        var scheduler = new ExportScheduler();
        var queue = scheduler.pending;
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1, () -> 8),
                scheduler, DownloadExportCoordinator.createExportExecutor(),
                work -> { throw new IllegalStateException("No target configured"); });
        var work = prepared("server:first");
        for (int batch = 0; batch < 8; batch++) queue.enqueue(work.retry());
        assertEquals(8, queue.frozenCount());
        assertEquals(2, coordinator.sampleResources().retainedChunks(),
                "one shared terrain revision and one entity observation");
        var pos = new ChunkPos(0, 0);
        var entity = work.entities().get(Level.OVERWORLD).get(pos);
        var resampled = new EntityTracker.ChunkUpdate(
                entity.revision(), entity.observation(), entity.upserts(), entity.tombstones());
        queue.enqueue(new ExportWork.Frozen(work.context(), work.terrain(),
                Map.of(Level.OVERWORLD, Map.of(pos, resampled)), work.containers(), work.settings(), 0,
                OptionalLong.empty()));
        assertEquals(2, coordinator.sampleResources().retainedChunks(),
                "separate entity snapshot wrappers share the same global revision");
    }

    @AfterEach
    void clearCache() {
        ChunkListener.clear();
        EntityTracker.clear();
    }
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preparedStopSnapshotRemainsAnImmutableBundleWhenAnotherSourceDefers() {
        CompoundTag oldContainer = new CompoundTag();
        oldContainer.putString("source", "old");
        CompoundTag newContainer = new CompoundTag();
        newContainer.putString("source", "new");
        ChunkListener.DirtySnapshot oldTerrain = ChunkListener.snapshotOf(Map.of());
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> oldEntities = Map.of();

        var pending = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false,
                                                    ExportTestTargets.source("server:old")),
                oldTerrain, oldEntities, containers(oldContainer), WorldSettingsSnapshot.defaults(), 0L,
                OptionalLong.empty());
        var request = intent(ExportWork.Trigger.MANUAL, true, "server:new")
                              .withContainers(containers(newContainer));

        var queue = new ExportScheduler.Pending();
        queue.enqueue(pending);
        queue.request(request);
        var frozen = (ExportWork.Frozen) queue.poll(false, 0L);

        assertEquals(ExportWork.Trigger.STOP, frozen.context().trigger());
        assertEquals("mirror:server:old", frozen.context().source().id());
        assertEquals(
                "old", frozen.containers().get(Level.OVERWORLD).get(BlockPos.ZERO).getStringOr("source", ""));
        assertSame(oldEntities, frozen.entities());
        assertSame(oldTerrain, frozen.terrain());
        assertSame(request, queue.poll(false, 0L));
        assertNull(queue.poll(false, 0L));
    }

    @Test
    void retainsEveryPreparedStopWhenSeveralWorldsLeaveDuringOneExport() {
        var queue = new ExportScheduler.Pending();
        var first = prepared("server:first");
        var second = prepared("server:second");
        var third = prepared("server:second");
        queue.enqueue(first);
        queue.enqueue(second);
        queue.enqueue(third);

        assertSame(first, queue.poll(true, 0L));
        assertSame(second, queue.poll(true, 0L));
        assertSame(third, queue.poll(true, 0L));
        assertNull(queue.poll(false, 0L));
    }

    @Test
    void clearingASourceDropsOnlyItsLiveRequest() {
        var queue = new ExportScheduler.Pending();
        var frozen = prepared("server:old");
        queue.request(intent(ExportWork.Trigger.MANUAL, true, "server:live"));
        queue.enqueue(frozen);

        queue.clearLive();

        assertSame(frozen, queue.poll(true, 0L));
        assertNull(queue.poll(false, 0L));
    }

    @Test
    void liveCoalescingCannotCarryContainersOrSettingsAcrossSources() {
        var queue = new ExportScheduler.Pending();
        var old = intent(ExportWork.Trigger.MANUAL, true, "server:old")
                          .withContainers(containers(new CompoundTag()));
        var next = intent(ExportWork.Trigger.MANUAL, true, "server:new");
        queue.request(old);
        assertFalse(queue.request(next));
        assertSame(next, queue.poll(false, 0L));
    }

    @Test
    void onlyExactEntityReceiptsCanReleaseFrozenUpdates() {
        var work = prepared("server:first");
        ChunkPos pos = new ChunkPos(0, 0);
        var wrong = new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(),
                Map.of(Level.OVERWORLD, Map.of(pos, 3L)), true, false,
                new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0));
        var exact = new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(),
                Map.of(Level.OVERWORLD, Map.of(pos, 4L)), true, true, wrong.timings());
        var terrainReceipt = Map.of(Level.OVERWORLD, Map.of(pos, 3L));
        assertTrue(work.remaining(wrong, terrainReceipt, 100).hasEntities());
        var complete = work.remaining(exact, terrainReceipt, 100);
        assertFalse(complete.hasEntities());
        assertFalse(complete.hasTerrain());
        assertEquals(100, complete.durableCaptureTime());
        assertTrue(complete.liveEntitySignal().isEmpty());
    }

    @Test
    void liveRequestsWaitForCaptureAndCoalesceTheirNotificationAndPriority() {
        var queue = new ExportScheduler.Pending();
        assertFalse(queue.request(intent(ExportWork.Trigger.MANUAL, true, "server:live")));
        assertTrue(queue.request(intent(ExportWork.Trigger.PERIODIC, false, "server:live").ready()));

        var scanRequest = (ExportWork.Intent) queue.poll(true, 0L);
        assertEquals(ExportWork.CapturePhase.REQUIRED, scanRequest.capture());
        queue.request(scanRequest.ready());
        assertNull(queue.poll(true, 0L));
        var live = (ExportWork.Intent) queue.poll(false, 0L);
        assertEquals(ExportWork.Trigger.MANUAL, live.context().trigger());
        assertTrue(live.context().notifyPlayer());
        assertEquals(ExportWork.CapturePhase.READY, live.capture());
        assertNull(queue.poll(false, 0L));
    }

    private static ExportWork.Intent intent(ExportWork.Trigger trigger, boolean notifyPlayer, String source) {
        return new ExportWork.Intent(
                new ExportWork.Context(trigger, notifyPlayer, ExportTestTargets.source(source)),
                ExportWork.CapturePhase.REQUIRED, ExportWork.EntityScope.LIVE, notifyPlayer,
                java.util.Optional.empty(), WorldSettingsSnapshot.defaults());
    }

    private static ExportWork.Frozen prepared(String source) {
        UUID uuid = new UUID(1L, 2L);
        var entity = new EntityTracker.EntityRecord(uuid, Set.of(uuid), new CompoundTag());
        var update = new EntityTracker.ChunkUpdate(
                4L, EntityTracker.Observation.PARTIAL, Map.of(uuid, entity), Set.of());
        return new ExportWork.Frozen(
                new ExportWork.Context(ExportWork.Trigger.STOP, false, ExportTestTargets.source(source)),
                ChunkListener.snapshotOf(Map.of(Level.OVERWORLD,
                        Map.of(new ChunkPos(0, 0),
                                new ChunkListener.CapturedChunk(new CompoundTag(), 100L, 3L, false)))),
                Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), Map.of(),
                WorldSettingsSnapshot.defaults(), 0L, OptionalLong.empty());
    }

    @Test
    void entityBackoffAllowsNewTerrainButKeepsEntitySnapshotsInOrder() {
        var queue = new ExportScheduler.Pending();
        var first = prepared("server:first");
        var failedEntities =
                first.remaining(new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(), Map.of(), true,
                                        false, new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0)),
                        Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), 3L)), 100L);
        var newer = prepared("server:first");
        queue.retry(failedEntities, 100L);
        queue.enqueue(newer);

        var terrain = (ExportWork.Frozen) queue.poll(true, 99L);
        assertSame(newer.terrain(), terrain.terrain());
        assertTrue(terrain.entities().isEmpty());
        assertFalse(queue.hasTerrainForSource(ExportTestTargets.source("server:first")));
        assertTrue(queue.hasEntitiesForSource(ExportTestTargets.source("server:first")));
        assertNull(queue.poll(false, 99L));
        assertSame(failedEntities, queue.poll(true, 100L));
        var entities = (ExportWork.Frozen) queue.poll(true, 100L);
        assertEquals(newer.entities(), entities.entities());
        assertTrue(entities.terrain().chunks().isEmpty());
        assertNull(queue.poll(false, 100L));
    }

    @Test
    void retryReleasesCommittedTerrainAndRetainsFailedIndexCommitsAndEntityRevisions() {
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos b = new ChunkPos(1, 0);
        var chunks = Map.of(a, new ChunkListener.CapturedChunk(new CompoundTag(), 100L, 3L, false), b,
                new ChunkListener.CapturedChunk(new CompoundTag(), 200L, 5L, false));
        var initial = prepared("server:first");
        var frozen = new ExportWork.Frozen(
                new ExportWork.Context(initial.context().trigger(), true, initial.context().source()),
                ChunkListener.snapshotOf(Map.of(Level.OVERWORLD, chunks)), initial.entities(),
                Map.of(Level.OVERWORLD,
                        Map.of(BlockPos.ZERO, new CompoundTag(), new BlockPos(16, 0, 0), new CompoundTag())),
                WorldSettingsSnapshot.defaults(), 0L, OptionalLong.empty());
        var result =
                new ChunkExporter.ExportResult(Map.of(Level.OVERWORLD,
                                                       Map.of(a, new ChunkExporter.WriteStamp(3L, 100L), b,
                                                               new ChunkExporter.WriteStamp(5L, 200L))),
                        Map.of(), Map.of(), Map.of(), true, false,
                        new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0));

        var remaining = frozen.remaining(result, Map.of(Level.OVERWORLD, Map.of(a, 3L)), 100L);

        assertEquals(Set.of(b), remaining.terrain().chunks().get(Level.OVERWORLD).keySet());
        assertEquals(Set.of(new BlockPos(16, 0, 0)), remaining.containers().get(Level.OVERWORLD).keySet());
        assertEquals(initial.entities(), remaining.entities());
        assertEquals(100L, remaining.durableCaptureTime());
        assertEquals(2, frozen.terrain().chunks().get(Level.OVERWORLD).size());
    }

    @Test
    void aLiveTerrainRequestCanPassAnEntityRetryWithoutAcknowledgingLiveEntitySignals() {
        var queue = new ExportScheduler.Pending();
        var old = prepared("server:first");
        var entities = new ExportWork.Frozen(
                new ExportWork.Context(old.context().trigger(), false, old.context().source()),
                ChunkListener.snapshotOf(Map.of()), old.entities(), Map.of(),
                WorldSettingsSnapshot.defaults(), 0L, OptionalLong.empty());
        queue.retry(entities, 100L);
        queue.request(intent(ExportWork.Trigger.MANUAL, true, "server:first"));

        var terrain = (ExportWork.Intent) queue.poll(false, 99L);
        assertEquals(ExportWork.EntityScope.NONE, terrain.entityScope());
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1, () -> 8192),
                new ExportScheduler(), DownloadExportCoordinator.createExportExecutor(),
                work -> { throw new IllegalStateException("No target configured"); });
        coordinator.acknowledgeEntityWork(OptionalLong.empty(), true);
        assertTrue(coordinator.hasEntityWork());
    }

    @Test
    void preparedOldSnapshotCannotSettleLiveEntityCaptureSignals() {
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1, () -> 8192),
                new ExportScheduler(), DownloadExportCoordinator.createExportExecutor(),
                work -> { throw new IllegalStateException("No target configured"); });
        var prepared = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false,
                                                     ExportTestTargets.source("server:old")),
                ChunkListener.snapshotOf(Map.of()), Map.of(), Map.of(), WorldSettingsSnapshot.defaults(), 0L,
                OptionalLong.empty());
        coordinator.acknowledgeEntityWork(prepared.liveEntitySignal(), true);
        assertTrue(coordinator.hasEntityWork());

        var live = intent(ExportWork.Trigger.MANUAL, false, "server:new");
        coordinator.markEntitiesDirty();
        coordinator.acknowledgeEntityWork(OptionalLong.of(1L), true);
        assertTrue(coordinator.hasEntityWork());
        coordinator.acknowledgeEntityWork(OptionalLong.of(2L), false);
        assertTrue(coordinator.hasEntityWork());
        coordinator.acknowledgeEntityWork(OptionalLong.of(2L), true);
        assertFalse(coordinator.hasEntityWork());
    }

    @Test
    void queuedSnapshotsKeepTheSourceResolvedBeforeDeferral() {
        var frozen = prepared("server:old");
        var retry = frozen.retry();
        assertEquals("mirror:server:old", retry.context().source().id());
        assertSame(frozen.terrain(), retry.terrain());
        assertEquals(frozen.entities(), retry.entities());
    }

    @Test
    void workerCompletionCannotAcknowledgeUntilTheClientDrainsIt(@TempDir Path world) throws Exception {
        var executor = DownloadExportCoordinator.createExportExecutor();
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1, () -> 8192),
                new ExportScheduler(), executor,
                work -> { throw new IllegalStateException("No target configured"); });
        ChunkPos pos = new ChunkPos(0, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        var work = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.MANUAL, true,
                                                 ExportTestTargets.source("server:completion")),
                ChunkListener.snapshotDirtyState(), Map.of(), Map.of(), WorldSettingsSnapshot.defaults(), 0,
                OptionalLong.empty());
        var job = new DownloadExportCoordinator.ExportJob(work,
                ()
                        -> ExportTestTargets.reserve(world, work),
                io.github.billstark001.worldmirror.config.ModConfig.DownloadPipelineMode.STABLE_PERIODIC,
                true, true, new io.github.billstark001.worldmirror.conflict.OverwriteResolver(), -1,
                io.github.billstark001.worldmirror.io.ExportProgress.NONE);
        try {
            coordinator.submit(job, 0);
            executor.submit(() -> {}).get(15, TimeUnit.SECONDS);
            assertTrue(coordinator.isInProgress(), "the client owns the in-flight slot");
            assertEquals(1, ChunkListener.getDirtyCount(), "the worker never acknowledges live state");
            assertEquals(1, coordinator.sampleResources().retainedChunks(),
                    "live and in-flight share one revision");
            assertEquals(1, coordinator.sampleResources().inFlightChunks());
            ChunkListener.markChunkDirty(Level.OVERWORLD, pos);
            long latest = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
            assertEquals(2, coordinator.sampleResources().retainedChunks(),
                    "a newer live revision is distinct from the write snapshot");
            coordinator.drainCompletions(work.context().source());
            assertEquals(1, coordinator.sampleResources().retainedChunks());
            var first = coordinator.snapshotDiagnostics();
            var second = coordinator.snapshotDiagnostics();
            assertEquals(1, first.written());
            assertEquals(first.written(), second.written());
            assertEquals(first.lastExportMillis(), second.lastExportMillis());
            assertEquals(first.lastExportMillis(), first.workerWallMillis());
            assertEquals(0, second.workerWallMillis());
            assertFalse(coordinator.isInProgress());
            assertEquals(latest, ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision());
            assertEquals(1, ChunkListener.getDirtyCount());
        } finally {
            coordinator.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void retryBackoffPreservesSourceOrderWithoutBlockingOtherWorlds() {
        var queue = new ExportScheduler.Pending();
        var failed = prepared("server:first");
        var newer = prepared("server:first");
        var other = prepared("server:other");
        var live = intent(ExportWork.Trigger.MANUAL, true, "server:first").ready();
        queue.retry(failed, 100L);
        queue.enqueue(newer);
        queue.enqueue(other);
        queue.request(live);

        assertTrue((queue.hasTerrainForSource(ExportTestTargets.source("server:first"))
                || queue.hasEntitiesForSource(ExportTestTargets.source("server:first"))));
        assertSame(other, queue.poll(false, 99L));
        assertNull(queue.poll(false, 99L));
        assertSame(failed, queue.poll(true, 100L));
        assertSame(newer, queue.poll(true, 100L));
        assertNull(queue.poll(true, 100L));
        assertSame(live, queue.poll(false, 100L));
        assertFalse((queue.hasTerrainForSource(ExportTestTargets.source("server:first"))
                || queue.hasEntitiesForSource(ExportTestTargets.source("server:first"))));
    }

    @Test
    void retryDeadlinesHandleMonotonicClockRollover() {
        var queue = new ExportScheduler.Pending();
        var request = prepared("server:first");
        queue.retry(request, Long.MIN_VALUE + 100L);
        assertNull(queue.poll(false, Long.MAX_VALUE - 10L));
        assertSame(request, queue.poll(false, Long.MIN_VALUE + 100L));
    }

    @Test
    void shutdownAttemptsFrozenWorkOnceAndStillReleasesTerrainAfterAnEntityFailure() {
        var queue = new ExportScheduler.Pending();
        var first = prepared("server:first");
        var failedEntities = new ExportWork.Frozen(
                new ExportWork.Context(first.context().trigger(), false, first.context().source()),
                ChunkListener.snapshotOf(Map.of()), first.entities(), Map.of(),
                WorldSettingsSnapshot.defaults(), 0L, OptionalLong.empty());
        queue.retry(failedEntities, 200L);
        queue.enqueue(first);
        queue.enqueue(prepared("server:other"));
        queue.prepareForShutdown();

        assertSame(failedEntities, queue.poll(true, 100L));
        queue.retry(failedEntities, 101L);
        var terrain = (ExportWork.Frozen) queue.poll(true, 100L);
        assertSame(first.terrain(), terrain.terrain());
        assertTrue(terrain.entities().isEmpty());
        assertEquals("mirror:server:other", queue.poll(true, 100L).context().source().id());
        assertNull(
                queue.poll(true, 100L), "failed and dependent entity bundles must not loop during shutdown");
        assertEquals(2, queue.frozenCount());
    }

    @Test
    void shutdownTerminatesAnIdleNonDaemonWorkerWithoutWaitingForItsKeepAlive() throws Exception {
        var executor = DownloadExportCoordinator.createExportExecutor();
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1, () -> 8192),
                new ExportScheduler(), executor,
                work -> { throw new IllegalStateException("No target configured"); });
        executor.submit(() -> assertFalse(Thread.currentThread().isDaemon())).get(5, TimeUnit.SECONDS);
        coordinator.shutdown();
        coordinator.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
    }

    @Test
    void shutdownLetsAnAcceptedAtomicWriteFinishWithoutInterruptingIt(@TempDir Path temp) throws Exception {
        var executor = DownloadExportCoordinator.createExportExecutor();
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1, () -> 8192),
                new ExportScheduler(), executor,
                work -> { throw new IllegalStateException("No target configured"); });
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Path target = temp.resolve("durable.bin");
        executor.execute(() -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                Path staged = temp.resolve("staged.bin");
                Files.writeString(staged, "complete");
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            coordinator.shutdown();
            assertFalse(executor.isTerminated());
        } finally {
            release.countDown();
            coordinator.shutdown();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals("complete", Files.readString(target));
    }

    @Test
    void aRetryRetainsVerifiedCaptureTimeAndAnUnknownFrozenSource() {
        var request = new ExportWork.Frozen(
                new ExportWork.Context(ExportWork.Trigger.RETRY, true, ExportTestTargets.source("unknown")),
                ChunkListener.snapshotOf(Map.of()), Map.of(), Map.of(), WorldSettingsSnapshot.defaults(),
                123L, OptionalLong.empty());

        var normalized = request.retry();
        assertEquals("mirror:unknown", normalized.context().source().id());
        assertEquals(123L, normalized.durableCaptureTime());
        assertSame(request.terrain(), normalized.terrain());
    }

    private static Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers(CompoundTag value) {
        return Map.of(Level.OVERWORLD, Map.of(BlockPos.ZERO, value));
    }
}
