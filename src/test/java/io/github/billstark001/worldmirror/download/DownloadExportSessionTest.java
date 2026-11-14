package io.github.billstark001.worldmirror.download;

import java.util.OptionalLong;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadExportSessionTest extends DownloadExportFixture {
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
        var first = ExportTestTargets.session("server:same-target");
        var next = ExportTestTargets.session("server:same-target");
        assertTrue(first.sourceEpoch() != next.sourceEpoch());
        assertEquals(first.id(), next.id(), "durable scheduling groups work for the same target");
        ChunkPos position = new ChunkPos(0, 0);
        var chunk = new CompoundTag();
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, position, chunk);
        try {
            var work = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false, first),
                    ChunkListener.snapshotDirtyState(), Map.of(), Map.of(), 0L, OptionalLong.empty());
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
    void refreshingSettingsStillAcknowledgesOnlyExactRevisionsForTheSameCache(@TempDir Path world)
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
        var previous = ExportTestTargets.session("server:settings-refresh");
        var next = new CaptureSession(previous.connectionEpoch(), previous.sourceEpoch(),
                previous.sourceNodeId(), previous.target(),
                new CaptureSession.Settings(
                        io.github.billstark001.worldmirror.config.ModConfig.ConflictStrategy.MANUAL,
                        previous.settings().worldSettings(), false));
        var unchanged = new ChunkPos(0, 0);
        var changed = new ChunkPos(1, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, unchanged, new CompoundTag());
        ChunkListener.addChunkNbt(Level.OVERWORLD, changed, new CompoundTag());
        try {
            var work = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false, previous),
                    ChunkListener.snapshotDirtyState(), Map.of(), Map.of(), 0L, OptionalLong.empty());
            coordinator.submit(jobs.apply(work), 0);
            var newer = new CompoundTag();
            newer.putString("fixture", "new revision");
            ChunkListener.addChunkNbt(Level.OVERWORLD, changed, newer);
            executor.submit(() -> {}).get(15, TimeUnit.SECONDS);
            coordinator.drainCompletions(next);

            assertEquals(Set.of(changed),
                    ChunkListener.snapshotDirtyState().chunks().get(Level.OVERWORLD).keySet());
            assertEquals(2, ChunkDatabase.countAllReadOnly(world, previous.target().sourceId()));
            assertEquals(io.github.billstark001.worldmirror.config.ModConfig.ConflictStrategy.OVERWRITE,
                    work.context().session().settings().strategy());
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
                                                 ExportTestTargets.session("server:light-test")),
                first, Map.of(), Map.of(), 0, OptionalLong.empty());
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
    void preparedStopSnapshotRemainsAnImmutableBundleWhenAnotherSourceDefers() {
        CompoundTag oldContainer = new CompoundTag();
        oldContainer.putString("source", "old");
        CompoundTag newContainer = new CompoundTag();
        newContainer.putString("source", "new");
        ChunkListener.DirtySnapshot oldTerrain = ChunkListener.snapshotOf(Map.of());
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> oldEntities = Map.of();

        var pending = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false,
                                                    ExportTestTargets.session("server:old")),
                oldTerrain, oldEntities, containers(oldContainer), 0L, OptionalLong.empty());
        var request = intent(ExportWork.Trigger.MANUAL, true, "server:new")
                              .withContainers(containers(newContainer));

        var queue = new ExportScheduler.Pending();
        queue.enqueue(pending);
        queue.request(request);
        var frozen = (ExportWork.Frozen) queue.poll(false, 0L);

        assertEquals(ExportWork.Trigger.STOP, frozen.context().trigger());
        assertEquals("mirror:server:old", frozen.context().session().id());
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
                new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0), 0L, false);
        var exact = new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(),
                Map.of(Level.OVERWORLD, Map.of(pos, 4L)), true, true, wrong.timings(), 0L, false);
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
        var session = ExportTestTargets.session("server:live");
        assertFalse(queue.request(intent(ExportWork.Trigger.MANUAL, true, session)));
        assertTrue(queue.request(intent(ExportWork.Trigger.PERIODIC, false, session).ready()));

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

    @Test
    void preparedOldSnapshotCannotSettleLiveEntityCaptureSignals() {
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1, () -> 8192),
                new ExportScheduler(), DownloadExportCoordinator.createExportExecutor(),
                work -> { throw new IllegalStateException("No target configured"); });
        var prepared = new ExportWork.Frozen(new ExportWork.Context(ExportWork.Trigger.STOP, false,
                                                     ExportTestTargets.session("server:old")),
                ChunkListener.snapshotOf(Map.of()), Map.of(), Map.of(), 0L, OptionalLong.empty());
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
        assertEquals("mirror:server:old", retry.context().session().id());
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
                                                 ExportTestTargets.session("server:completion")),
                ChunkListener.snapshotDirtyState(), Map.of(), Map.of(), 0, OptionalLong.empty());
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
            coordinator.drainCompletions(work.context().session());
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
}
