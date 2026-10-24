package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ExportProgress;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static io.github.billstark001.worldmirror.download.ExportStatus.State.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportStatusStoreTest {
    private static final ChunkPos POS = new ChunkPos(-33, 65);

    @Test
    void mixedBatchCountersMatchItsPublishedChunkReceipts() {
        var chunks = new java.util.HashMap<ChunkPos,ChunkListener.CapturedChunk>();
        Map<ChunkPos,Long> written = new java.util.HashMap<>();
        for (int n = 0; n < 100; n++) {
            var pos = new ChunkPos(n,0);
            chunks.put(pos,new ChunkListener.CapturedChunk(new CompoundTag(),100,n+1));
            if (n%2==0) written.put(pos,(long)n+1);
        }
        var base = work("mixed",-1,-1);
        var work = new ExportWork.Frozen(base.context(),ChunkListener.snapshotOf(Map.of(Level.OVERWORLD,chunks)),
                Map.of(),Map.of(),base.settings(),0,OptionalLong.empty());
        var store = new ExportStatusStore();
        store.queued(work);
        store.writing(work,ExportProgress.Part.TERRAIN,Level.OVERWORLD,chunks.keySet());
        assertEquals(100,store.health("mixed").counts().writing());
        var result = new ChunkExporter.ExportResult(Map.of(),Map.of(),Map.of(),Map.of(),false,true,
                new ChunkExporter.ExportTimings(0,0,0,0,0,0,0));
        var receipts = Map.of(Level.OVERWORLD,written);
        var outcome = new DownloadExportCoordinator.ExportOutcome(result,receipts,work.remaining(result,receipts,100),true,true,0);
        var counts = store.finish(work,outcome).counts();
        assertEquals(new ExportStatus.Counts(0,0,50,0,50,50,50,0),counts);
        assertEquals(counts,store.inRange("mixed",Level.OVERWORLD,0,99,0,0).stream()
                .map(ExportStatus.Chunk::counts).reduce(ExportStatus.Counts.EMPTY,ExportStatus.Counts::plus));
    }

    @Test
    void idleViewsReadPublishedVersionAndHealthWithoutWaitingForBulkMutationLock() throws Exception {
        var store = new ExportStatusStore();
        var work = work("a",1,-1);
        store.queued(work);
        long version = store.version("a",Level.OVERWORLD);
        var health = store.health("a");
        synchronized (store) {
            var read = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                assertEquals(version,store.version("a",Level.OVERWORLD));
                assertSame(health,store.health("a"));
                assertEquals(1,store.inRange("a",Level.OVERWORLD,-33,-33,65,65).size());
                return true;
            });
            assertTrue(read.get(2,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void idempotentQueuesReusePublishedHealthAndChunkObservations() {
        var store = new ExportStatusStore();
        var work = work("a",1,2);
        store.queued(work);
        var health = store.health("a");
        var chunk = chunk(store,"a");
        long version = store.version("a",Level.OVERWORLD);
        for (int attempt = 0; attempt < 100; attempt++) store.queued(work);
        assertSame(health,store.health("a"));
        assertSame(chunk,chunk(store,"a"));
        assertSame(chunk,chunk.withTerrain(1));
        assertEquals(version,store.version("a",Level.OVERWORLD));
    }

    @Test
    void terrainAndEntitiesAdvanceIndependentlyThroughAllFiveStates() {
        var store = new ExportStatusStore();
        var work = work("a", 1, 7);
        store.queued(work);
        assertEquals(QUEUED, chunk(store, "a").state());
        store.writing(work, ExportProgress.Part.TERRAIN, Level.OVERWORLD, Set.of(POS));
        assertEquals(WRITING, chunk(store, "a").state());
        store.finish(work, outcome(work, false, false, true));
        assertEquals(FAILED, chunk(store, "a").state());
        store.writing(work, ExportProgress.Part.TERRAIN, Level.OVERWORLD, Set.of(POS));
        assertEquals(WRITING, chunk(store, "a").state());
        assertTrue(chunk(store, "a").hasFailure(), "retry must not erase an unresolved failure");
        store.finish(work, outcome(work, true, false, true));
        assertEquals(PARTIAL, chunk(store, "a").state());
        assertEquals(ExportStatus.FailureStage.ENTITIES, store.health("a").stage());
        var newerTerrain = work("a", 2, -1);
        store.queued(newerTerrain);
        store.finish(newerTerrain, outcome(newerTerrain, true, true, true));
        assertEquals(PARTIAL, chunk(store, "a").state(), "a later terrain pass cannot recover failed entities");
        assertTrue(store.health("a").degraded());
        store.finish(work, outcome(work, true, true, true));
        assertEquals(SUCCEEDED, chunk(store, "a").state());
        assertTrue(store.health("a").settled());
        assertEquals(1, store.health("a").counts().succeeded());
    }

    @Test
    void staleReceiptsCannotCompleteNewerCapturesOrRegressNewerSuccess() {
        var store = new ExportStatusStore();
        var old = work("a", 1, 3);
        var next = work("a", 2, 4);
        store.queued(old);
        store.writing(old, ExportProgress.Part.TERRAIN, Level.OVERWORLD, Set.of(POS));
        store.queued(next);
        assertEquals(WRITING, chunk(store, "a").state(), "a newer queued capture must not hide an active older write");
        store.finish(old, outcome(old, true, true, true));
        assertEquals(QUEUED, chunk(store, "a").state());
        store.finish(next, outcome(next, true, true, true));
        store.finish(old, outcome(old, false, false, true));
        assertEquals(SUCCEEDED, chunk(store, "a").state());
        assertFalse(chunk(store, "a").hasFailure());
    }

    @Test
    void metadataOnlyRetryClearsOnlyCoveredMetadataObligations() {
        var store = new ExportStatusStore();
        var work = work("a", 2, -1);
        store.queued(work);
        var failed = outcome(work, true, true, false);
        store.finish(work, failed);
        assertEquals(PARTIAL, chunk(store, "a").state());
        assertTrue(chunk(store, "a").metadataPending());
        var insufficient = work("a", -1, -1);
        store.finish(insufficient, outcome(insufficient, true, true, true));
        assertTrue(chunk(store, "a").metadataPending());
        var retry = failed.remaining().retry();
        assertFalse(retry.hasTerrain());
        store.finish(retry, outcome(retry, true, true, true));
        assertEquals(SUCCEEDED, chunk(store, "a").state());
        assertFalse(chunk(store, "a").metadataPending());
    }

    @Test
    void sourcesAndDimensionsRemainIsolatedAndRepeatedRetriesDoNotGrowHistory() {
        var store = new ExportStatusStore();
        var a = work("a", 1, -1);
        var b = work("b", 2, -1);
        store.queued(a);
        store.queued(b);
        long before = store.version("b", Level.OVERWORLD);
        for (int attempt = 0; attempt < 100; attempt++) {
            store.queued(a);
            store.finish(a, outcome(a, false, false, true));
        }
        assertEquals(before, store.version("b", Level.OVERWORLD));
        assertEquals(QUEUED, chunk(store, "b").state());
        assertEquals(1, store.health("a").counts().total());
        assertEquals(1, store.inRange("a", Level.OVERWORLD, -34, -32, 64, 66).size());
        assertTrue(store.inRange("a", Level.NETHER, -100, 100, -100, 100).isEmpty());
        assertTrue(store.inRange("a", Level.OVERWORLD, 0, 100, 0, 100).isEmpty());
    }

    @Test
    void completedObservationSurvivesWithoutLiveCacheAndNewCaptureBecomesQueued() {
        var store = new ExportStatusStore();
        var work = work("a", 1, -1);
        store.queued(work);
        store.finish(work, outcome(work, true, true, true));
        var observed = chunk(store, "a");
        assertEquals(SUCCEEDED, observed.state());
        assertEquals(QUEUED, observed.withTerrain(2).state());
        assertEquals(SUCCEEDED, observed.state(), "viewport enrichment cannot mutate durable observations");
        long version = store.version("a", Level.OVERWORLD);
        store.finish(work, outcome(work, true, true, true));
        assertEquals(version, store.version("a", Level.OVERWORLD), "unchanged results must not invalidate geometry");
    }

    @Test
    void worldOnlyFailuresRemainUntilMetadataIsActuallyCommitted() {
        var store = new ExportStatusStore();
        var empty = work("a", -1, -1);
        store.finish(empty, DownloadExportCoordinator.ExportOutcome.failed(empty));
        assertTrue(store.health("a").worldFailure());
        var work = work("a", 1, -1);
        store.queued(work);
        store.finish(work, outcome(work, false, false, false));
        assertTrue(store.health("a").worldFailure());
        store.finish(work, outcome(work, true, true, true));
        assertTrue(store.health("a").settled());
    }

    private static ExportStatus.Chunk chunk(ExportStatusStore store, String source) {
        return store.inRange(source, Level.OVERWORLD, -33, -33, 65, 65).getFirst();
    }
    static ExportWork.Frozen work(String source, long terrain, long entity) {
        return new ExportWork.Frozen(new ExportWork.Context(DownloadExportCoordinator.Trigger.PERIODIC, false,
                new ExportWork.Source(source, "server")),
                ChunkListener.snapshotOf(terrain < 0 ? Map.of() : Map.of(Level.OVERWORLD, Map.of(POS,
                        new ChunkListener.CapturedChunk(new CompoundTag(), terrain * 100, terrain)))),
                entity < 0 ? Map.of() : Map.of(Level.OVERWORLD, Map.of(POS,
                        new EntityTracker.ChunkUpdate(entity, EntityTracker.Observation.PARTIAL, Map.of(), Set.of()))),
                Map.of(), WorldSettingsSnapshot.defaults(), 0, OptionalLong.empty());
    }
    static DownloadExportCoordinator.ExportOutcome outcome(ExportWork.Frozen work, boolean terrain, boolean entities, boolean metadata) {
        Map<ChunkPos, Long> terrainReceipts = new java.util.HashMap<>();
        Map<ChunkPos, Long> entityReceipts = new java.util.HashMap<>();
        if (terrain) work.terrain().chunks().getOrDefault(Level.OVERWORLD, Map.of()).forEach((pos, capture) -> terrainReceipts.put(pos, capture.revision()));
        if (entities) work.entities().getOrDefault(Level.OVERWORLD, Map.of()).forEach((pos, update) -> entityReceipts.put(pos, update.revision()));
        var result = new ChunkExporter.ExportResult(Map.of(), Map.of(), Map.of(), Map.of(Level.OVERWORLD, entityReceipts),
                terrain, entities, new ChunkExporter.ExportTimings(0,0,0,0,0,0,0));
        var receipts = Map.of(Level.OVERWORLD, terrainReceipts);
        long time = work.terrain().chunks().getOrDefault(Level.OVERWORLD, Map.of()).values().stream()
                .mapToLong(ChunkListener.CapturedChunk::capturedAtMs).max().orElse(work.durableCaptureTime());
        return new DownloadExportCoordinator.ExportOutcome(result, receipts, work.remaining(result, receipts, time),
                true, metadata, 0);
    }
}
