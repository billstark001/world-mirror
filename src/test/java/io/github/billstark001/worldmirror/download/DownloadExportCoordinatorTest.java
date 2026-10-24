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

        var pending = new ExportWork.Frozen(
                new ExportWork.Context(DownloadExportCoordinator.Trigger.STOP, false, new ExportWork.Source("server:old", "server")),
                oldTerrain,
                oldEntities,
                containers(oldContainer),
                WorldSettingsSnapshot.defaults(),
                0L,
                OptionalLong.empty());
        var request = ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, true).forSource(new ExportWork.Source("server:new", "server")).withContainers(containers(newContainer));

        var queue = new DownloadExportCoordinator.DeferredRequests();
        queue.offer(pending);
        queue.offer(request);
        var frozen = (ExportWork.Frozen) queue.poll(false);

        assertEquals(DownloadExportCoordinator.Trigger.STOP, frozen.context().trigger());
        assertEquals("server:old", frozen.context().source().id());
        assertEquals("old", frozen.containers().get(Level.OVERWORLD)
                .get(BlockPos.ZERO).getStringOr("source", ""));
        assertSame(oldEntities, frozen.entities());
        assertSame(oldTerrain, frozen.terrain());
        assertSame(request, queue.poll(false));
        assertNull(queue.poll(false));
    }

    @Test
    void retainsEveryPreparedStopWhenSeveralWorldsLeaveDuringOneExport() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var first = prepared("server:first");
        var second = prepared("server:second");
        var third = prepared("server:second");
        queue.offer(first);
        queue.offer(second);
        queue.offer(third);

        assertSame(first, queue.poll(true));
        assertSame(second, queue.poll(true));
        assertSame(third, queue.poll(true));
        assertNull(queue.poll(false));
    }

    @Test
    void clearingASourceDropsOnlyItsLiveRequest() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var frozen = prepared("server:old");
        queue.offer(ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, true).forSource(new ExportWork.Source("server:live", "server")));
        queue.offer(frozen);

        queue.clearLive();

        assertSame(frozen, queue.poll(true));
        assertNull(queue.poll(false));
    }

    @Test
    void liveCoalescingCannotCarryContainersOrSettingsAcrossSources() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var old = ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, true)
                .forSource(new ExportWork.Source("server:old", "server"))
                .withContainers(containers(new CompoundTag())).withSettings(WorldSettingsSnapshot.defaults());
        var next = ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, true)
                .forSource(new ExportWork.Source("server:new", "server"));
        queue.offer(old);
        assertFalse(queue.offer(next));
        assertSame(next, queue.poll(false));
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
        var queue = new DownloadExportCoordinator.DeferredRequests();
        assertFalse(queue.offer(ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, true).forSource(new ExportWork.Source("server:live", "server"))));
        assertTrue(queue.offer(ExportWork.Intent.live(DownloadExportCoordinator.Trigger.PERIODIC, false).ready().forSource(new ExportWork.Source("server:live", "server"))));

        assertNull(queue.poll(true));
        var live = (ExportWork.Intent) queue.poll(false);
        assertEquals(DownloadExportCoordinator.Trigger.MANUAL, live.context().trigger());
        assertTrue(live.context().notifyPlayer());
        assertEquals(ExportWork.CapturePhase.REQUIRED, live.capture());
        assertNull(queue.poll(false));
    }

    private static ExportWork.Frozen prepared(String source) {
        UUID uuid = new UUID(1L, 2L);
        var entity = new EntityTracker.EntityRecord(uuid, Set.of(uuid), new CompoundTag());
        var update = new EntityTracker.ChunkUpdate(4L, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, entity), Set.of());
        return new ExportWork.Frozen(
                new ExportWork.Context(DownloadExportCoordinator.Trigger.STOP, false, new ExportWork.Source(source, "server")),
                ChunkListener.snapshotOf(Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0),
                        new ChunkListener.CapturedChunk(new CompoundTag(), 100L, 3L)))),
                Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)),
                Map.of(),
                WorldSettingsSnapshot.defaults(),
                0L,
                OptionalLong.empty());
    }

    @Test
    void entityBackoffAllowsNewTerrainButKeepsEntitySnapshotsInOrder() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var first = prepared("server:first");
        var failedEntities = first.remaining(
                new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(), Map.of(), true, false,
                        new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0)),
                Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), 3L)), 100L);
        var newer = prepared("server:first");
        queue.retry(failedEntities, 100L);
        queue.offer(newer);

        var terrain = (ExportWork.Frozen) queue.poll(true, 99L);
        assertSame(newer.terrain(), terrain.terrain());
        assertTrue(terrain.entities().isEmpty());
        assertFalse(queue.hasTerrainForSource(new ExportWork.Source("server:first", "server")));
        assertTrue(queue.hasEntitiesForSource(new ExportWork.Source("server:first", "server")));
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
        var chunks = Map.of(a, new ChunkListener.CapturedChunk(new CompoundTag(), 100L, 3L),
                b, new ChunkListener.CapturedChunk(new CompoundTag(), 200L, 5L));
        var initial = prepared("server:first");
        var frozen = new ExportWork.Frozen(
                new ExportWork.Context(initial.context().trigger(), true, new ExportWork.Source(initial.context().source().id(), "server")),
                ChunkListener.snapshotOf(Map.of(Level.OVERWORLD, chunks)),
                initial.entities(),
                Map.of(Level.OVERWORLD,
                        Map.of(BlockPos.ZERO, new CompoundTag(), new BlockPos(16, 0, 0), new CompoundTag())),
                WorldSettingsSnapshot.defaults(),
                0L,
                OptionalLong.empty());
        var result = new ChunkExporter.ExportResult(Map.of(Level.OVERWORLD, Map.of(
                a, new ChunkExporter.WriteStamp(3L, 100L), b, new ChunkExporter.WriteStamp(5L, 200L))),
                Map.of(), Map.of(), Map.of(), true, false,
                new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0));

        var remaining = frozen.remaining( result,
                Map.of(Level.OVERWORLD, Map.of(a, 3L)), 100L);

        assertEquals(Set.of(b), remaining.terrain().chunks().get(Level.OVERWORLD).keySet());
        assertEquals(Set.of(new BlockPos(16, 0, 0)), remaining.containers().get(Level.OVERWORLD).keySet());
        assertEquals(initial.entities(), remaining.entities());
        assertEquals(100L, remaining.durableCaptureTime());
        assertEquals(2, frozen.terrain().chunks().get(Level.OVERWORLD).size());
    }

    @Test
    void aLiveTerrainRequestCanPassAnEntityRetryWithoutAcknowledgingLiveEntitySignals() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var old = prepared("server:first");
        var entities = new ExportWork.Frozen(
                new ExportWork.Context(old.context().trigger(), false, new ExportWork.Source(old.context().source().id(), "server")),
                ChunkListener.snapshotOf(Map.of()),
                old.entities(),
                Map.of(),
                WorldSettingsSnapshot.defaults(),
                0L,
                OptionalLong.empty());
        queue.retry(entities, 100L);
        queue.offer(ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, true).forSource(new ExportWork.Source("server:first", "server")));

        var terrain = (ExportWork.Intent) queue.poll(false, 99L);
        assertEquals(ExportWork.EntityScope.NONE, terrain.entityScope());
        assertTrue(terrain.hasTerrain(), "live terrain is captured at execution, not at enqueue time");
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1),
                () -> DownloadPipeline.create(io.github.billstark001.worldmirror.config.ModConfig.DownloadPipelineMode.STABLE_PERIODIC));
        coordinator.acknowledgeEntityWork(OptionalLong.empty(), true);
        assertTrue(coordinator.hasEntityWork());
    }

    @Test
    void preparedOldSnapshotCannotSettleLiveEntityCaptureSignals() {
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1),
                () -> DownloadPipeline.create(io.github.billstark001.worldmirror.config.ModConfig
                        .DownloadPipelineMode.STABLE_PERIODIC));
        var prepared = new ExportWork.Frozen(
                new ExportWork.Context(DownloadExportCoordinator.Trigger.STOP, false, new ExportWork.Source("server:old", "server")),
                ChunkListener.snapshotOf(Map.of()),
                Map.of(),
                Map.of(),
                WorldSettingsSnapshot.defaults(),
                0L,
                OptionalLong.empty());
        coordinator.acknowledgeEntityWork(prepared.liveEntitySignal(), true);
        assertTrue(coordinator.hasEntityWork());

        var live = ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, false).forSource(new ExportWork.Source("server:new", "server"));
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
        assertEquals("server:old", retry.context().source().id());
        assertSame(frozen.terrain(), retry.terrain());
        assertEquals(frozen.entities(), retry.entities());
    }

    @Test
    void workerAcknowledgementsAreDispatchedAndCannotConsumeANewerRevision() {
        ArrayList<Runnable> gameThreadTasks = new ArrayList<>();
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1),
                () -> DownloadPipeline.create(io.github.billstark001.worldmirror.config.ModConfig
                        .DownloadPipelineMode.STABLE_PERIODIC), gameThreadTasks::add);
        ChunkPos pos = new ChunkPos(0, 0);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        long exported = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
        Map<ChunkPos, Long> revisions = new HashMap<>(Map.of(pos, exported));
        var emptyResult = new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(), Map.of(),
                true, true, new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0));

        coordinator.dispatchAcknowledgements(prepared("server:old"), new DownloadExportCoordinator.ExportOutcome(emptyResult,
                Map.of(Level.OVERWORLD, revisions), prepared("server:old"), true, true, 0L), true);

        assertEquals(1, gameThreadTasks.size());
        assertEquals(1, ChunkListener.getDirtyCount(), "the worker must not mutate the live cache");
        ChunkListener.markChunkDirty(Level.OVERWORLD, pos);
        long latest = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
        revisions.put(pos, latest);
        gameThreadTasks.removeFirst().run();

        assertEquals(latest, ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision());
        assertEquals(1, ChunkListener.getDirtyCount());
    }

    @Test
    void retryBackoffPreservesSourceOrderWithoutBlockingOtherWorlds() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var failed = prepared("server:first");
        var newer = prepared("server:first");
        var other = prepared("server:other");
        var live = ExportWork.Intent.live(DownloadExportCoordinator.Trigger.MANUAL, true).forSource(new ExportWork.Source("server:first", "server"));
        queue.retry(failed, 100L);
        queue.offer(newer);
        queue.offer(other);
        queue.offer(live);

        assertTrue((queue.hasTerrainForSource(new ExportWork.Source("server:first", "server")) || queue.hasEntitiesForSource(new ExportWork.Source("server:first", "server"))));
        assertSame(other, queue.poll(false, 99L));
        assertNull(queue.poll(false, 99L));
        assertSame(failed, queue.poll(true, 100L));
        assertSame(newer, queue.poll(true, 100L));
        assertNull(queue.poll(true, 100L));
        assertSame(live, queue.poll(false, 100L));
        assertFalse((queue.hasTerrainForSource(new ExportWork.Source("server:first", "server")) || queue.hasEntitiesForSource(new ExportWork.Source("server:first", "server"))));
    }

    @Test
    void retryDeadlinesHandleNanoTimeRollover() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var request = prepared("server:first");
        queue.retry(request, Long.MIN_VALUE + 100L);
        assertNull(queue.poll(false, Long.MAX_VALUE - 10L));
        assertSame(request, queue.poll(false, Long.MIN_VALUE + 100L));
    }

    @Test
    void shutdownAttemptsFrozenWorkOnceAndStillReleasesTerrainAfterAnEntityFailure() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        var first = prepared("server:first");
        var failedEntities = new ExportWork.Frozen(
                new ExportWork.Context(first.context().trigger(), false, new ExportWork.Source(first.context().source().id(), "server")),
                ChunkListener.snapshotOf(Map.of()),
                first.entities(),
                Map.of(),
                WorldSettingsSnapshot.defaults(),
                0L,
                OptionalLong.empty());
        queue.retry(failedEntities, 200L);
        queue.offer(first);
        queue.offer(prepared("server:other"));
        queue.prepareForShutdown();

        assertSame(failedEntities, queue.poll(true, 100L));
        queue.retry(failedEntities, 101L);
        var terrain = (ExportWork.Frozen) queue.poll(true, 100L);
        assertSame(first.terrain(), terrain.terrain());
        assertTrue(terrain.entities().isEmpty());
        assertEquals("server:other", queue.poll(true, 100L).context().source().id());
        assertNull(queue.poll(true, 100L), "failed and dependent entity bundles must not loop during shutdown");
        assertEquals(2, queue.frozenCount());
    }

    @Test
    void shutdownTerminatesAnIdleNonDaemonWorkerWithoutWaitingForItsKeepAlive() throws Exception {
        var executor = DownloadExportCoordinator.createExportExecutor();
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1),
                () -> DownloadPipeline.create(io.github.billstark001.worldmirror.config.ModConfig.DownloadPipelineMode.STABLE_PERIODIC),
                task -> { throw new AssertionError("shutdown must not need a client tick"); }, executor);
        executor.submit(() -> assertFalse(Thread.currentThread().isDaemon())).get(5, TimeUnit.SECONDS);
        coordinator.shutdown();
        coordinator.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
    }

    @Test
    void shutdownLetsAnAcceptedAtomicWriteFinishWithoutInterruptingIt(@TempDir Path temp) throws Exception {
        var executor = DownloadExportCoordinator.createExportExecutor();
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1),
                () -> DownloadPipeline.create(io.github.billstark001.worldmirror.config.ModConfig.DownloadPipelineMode.STABLE_PERIODIC),
                task -> { throw new AssertionError("shutdown must not need a client tick"); }, executor);
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
                new ExportWork.Context(DownloadExportCoordinator.Trigger.RETRY, true, new ExportWork.Source("unknown", "server")),
                ChunkListener.snapshotOf(Map.of()),
                Map.of(),
                Map.of(),
                WorldSettingsSnapshot.defaults(),
                123L,
                OptionalLong.empty());

        var normalized = request.retry();
        assertEquals("unknown", normalized.context().source().id());
        assertEquals(123L, normalized.durableCaptureTime());
        assertSame(request.terrain(), normalized.terrain());
    }

    private static Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers(
            CompoundTag value) {
        return Map.of(Level.OVERWORLD, Map.of(BlockPos.ZERO, value));
    }
}
