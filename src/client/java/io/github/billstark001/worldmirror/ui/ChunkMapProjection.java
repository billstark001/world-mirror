package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.util.ChunkRegionIndex;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;

/** Per-view derived geometry; source indices share their complete-bucket summaries. */
public final class ChunkMapProjection {
    private static final long COLOR_REFRESH_MS = 1_000L;
    private record Key(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBucketLayout layout, boolean dense) { }
    record Frame(int bucketSize, long projectedBuckets, int occupiedBuckets, boolean dense,
                 List<ChunkTileGeometry.Run> runs, List<ChunkTileGeometry.Boundary> boundaries,
                 Map<Long, LiveChunkStatusSnapshot.Record> live, Map<Long, ChunkBucketIndex.Summary> marks) { }
    private Key key;
    private Frame frame;
    private Map<Long, ChunkBucketIndex.Summary> summaries;
    private long createdAt;
    private long validUntil;

    Frame currentFrame() { return frame; }

    Frame get(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBounds range, long now,
              long columns, long rows, int budget, boolean detailed) {
        ChunkBucketLayout layout = ChunkBucketLayout.forViewport(range, columns, rows, budget);
        boolean dense = detailed && !range.isEmpty() && layout.size() == 1
                && ChunkBucketLayout.fitsViewport(columns + 2, rows + 2, 1, budget);
        return get(saved, live, range, now, dense ? new ChunkBucketLayout(range.expand(1), 1) : layout, dense);
    }

    Frame get(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBounds range, long now,
              ChunkBucketLayout layout, boolean dense) {
        Key next = new Key(saved, live, layout, dense);
        if (next.equals(key)) {
            if (now >= createdAt && now < validUntil) return frame;
        } else {
            summaries = ChunkBucketIndex.combine(saved.index(), live.index(), layout);
        }
        Map<Long, ChunkTileGeometry.Cell> cells = new Long2ObjectOpenHashMap<>();
        Map<Long, LiveChunkStatusSnapshot.Record> selectedLive = new Long2ObjectOpenHashMap<>();
        long deadline = Long.MAX_VALUE;
        int occupied = dense ? 0 : summaries.size();
        for (var entry : summaries.entrySet()) {
            long coordinate = entry.getKey();
            int x = ChunkBounds.x(coordinate), z = ChunkBounds.z(coordinate);
            var summary = entry.getValue();
            var cell = ChunkMapStyle.mapCell(x, z, summary, now, dense);
            if (cell != null) cells.put(coordinate, cell);
            if (summary.live() != null) selectedLive.put(coordinate, summary.live());
            if (dense && range.contains(x, z)) occupied++;
            deadline = Math.min(deadline, ChunkMapStyle.nextFillRefresh(summary.newest(), now, COLOR_REFRESH_MS));
        }
        List<ChunkTileGeometry.Boundary> boundaries = ChunkTileGeometry.boundaries(cells, false, false);
        if (dense) boundaries = boundaries.stream().filter(boundary -> boundary.intersects(range)).toList();
        Map<Long, ChunkBucketIndex.Summary> marks = new Long2ObjectOpenHashMap<>();
        summaries.forEach((coordinate, summary) -> {
            if (ExportStatusMarkers.compact(summary.exports()) != 0 || summary.conflict()) marks.put(coordinate, summary);
        });
        frame = new Frame(layout.size(), layout.cellCount(), occupied, dense, ChunkTileGeometry.runs(cells), boundaries,
                ChunkRegionIndex.copyCoordinates(selectedLive), ChunkRegionIndex.copyCoordinates(marks));
        key = next;
        createdAt = now;
        validUntil = deadline;
        return frame;
    }

}
