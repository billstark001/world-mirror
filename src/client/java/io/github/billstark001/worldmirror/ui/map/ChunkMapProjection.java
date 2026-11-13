package io.github.billstark001.worldmirror.ui.map;

import io.github.billstark001.worldmirror.util.ChunkRegionIndex;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Per-view derived geometry; source indices share their complete-bucket summaries. */
public final class ChunkMapProjection {
    private static final long COLOR_REFRESH_MS = 1_000L;
    private record Key(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBucketLayout layout,
            boolean dense) {}
    record Frame(int bucketSize, long projectedBuckets, int occupiedBuckets, boolean dense,
            List<ChunkTileGeometry.Run> runs, List<ChunkTileGeometry.Boundary> boundaries,
            Map<Long, LiveChunkStatusSnapshot.Record> live, Map<Long, ChunkBucketIndex.Summary> marks) {}
    private record AgingCell(long coordinate, ChunkBucketIndex.Summary summary) {}
    private Key key;
    private Frame frame;
    private Long2ObjectMap<ChunkTileGeometry.Cell> cells;
    private List<AgingCell> agingCells;
    private long createdAt;
    private long validUntil;

    Frame currentFrame() {
        return frame;
    }

    Frame get(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBounds range, long now,
            long columns, long rows, int budget, boolean detailed) {
        ChunkBucketLayout layout = ChunkBucketLayout.forViewport(range, columns, rows, budget);
        boolean dense = detailed && !range.isEmpty() && layout.size() == 1
                && ChunkBucketLayout.fitsViewport(columns + 2, rows + 2, 1, budget);
        return get(
                saved, live, range, now, dense ? new ChunkBucketLayout(range.expand(1), 1) : layout, dense);
    }

    Frame get(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBounds range, long now,
            ChunkBucketLayout layout, boolean dense) {
        Key next = new Key(saved, live, layout, dense);
        if (!next.equals(key)) {
            frame = buildFrame(
                    ChunkBucketIndex.combine(saved.index(), live.index(), layout), range, layout, dense, now);
            key = next;
        } else if (now < createdAt || now >= validUntil) {
            refreshColors(range, now);
        } else {
            return frame;
        }
        createdAt = now;
        return frame;
    }

    private Frame buildFrame(Map<Long, ChunkBucketIndex.Summary> summaries, ChunkBounds range,
            ChunkBucketLayout layout, boolean dense, long now) {
        cells = new Long2ObjectOpenHashMap<>(summaries.size());
        agingCells = new ArrayList<>();
        Map<Long, LiveChunkStatusSnapshot.Record> selectedLive = new Long2ObjectOpenHashMap<>();
        Map<Long, ChunkBucketIndex.Summary> marks = new Long2ObjectOpenHashMap<>();
        validUntil = Long.MAX_VALUE;
        int occupied = dense ? 0 : summaries.size();
        for (var entry : summaries.entrySet()) {
            long coordinate = entry.getKey();
            int x = ChunkBounds.x(coordinate), z = ChunkBounds.z(coordinate);
            var summary = entry.getValue();
            var cell = ChunkMapStyle.mapCell(x, z, summary, now, dense, null);
            if (cell != null)
                cells.put(coordinate, cell);
            if (summary.live() != null)
                selectedLive.put(coordinate, summary.live());
            if (dense && range.contains(x, z))
                occupied++;
            if (ExportStatusMarkers.compact(summary.exports()) != 0 || summary.conflict())
                marks.put(coordinate, summary);
            if (summary.newest() != null
                    && ChunkMapStyle.usesCaptureTimeGradient(summary.newest().updateSource())) {
                agingCells.add(new AgingCell(coordinate, summary));
                validUntil = Math.min(
                        validUntil, ChunkMapStyle.nextFillRefresh(summary.newest(), now, COLOR_REFRESH_MS));
            }
        }
        return new Frame(layout.size(), layout.cellCount(), occupied, dense, ChunkTileGeometry.runs(cells),
                boundaries(range, dense), ChunkRegionIndex.copyCoordinates(selectedLive),
                ChunkRegionIndex.copyCoordinates(marks));
    }

    private void refreshColors(ChunkBounds range, long now) {
        validUntil = Long.MAX_VALUE;
        boolean fillsChanged = false, boundariesChanged = false;
        boolean dense = frame.dense();
        for (var entry : agingCells) {
            var summary = entry.summary();
            long coordinate = entry.coordinate();
            var old = cells.get(coordinate);
            var updated = ChunkMapStyle.mapCell(old.x(), old.z(), summary, now, dense, old);
            if (updated != old) {
                cells.put(coordinate, updated);
                fillsChanged |= updated.color() != old.color();
                boundariesChanged |= updated.border() != old.border();
            }
            validUntil = Math.min(
                    validUntil, ChunkMapStyle.nextFillRefresh(summary.newest(), now, COLOR_REFRESH_MS));
        }
        if (fillsChanged || boundariesChanged) {
            frame = new Frame(frame.bucketSize(), frame.projectedBuckets(), frame.occupiedBuckets(), dense,
                    fillsChanged ? ChunkTileGeometry.runs(cells) : frame.runs(),
                    boundariesChanged ? boundaries(range, dense) : frame.boundaries(), frame.live(),
                    frame.marks());
        }
    }

    private List<ChunkTileGeometry.Boundary> boundaries(ChunkBounds range, boolean dense) {
        var boundaries = ChunkTileGeometry.boundaries(cells, false, false);
        return dense ? boundaries.stream().filter(boundary -> boundary.intersects(range)).toList()
                     : boundaries;
    }
}
