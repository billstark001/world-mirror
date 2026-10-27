package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode;
import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;
import io.github.billstark001.worldmirror.download.ExportStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExportStatusRenderingTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @EnumSource(value = ExportStatus.State.class, names = {"QUEUED", "WRITING", "SUCCEEDED"})
    void normalExportsDoNotAddAnyBuiltInMapDrawing(ExportStatus.State state) {
        var saved = saved(false);
        for (var records : List.of(Map.<Long, LiveChunkStatusSnapshot.Record>of(), Map.of(0L,
                new LiveChunkStatusSnapshot.Record(0, 0, 200, LiveChunkStatusSnapshot.State.PENDING_WRITE, true)))) {
            var before = new LiveChunkStatusSnapshot(records, Map.of());
            var after = new LiveChunkStatusSnapshot(records, Map.of(0L, status(0, 0, state)));
            for (int size : new int[]{1, 5, 8}) {
                for (int threshold : new int[]{1, 16}) {
                    for (boolean transparent : new boolean[]{false, true}) {
                        assertEquals(builtIn(saved, before, size, threshold, transparent),
                                builtIn(saved, after, size, threshold, transparent));
                    }
                }
            }
            assertTrue(new ChunkMapProjection().get(saved, after,
                    new ChunkBounds(0, 0, 0, 0), 200, 4096, true).marks().isEmpty());
            assertEquals(state, after.exportAt(0, 0).state(), "normal states remain available to tooltips");
        }
    }

    @ParameterizedTest
    @EnumSource(value = ExportStatus.State.class, names = {"FAILED", "PARTIAL"})
    void failuresRemainVisibleOnDenseSparseAndSmallMapsWithoutTerrain(ExportStatus.State state) {
        var live = new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, status(0, 0, state)));
        for (int size : new int[]{1, 5, 8}) {
            for (int threshold : new int[]{1, 16}) {
                var colors = builtIn(ChunkStatusSnapshot.EMPTY, live, size, threshold, false)
                        .stream().map(Rect::color).toList();
                int marker = state == ExportStatus.State.PARTIAL && size < 8
                        ? ExportStatusMarkers.PARTIAL : ExportStatusMarkers.FAILED;
                assertTrue(colors.contains(marker));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ExportStatus.State.class)
    void builtInConflictsRemainVisibleAlongsideExportWarnings(ExportStatus.State state) {
        var live = new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, status(0, 0, state)));
        for (int size : new int[]{5, 8}) {
            for (int threshold : new int[]{1, 16}) {
                var colors = builtIn(saved(true), live, size, threshold, false)
                        .stream().map(Rect::color).toList();
                assertTrue(colors.contains(0xFF00C800));
                assertTrue(colors.contains(ExportStatusMarkers.CONFLICT));
                assertEquals(state == ExportStatus.State.FAILED || state == ExportStatus.State.PARTIAL,
                        colors.contains(ExportStatusMarkers.FAILED) || colors.contains(ExportStatusMarkers.PARTIAL));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ExportStatus.State.class)
    void xaeroAndBothWorldOverlayModesIgnoreExportMarkers(ExportStatus.State state) {
        var live = new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, status(0, 0, state)));
        for (boolean conflict : new boolean[]{false, true}) {
            var saved = saved(conflict);
            for (double pixelsPerBlock : new double[]{.0625, .3125, 1}) {
                assertEquals(xaero(saved, LiveChunkStatusSnapshot.EMPTY, pixelsPerBlock),
                        xaero(saved, live, pixelsPerBlock));
            }
            for (var mode : WorldChunkOverlayColorMode.values()) {
                var settings = new ChunkWorldOverlay.Settings(96, 50, true, 60, mode);
                var range = new ChunkBounds(0, 0, 0, 0);
                assertEquals(ChunkWorldOverlay.build(saved, LiveChunkStatusSnapshot.EMPTY, range, settings, 200),
                        ChunkWorldOverlay.build(saved, live, range, settings, 200));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ExportStatus.State.class)
    void exportObservationsAloneDoNotCreateOverlayGeometry(ExportStatus.State state) {
        var live = new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, status(0, 0, state)));
        assertTrue(xaero(ChunkStatusSnapshot.EMPTY, live, 1).isEmpty());
        for (var mode : WorldChunkOverlayColorMode.values()) {
            var overlay = ChunkWorldOverlay.build(ChunkStatusSnapshot.EMPTY, live, new ChunkBounds(0, 0, 0, 0),
                    new ChunkWorldOverlay.Settings(96, 0, false, 60, mode), 200);
            assertTrue(overlay.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(ExportStatus.State.class)
    void tooltipsRetainAllExportStatesAndSeparateTerrainAndEntityResults(ExportStatus.State state) {
        var status = status(0, 0, state);
        var lines = new ArrayList<Component>();
        ExportStatusText.append(lines, status, true);
        assertEquals(List.of("screen.worldmirror.export.state", "screen.worldmirror.export.parts"),
                lines.stream().map(line -> text(line).getKey()).toList());
        assertEquals("screen.worldmirror.export.state." + state.name().toLowerCase(Locale.ROOT),
                text((Component) text(lines.getFirst()).getArgs()[0]).getKey());
        var parts = text(lines.get(1)).getArgs();
        assertEquals("screen.worldmirror.export.part." + status.terrain().state().name().toLowerCase(Locale.ROOT),
                text((Component) parts[0]).getKey());
        assertEquals("screen.worldmirror.export.part." + status.entities().state().name().toLowerCase(Locale.ROOT),
                text((Component) parts[1]).getKey());
    }

    @Test
    void retryWarningsSurviveUntilResolvedAndMetadataDetailsRemainInTooltips() {
        for (var part : List.of(new ExportStatus.Part(1, -1, 1, 1), new ExportStatus.Part(2, -1, 1, -1))) {
            var retry = new ExportStatus.Chunk(0, 0, part, ExportStatus.Part.NONE, false, 0);
            var live = new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, retry));
            for (int size : new int[]{1, 5, 8}) {
                assertTrue(builtIn(saved(false), live, size, 1, false).stream()
                        .anyMatch(rect -> rect.color() == ExportStatusMarkers.FAILED));
            }
            var lines = new ArrayList<Component>();
            ExportStatusText.append(lines, retry, true);
            assertEquals("screen.worldmirror.export.unresolved", text(lines.getLast()).getKey());
        }
        var metadata = new ExportStatus.Chunk(0, 0, new ExportStatus.Part(1, 1, -1, -1),
                ExportStatus.Part.NONE, true, 100);
        var lines = new ArrayList<Component>();
        ExportStatusText.append(lines, metadata, true);
        assertEquals("screen.worldmirror.export.metadata", text(lines.getLast()).getKey());
        assertEquals(ExportStatusMarkers.PARTIAL, ExportStatusMarkers.compact(metadata.counts()));
        var projection = new ChunkMapProjection();
        var saved = saved(false);
        var range = new ChunkBounds(0, 0, 0, 0);
        assertFalse(projection.get(saved, new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, metadata)),
                range, 200, 4096, true).marks().isEmpty());
        var recovered = new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, status(0, 0, ExportStatus.State.SUCCEEDED)));
        assertTrue(projection.get(saved, recovered, range, 200, 4096, true).marks().isEmpty());
        lines.clear();
        ExportStatusText.append(lines, null, true);
        assertEquals("screen.worldmirror.export.historical", text(lines.getFirst()).getKey());
    }

    @Test
    void largeCacheBucketsRetainEveryStateCountAndWarmQueriesDoNotRescan() {
        Map<Long, ExportStatus.Chunk> records = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            int x = i % 250 - 125, z = i / 250 - 100;
            records.put(ChunkBounds.key(x, z), status(x, z, ExportStatus.State.values()[i % 5]));
        }
        var live = new LiveChunkStatusSnapshot(Map.of(), records);
        var range = new ChunkBounds(-512, 511, -512, 511);
        var layout = ChunkBucketLayout.withinBudget(range, 1024);
        var summaries = live.index().query(layout);
        var counts = summaries.values().stream().map(ChunkBucketIndex.Summary::exports)
                .reduce(ExportStatus.Counts.EMPTY, ExportStatus.Counts::plus);
        assertEquals(new ExportStatus.Counts(10_000, 10_000, 10_000, 10_000, 10_000, 20_000, 10_000, 0), counts);
        long visits = live.index().visits();
        for (int frame = 0; frame < 120; frame++) assertEquals(summaries, live.index().query(layout));
        assertEquals(visits, live.index().visits());
        var map = new ChunkMapProjection().get(ChunkStatusSnapshot.EMPTY, live, range, 200, 1024, false);
        assertTrue(map.marks().size() <= 1024);
        assertEquals(counts.unresolved(), map.marks().values().stream()
                .mapToInt(summary -> summary.exports().unresolved()).sum());
        var geometry = new ChunkWorldOverlay.GeometryCache();
        var settings = new ChunkWorldOverlay.Settings(96, 0, false, 60, WorldChunkOverlayColorMode.SESSION);
        var overlay = geometry.get(ChunkStatusSnapshot.EMPTY, live, range, settings, 0);
        assertTrue(overlay.isEmpty());
        for (int frame = 0; frame < 120; frame++) {
            assertSame(overlay, geometry.get(ChunkStatusSnapshot.EMPTY, live, range, settings, frame * 50));
        }
    }

    private record Rect(int left, int top, int right, int bottom, int color) { }

    private static List<Rect> builtIn(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live,
                                     int size, int threshold, boolean transparent) {
        var drawn = new ArrayList<Rect>();
        ChunkMapView.renderBuiltIn((x1, z1, x2, z2, color) -> drawn.add(new Rect(x1, z1, x2, z2, color)),
                new ChunkMapProjection(), saved, live,
                new ChunkMapView.BuiltInViewport(100, 100, 0, 0, size, transparent), 200, threshold);
        return drawn;
    }

    private static List<Rect> xaero(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, double pixelsPerBlock) {
        var drawn = new ArrayList<Rect>();
        ChunkMapView.renderOverlay((x1, z1, x2, z2, color) -> drawn.add(new Rect(x1, z1, x2, z2, color)),
                new ChunkMapProjection(), coordinate -> 50 + (int) Math.round(coordinate * pixelsPerBlock),
                coordinate -> 50 + (int) Math.round(coordinate * pixelsPerBlock),
                saved, live, 100, 100, 0, 0, pixelsPerBlock, 200, 1000);
        return drawn;
    }

    private static ChunkStatusSnapshot saved(boolean conflict) {
        return new ChunkStatusSnapshot(List.of(new ChunkRecord(0, 0, 100, "world_mirror")),
                conflict ? Set.of(new ChunkPos(0, 0)) : Set.of());
    }

    private static TranslatableContents text(Component component) {
        return (TranslatableContents) component.getContents();
    }

    private static ExportStatus.Chunk status(int x, int z, ExportStatus.State state) {
        var part = switch (state) {
            case QUEUED -> new ExportStatus.Part(1, -1, -1, -1);
            case WRITING -> new ExportStatus.Part(1, -1, -1, 1);
            case FAILED -> new ExportStatus.Part(1, -1, 1, -1);
            case PARTIAL, SUCCEEDED -> new ExportStatus.Part(1, 1, -1, -1);
        };
        return new ExportStatus.Chunk(x, z, part,
                state == ExportStatus.State.PARTIAL ? new ExportStatus.Part(1, -1, 1, -1) : ExportStatus.Part.NONE, false, 0);
    }
}
