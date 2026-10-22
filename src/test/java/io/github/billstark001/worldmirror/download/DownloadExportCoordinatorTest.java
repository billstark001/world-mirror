package io.github.billstark001.worldmirror.download;

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

import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        DownloadExportCoordinator.Request pending = new DownloadExportCoordinator.Request(
                DownloadExportCoordinator.Trigger.STOP, false, true,
                "server:old", "server", containers(oldContainer), oldEntities, oldTerrain);
        DownloadExportCoordinator.Request request = new DownloadExportCoordinator.Request(
                DownloadExportCoordinator.Trigger.MANUAL, true, false,
                "server:new", "server", containers(newContainer));

        var queue = new DownloadExportCoordinator.DeferredRequests();
        queue.offer(pending);
        queue.offer(request);
        DownloadExportCoordinator.Request frozen = queue.poll(false);

        assertEquals(DownloadExportCoordinator.Trigger.STOP, frozen.trigger());
        assertEquals("server:old", frozen.preferredSourceId());
        assertEquals("old", frozen.containerSnapshot().get(Level.OVERWORLD)
                .get(BlockPos.ZERO).getStringOr("source", ""));
        assertSame(oldEntities, frozen.entitySnapshot());
        assertSame(oldTerrain, frozen.terrainSnapshot());
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
        queue.offer(new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.MANUAL,
                true, false, "server:live", "server", null));
        queue.offer(frozen);

        queue.clearLive();

        assertSame(frozen, queue.poll(true));
        assertNull(queue.poll(false));
    }

    @Test
    void liveRequestsWaitForCaptureAndCoalesceTheirNotificationAndPriority() {
        var queue = new DownloadExportCoordinator.DeferredRequests();
        assertFalse(queue.offer(new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.MANUAL,
                true, false, "server:live", "server", null)));
        assertTrue(queue.offer(new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.PERIODIC,
                false, true, "server:live", "server", null)));

        assertNull(queue.poll(true));
        var live = queue.poll(false);
        assertEquals(DownloadExportCoordinator.Trigger.MANUAL, live.trigger());
        assertTrue(live.shouldNotify());
        assertFalse(live.preCaptureAlreadyDone());
        assertNull(queue.poll(false));
    }

    private static DownloadExportCoordinator.Request prepared(String source) {
        return new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.STOP,
                false, true, source, "server", Map.of(), Map.of(), ChunkListener.snapshotOf(Map.of()));
    }

    @Test
    void preparedOldSnapshotCannotSettleLiveEntityCaptureSignals() {
        var coordinator = new DownloadExportCoordinator(new DownloadCaptureQueue(() -> false, 1),
                () -> DownloadPipeline.create(io.github.billstark001.worldmirror.config.ModConfig
                        .DownloadPipelineMode.STABLE_PERIODIC));
        var prepared = new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.STOP,
                false, true, "server:old", "server", Map.of(), Map.of(),
                ChunkListener.snapshotOf(Map.of()));
        coordinator.acknowledgeEntityWork(prepared, 1L, true);
        assertTrue(coordinator.hasEntityWork());

        var live = new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.MANUAL,
                false, false, "server:new", "server", null);
        coordinator.markEntitiesDirty();
        coordinator.acknowledgeEntityWork(live, 1L, true);
        assertTrue(coordinator.hasEntityWork());
        coordinator.acknowledgeEntityWork(live, 2L, false);
        assertTrue(coordinator.hasEntityWork());
        coordinator.acknowledgeEntityWork(live, 2L, true);
        assertFalse(coordinator.hasEntityWork());
    }

    @Test
    void queuedSnapshotsKeepTheSourceResolvedBeforeDeferral() {
        var unidentified = new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.STOP,
                false, true, null, null, Map.of(), Map.of(), ChunkListener.snapshotOf(Map.of()));
        var identified = DownloadExportCoordinator.withSourceIdentity(unidentified, "server:old", "server");
        var afterWorldChange = DownloadExportCoordinator.withSourceIdentity(identified, "server:new", "server");

        assertEquals("server:old", afterWorldChange.preferredSourceId());
        assertSame(unidentified.terrainSnapshot(), afterWorldChange.terrainSnapshot());
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

        coordinator.dispatchAcknowledgements(prepared("server:old"), 1L, emptyResult,
                Map.of(Level.OVERWORLD, revisions), Map.of(), true);

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
        var live = new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.MANUAL,
                true, false, "server:first", "server", null);
        queue.retry(failed, 100L);
        queue.offer(newer);
        queue.offer(other);
        queue.offer(live);

        assertTrue(queue.hasFrozenForSource("server:first"));
        assertSame(other, queue.poll(false, 99L));
        assertNull(queue.poll(false, 99L));
        assertSame(failed, queue.poll(true, 100L));
        assertSame(newer, queue.poll(true, 100L));
        assertNull(queue.poll(true, 100L));
        assertSame(live, queue.poll(false, 100L));
        assertFalse(queue.hasFrozenForSource("server:first"));
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
    void aRetryRetainsVerifiedCaptureTimeAndAnUnknownFrozenSource() {
        var request = new DownloadExportCoordinator.Request(DownloadExportCoordinator.Trigger.RETRY,
                true, true, "unknown", "server", Map.of(), Map.of(), ChunkListener.snapshotOf(Map.of()),
                WorldSettingsSnapshot.defaults(), 123L);

        var normalized = DownloadExportCoordinator.withSourceIdentity(request, "server:new", "server");

        assertEquals("unknown", normalized.preferredSourceId());
        assertEquals(123L, normalized.knownDurableCaptureTime());
        assertSame(request.terrainSnapshot(), normalized.terrainSnapshot());
    }

    private static Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers(
            CompoundTag value) {
        return Map.of(Level.OVERWORLD, Map.of(BlockPos.ZERO, value));
    }
}
