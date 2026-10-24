package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode.MAP;
import static io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode.SESSION;
import static io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot.State.CACHED;
import static io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot.State.PENDING_CAPTURE;
import static io.github.billstark001.worldmirror.ui.LiveChunkStatusSnapshot.State.PENDING_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkWorldOverlayTest {
    @Test
    void mergesAdjacentChunksAndUsesCurrentSessionColor() {
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(-2, 0, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(-1, 0, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(0, 0, 20, "world_mirror"),
                new ChunkDatabase.ChunkRecord(1, 0, 20, "replay_import")), Set.of());
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(-2, 1, 0, 0), new ChunkWorldOverlay.Settings(64, 15, true, 60, SESSION), 0L);

        assertEquals(List.of(
                new ChunkWorldOverlay.OverlayChunk(-2, 0, 0, 1, 64, 0xFF2878E0),
                new ChunkWorldOverlay.OverlayChunk(0, 1, 0, 1, 64, 0xFF20E060),
                new ChunkWorldOverlay.OverlayChunk(1, 2, 0, 1, 64, 0xFF2878E0)),
                overlay.chunks());
    }

    @ParameterizedTest
    @EnumSource(WorldChunkOverlayColorMode.class)
    void largeDenseViewStaysWithinMapOverlayBucketBudget(WorldChunkOverlayColorMode mode) {
        ArrayList<ChunkDatabase.ChunkRecord> records = new ArrayList<>();
        for (int z = -128; z <= 128; z++) {
            for (int x = -128; x <= 128; x++) {
                records.add(new ChunkDatabase.ChunkRecord(x, z, 10, "world_mirror"));
            }
        }
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(records, Set.of());
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(-128, 128, -128, 128), new ChunkWorldOverlay.Settings(96, 0, true, 60, mode), 20);
        assertTrue(overlay.chunks().size() <= 4_096);
        assertTrue(overlay.chunks().size() < records.size() / 10);
    }

    @Test
    void sessionUsesOnlyItsThreeColorsForLiveStatesAndHistoricalCoverage() {
        var persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 20, "world_mirror"),
                new ChunkDatabase.ChunkRecord(7, 0, 20, "replay_import"),
                new ChunkDatabase.ChunkRecord(8, 0, 20, "external")), Set.of());
        var live = live(
                new LiveChunkStatusSnapshot.Record(0, 0, 0, PENDING_CAPTURE, true),
                new LiveChunkStatusSnapshot.Record(1, 0, 0, PENDING_CAPTURE, false),
                new LiveChunkStatusSnapshot.Record(2, 0, 20, PENDING_WRITE, true),
                new LiveChunkStatusSnapshot.Record(3, 0, 20, PENDING_WRITE, false),
                new LiveChunkStatusSnapshot.Record(4, 0, 20, CACHED, true),
                new LiveChunkStatusSnapshot.Record(5, 0, 20, CACHED, false),
                new LiveChunkStatusSnapshot.Record(6, 0, 10, CACHED, false));
        var overlay = ChunkWorldOverlay.build(persistent, live, new ChunkBounds(0, 8, 0, 0), new ChunkWorldOverlay.Settings(96, 15, true, 60, SESSION), 30);

        assertEquals(0xFFFFD21F, colorAt(overlay, 0, 0));
        assertEquals(0xFFFFD21F, colorAt(overlay, 1, 0));
        for (int x = 2; x <= 5; x++) assertEquals(0xFF20E060, colorAt(overlay, x, 0));
        assertEquals(0xFF2878E0, colorAt(overlay, 6, 0));
        assertEquals(0xFF2878E0, colorAt(overlay, 7, 0));
        assertEquals(0, colorAt(overlay, 8, 0));
        assertTrue(overlay.chunks().stream().allMatch(chunk ->
                Set.of(0xFFFFD21F, 0xFF20E060, 0xFF2878E0).contains(chunk.color())));
    }

    @Test
    void withoutAnActiveSessionSavedAndCachedCoverageIsHistoricalBlue() {
        var persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 20, "world_mirror"),
                new ChunkDatabase.ChunkRecord(1, 0, 30, "replay_import")), Set.of());
        var overlay = ChunkWorldOverlay.build(persistent, live(new LiveChunkStatusSnapshot.Record(2, 0, 40, CACHED, false)), new ChunkBounds(0, 2, 0, 0), new ChunkWorldOverlay.Settings(96, 0, true, 60, SESSION), 50);

        assertEquals(List.of(new ChunkWorldOverlay.OverlayChunk(0, 3, 0, 1, 96, 0xFF2878E0)),
                overlay.chunks());
    }

    @Test
    void mapUsesCaptureAgeAndSourceColorsIndependentlyOfSessionMembership() {
        long now = 40L * 24 * 60 * 60 * 1_000;
        var records = List.of(
                new ChunkDatabase.ChunkRecord(0, 0, now, "world_mirror"),
                new ChunkDatabase.ChunkRecord(1, 0, 1L, "world_mirror"),
                new ChunkDatabase.ChunkRecord(2, 0, now - 3_600_000, "replay_import"),
                new ChunkDatabase.ChunkRecord(3, 0, now, "external"));
        var persistent = new ChunkStatusSnapshot(records, Set.of());
        var range = new ChunkBounds(0, 3, 0, 0);
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, range, new ChunkWorldOverlay.Settings(96, 0, true, 60, MAP), now);
        var withSession = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, range, new ChunkWorldOverlay.Settings(96, now, true, 60, MAP), now);

        assertEquals(overlay, withSession);
        assertEquals(0xFF00C800, colorAt(overlay, 0, 0));
        assertEquals(0xFF0000C8, colorAt(overlay, 1, 0));
        assertEquals(0xFFFF9000, colorAt(overlay, 3, 0));
        for (var record : records) {
            assertEquals(ChunkMapStyle.saved(record, now), colorAt(overlay, record.x(), record.z()));
        }

        var session = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, range, new ChunkWorldOverlay.Settings(96, now, true, 60, SESSION), now);
        assertEquals(0xFF2878E0, colorAt(session, 2, 0));
        assertEquals(0, colorAt(session, 3, 0));
    }

    @Test
    void mapDistinguishesAllLiveQueueColorsAndLetsThemReplaceSavedColors() {
        long now = 40L * 24 * 60 * 60 * 1_000;
        var persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, now, "world_mirror")), Set.of());
        var overlay = ChunkWorldOverlay.build(persistent, live(
                new LiveChunkStatusSnapshot.Record(0, 0, 0, PENDING_CAPTURE, true),
                new LiveChunkStatusSnapshot.Record(1, 0, 0, PENDING_CAPTURE, false),
                new LiveChunkStatusSnapshot.Record(2, 0, now, PENDING_WRITE, true),
                new LiveChunkStatusSnapshot.Record(3, 0, now, PENDING_WRITE, false),
                new LiveChunkStatusSnapshot.Record(4, 0, now, CACHED, false),
                new LiveChunkStatusSnapshot.Record(5, 0, 1, CACHED, true)), new ChunkBounds(0, 5, 0, 0), new ChunkWorldOverlay.Settings(96, 0, true, 60, MAP), now);

        int[] expected = {0xFFD8DC30, 0xFFFFD21F, 0xFF20E060, 0xFF91E633, 0xFF00C800, 0xFF0000C8};
        for (int x = 0; x < expected.length; x++) assertEquals(expected[x], colorAt(overlay, x, 0));
    }

    @Test
    void mapColorsAgeWhenTheSameSnapshotIsRebuilt() {
        long capturedAt = 1_000_000;
        var persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, capturedAt, "world_mirror")), Set.of());
        var range = new ChunkBounds(0, 0, 0, 0);
        var fresh = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, range, new ChunkWorldOverlay.Settings(96, capturedAt, true, 60, MAP), capturedAt);
        var old = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, range, new ChunkWorldOverlay.Settings(96, capturedAt, true, 60, MAP), capturedAt + 35L * 24 * 60 * 60 * 1_000);

        assertEquals(0xFF00C800, colorAt(fresh, 0, 0));
        assertEquals(0xFF0000C8, colorAt(old, 0, 0));
    }

    @Test
    void coarseMapBucketsUseNewestSavedRecordsAndTheMapsLiveStatePriority() {
        long now = 40L * 24 * 60 * 60 * 1_000;
        var persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(-2, 0, 1, "world_mirror"),
                new ChunkDatabase.ChunkRecord(-1, 0, 2, "external")), Set.of());
        var range = new ChunkBounds(-128, 128, -128, 128);
        var saved = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, range, new ChunkWorldOverlay.Settings(96, 0, true, 60, MAP), now);
        assertEquals(0xFFFF9000, colorAt(saved, -2, 0));

        var overlay = ChunkWorldOverlay.build(persistent, live(
                new LiveChunkStatusSnapshot.Record(-2, 0, now, CACHED, false),
                new LiveChunkStatusSnapshot.Record(-1, 0, 0, PENDING_CAPTURE, true),
                new LiveChunkStatusSnapshot.Record(126, 0, now, PENDING_WRITE, true),
                new LiveChunkStatusSnapshot.Record(127, 0, 1, PENDING_WRITE, false),
                new LiveChunkStatusSnapshot.Record(128, 0, now, PENDING_WRITE, true),
                new LiveChunkStatusSnapshot.Record(129, 0, 0, PENDING_CAPTURE, false)), range, new ChunkWorldOverlay.Settings(96, 0, true, 60, MAP), now);

        assertEquals(0xFFD8DC30, colorAt(overlay, -2, 0));
        assertEquals(0xFF91E633, colorAt(overlay, 128, 0));
    }

    @Test
    void bucketBudgetAccountsForUnalignedAndNegativeCoordinates() {
        int size = ChunkBucketLayout.sizeFor(-3, 0, -3, 0, 4);
        long width = Math.floorDiv(0, size) - Math.floorDiv(-3, size) + 1L;
        assertTrue(width * width <= 4);
        assertTrue(ChunkBucketLayout.sizeFor(-3, 0, -3, 0, 1) > 0);
    }

    @Test
    void innerGridIsCompleteWithoutDuplicateSharedEdges() {
        ArrayList<ChunkDatabase.ChunkRecord> records = new ArrayList<>();
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                records.add(new ChunkDatabase.ChunkRecord(x, z, 10, "world_mirror"));
            }
        }
        var overlay = ChunkWorldOverlay.build(new ChunkStatusSnapshot(records, Set.of()), LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 1, 0, 1), new ChunkWorldOverlay.Settings(64, 0, true, 60, SESSION), 0L);

        assertEquals(1, overlay.chunks().size());
        assertEquals(6, overlay.edges().size());
        assertEquals(12, overlay.edges().stream().mapToInt(edge ->
                edge.endChunkX() - edge.startChunkX() + edge.endChunkZ() - edge.startChunkZ()).sum());
        assertEquals(2, overlay.edges().stream()
                .filter(ChunkWorldOverlay.OverlayEdge::sameColorInterior).count());
        assertEquals(4, overlay.edges().stream()
                .filter(edge -> !edge.sameColorInterior()).count());

        var withoutGrid = ChunkWorldOverlay.build(new ChunkStatusSnapshot(records, Set.of()), LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 1, 0, 1), new ChunkWorldOverlay.Settings(64, 0, false, 60, SESSION), 0L);
        assertEquals(4, withoutGrid.edges().size());
        assertTrue(withoutGrid.edges().stream().noneMatch(
                ChunkWorldOverlay.OverlayEdge::sameColorInterior));
    }

    @Test
    void statusBoundaryRemainsWhenInnerGridIsHidden() {
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(0, 0, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(1, 0, 20, "world_mirror")), Set.of());
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 1, 0, 0), new ChunkWorldOverlay.Settings(64, 15, false, 60, SESSION), 0L);

        assertTrue(overlay.edges().stream().anyMatch(edge ->
                edge.startChunkX() == 1 && edge.endChunkX() == 1
                        && !edge.sameColorInterior()));
        assertTrue(!overlay.showInnerGrid());
    }

    @Test
    void coarseTilesStayInsideRequestedMaximumRadius() {
        int radius = 16_384;
        ChunkStatusSnapshot persistent = new ChunkStatusSnapshot(List.of(
                new ChunkDatabase.ChunkRecord(-radius, -radius, 10, "world_mirror"),
                new ChunkDatabase.ChunkRecord(radius, radius, 10, "world_mirror")), Set.of());
        var overlay = ChunkWorldOverlay.build(persistent, LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(-radius, radius, -radius, radius), new ChunkWorldOverlay.Settings(64, 0, true, 60, SESSION), 0L);

        assertEquals(2, overlay.chunks().size());
        assertTrue(overlay.chunks().stream().allMatch(chunk ->
                chunk.startChunkX() >= -radius && chunk.endChunkX() <= radius + 1
                        && chunk.startChunkZ() >= -radius && chunk.endChunkZ() <= radius + 1));
        assertTrue(overlay.edges().stream().allMatch(edge ->
                edge.startChunkX() >= -radius && edge.endChunkX() <= radius + 1
                        && edge.startChunkZ() >= -radius && edge.endChunkZ() <= radius + 1));
    }

    private static LiveChunkStatusSnapshot live(LiveChunkStatusSnapshot.Record... records) {
        var indexed = new HashMap<Long, LiveChunkStatusSnapshot.Record>();
        for (var record : records) indexed.put(ChunkBounds.key(record.x(), record.z()), record);
        return new LiveChunkStatusSnapshot(indexed, Map.of());
    }

    @Test
    void largeUniformGridPreservesEveryGridLineWithOnlyLinearVertexWork() {
        var records = new ArrayList<ChunkDatabase.ChunkRecord>();
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) records.add(new ChunkDatabase.ChunkRecord(x, z, 10, "world_mirror"));
        }
        var overlay = ChunkWorldOverlay.build(new ChunkStatusSnapshot(records, Set.of()), LiveChunkStatusSnapshot.EMPTY, new ChunkBounds(0, 63, 0, 63), new ChunkWorldOverlay.Settings(96, 0, true, 60, SESSION), 0L);
        assertEquals(1, overlay.chunks().size());
        assertEquals(130, overlay.edges().size(), "8320 one-chunk strips become 130 continuous lines");
        assertEquals(8320, overlay.edges().stream().mapToInt(edge ->
                edge.endChunkX() - edge.startChunkX() + edge.endChunkZ() - edge.startChunkZ()).sum());
    }

    @Test
    void unchangedLargeHistoryDoesNotRebuildAtThePollingCadence() {
        var records = new ArrayList<ChunkDatabase.ChunkRecord>();
        for (int n = 0; n < 100_000; n++) records.add(new ChunkDatabase.ChunkRecord(n % 1_000, n / 1_000, 1, "world_mirror"));
        var persistent = new ChunkStatusSnapshot(records, Set.of());
        var cache = new ChunkWorldOverlay.GeometryCache();
        var range = new ChunkBounds(0, 127, 0, 127);
        var settings = new ChunkWorldOverlay.Settings(96, 0, true, 60, SESSION);
        var first = cache.get(persistent, LiveChunkStatusSnapshot.EMPTY, range, settings, 0L);
        long visits = persistent.index().visits();
        for (int frame = 1; frame <= 120; frame++) {
            org.junit.jupiter.api.Assertions.assertSame(first, cache.get(persistent, LiveChunkStatusSnapshot.EMPTY,
                    range, settings, frame * 500L));
        }
        assertEquals(visits, persistent.index().visits());
        var changed = live(new LiveChunkStatusSnapshot.Record(0, 0, 0, PENDING_CAPTURE, false));
        org.junit.jupiter.api.Assertions.assertNotSame(first, cache.get(persistent, changed, range, settings, 61_000L));
        cache.clear();
        org.junit.jupiter.api.Assertions.assertNotSame(first, cache.get(persistent, LiveChunkStatusSnapshot.EMPTY,
                range, settings, 62_000L));
    }

    private static int colorAt(ChunkWorldOverlay.OverlaySnapshot overlay, int x, int z) {
        return overlay.chunks().stream()
                .filter(chunk -> x >= chunk.startChunkX() && x < chunk.endChunkX()
                        && z >= chunk.startChunkZ() && z < chunk.endChunkZ())
                .mapToInt(ChunkWorldOverlay.OverlayChunk::color).findFirst().orElse(0);
    }
}
