package io.github.billstark001.worldmirror.download;

import java.util.OptionalLong;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadExportRetryTest extends DownloadExportFixture {
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
                Map.of(Level.OVERWORLD, Map.of(pos, resampled)), work.containers(), 0, OptionalLong.empty()));
        assertEquals(2, coordinator.sampleResources().retainedChunks(),
                "separate entity snapshot wrappers share the same global revision");
    }

    @Test
    void entityBackoffAllowsNewTerrainButKeepsEntitySnapshotsInOrder() {
        var queue = new ExportScheduler.Pending();
        var first = prepared("server:first");
        var failedEntities = first.remaining(
                new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(), Map.of(), true, false,
                        new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0), 0L, false),
                Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), 3L)), 100L);
        var newer = prepared("server:first");
        queue.retry(failedEntities, 100L);
        queue.enqueue(newer);

        var terrain = (ExportWork.Frozen) queue.poll(true, 99L);
        assertSame(newer.terrain(), terrain.terrain());
        assertTrue(terrain.entities().isEmpty());
        assertFalse(queue.hasTerrainForTarget("mirror:server:first"));
        assertTrue(queue.hasEntitiesForTarget("mirror:server:first"));
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
                new ExportWork.Context(initial.context().trigger(), true, initial.context().session()),
                ChunkListener.snapshotOf(Map.of(Level.OVERWORLD, chunks)), initial.entities(),
                Map.of(Level.OVERWORLD,
                        Map.of(BlockPos.ZERO, new CompoundTag(), new BlockPos(16, 0, 0), new CompoundTag())),
                0L, OptionalLong.empty());
        var result =
                new ChunkExporter.ExportResult(Map.of(Level.OVERWORLD,
                                                       Map.of(a, new ChunkExporter.WriteStamp(3L, 100L), b,
                                                               new ChunkExporter.WriteStamp(5L, 200L))),
                        Map.of(), Map.of(), Map.of(), true, false,
                        new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0), 0L, false);

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
                new ExportWork.Context(old.context().trigger(), false, old.context().session()),
                ChunkListener.snapshotOf(Map.of()), old.entities(), Map.of(), 0L, OptionalLong.empty());
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

        assertTrue((queue.hasTerrainForTarget("mirror:server:first")
                || queue.hasEntitiesForTarget("mirror:server:first")));
        assertSame(other, queue.poll(false, 99L));
        assertNull(queue.poll(false, 99L));
        assertSame(failed, queue.poll(true, 100L));
        assertSame(newer, queue.poll(true, 100L));
        assertNull(queue.poll(true, 100L));
        assertSame(live, queue.poll(false, 100L));
        assertFalse((queue.hasTerrainForTarget("mirror:server:first")
                || queue.hasEntitiesForTarget("mirror:server:first")));
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
    void aRetryRetainsVerifiedCaptureTimeAndAnUnknownFrozenSource() {
        var request = new ExportWork.Frozen(
                new ExportWork.Context(ExportWork.Trigger.RETRY, true, ExportTestTargets.session("unknown")),
                ChunkListener.snapshotOf(Map.of()), Map.of(), Map.of(), 123L, OptionalLong.empty());

        var normalized = request.retry();
        assertEquals("mirror:unknown", normalized.context().session().id());
        assertEquals(123L, normalized.durableCaptureTime());
        assertSame(request.terrain(), normalized.terrain());
    }
}
