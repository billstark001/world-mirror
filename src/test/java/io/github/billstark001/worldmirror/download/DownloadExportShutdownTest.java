package io.github.billstark001.worldmirror.download;

import java.util.OptionalLong;
import io.github.billstark001.worldmirror.core.ChunkListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
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

class DownloadExportShutdownTest extends DownloadExportFixture {
    @Test
    void shutdownTransfersFailedInFlightWorkAndDrainsOtherSourcesOnce(@TempDir Path worlds) throws Exception {
        var scheduler = new ExportScheduler();
        var executor = DownloadExportCoordinator.createExportExecutor();
        var attempts = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        java.util.function.Function<ExportWork.Frozen, DownloadExportCoordinator.ExportJob> jobs = work
                -> new DownloadExportCoordinator.ExportJob(work,
                        ()
                                -> {
                            String source = work.context().session().id();
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
    void shutdownAttemptsFrozenWorkOnceAndStillReleasesTerrainAfterAnEntityFailure() {
        var queue = new ExportScheduler.Pending();
        var first = prepared("server:first");
        var failedEntities = new ExportWork.Frozen(
                new ExportWork.Context(first.context().trigger(), false, first.context().session()),
                ChunkListener.snapshotOf(Map.of()), first.entities(), Map.of(), 0L, OptionalLong.empty());
        queue.retry(failedEntities, 200L);
        queue.enqueue(first);
        queue.enqueue(prepared("server:other"));
        queue.prepareForShutdown();

        assertSame(failedEntities, queue.poll(true, 100L));
        queue.retry(failedEntities, 101L);
        var terrain = (ExportWork.Frozen) queue.poll(true, 100L);
        assertSame(first.terrain(), terrain.terrain());
        assertTrue(terrain.entities().isEmpty());
        assertEquals("mirror:server:other", queue.poll(true, 100L).context().session().id());
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
}
