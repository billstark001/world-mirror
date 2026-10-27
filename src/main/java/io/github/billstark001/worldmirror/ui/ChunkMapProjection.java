package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.util.ChunkRegionIndex;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;

/** Per-view derived geometry; source indices share their complete-bucket summaries. */
public final class ChunkMapProjection {
    private static final long COLOR_REFRESH_MS = 1_000L;
    private record Key(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBucketLayout layout, boolean dense) { }
    record Frame(int bucketSize, List<ChunkTileGeometry.Run> runs, List<ChunkTileGeometry.Boundary> boundaries,
                 Map<Long, LiveChunkStatusSnapshot.Record> live, Map<Long, ChunkBucketIndex.Summary> marks) { }
    private Key key;
    private Frame frame;
    private long createdAt;
    private long validUntil;

    Frame get(ChunkStatusSnapshot saved, LiveChunkStatusSnapshot live, ChunkBounds range, long now, int budget, boolean dense) {
        ChunkBucketLayout layout = dense ? new ChunkBucketLayout(range.expand(1), 1) : ChunkBucketLayout.withinBudget(range, budget);
        Key next = new Key(saved, live, layout, dense);
        if (next.equals(key) && now >= createdAt && now < validUntil) return frame;
        Map<Long, ChunkBucketIndex.Summary> summaries = ChunkBucketIndex.combine(saved.index(), live.index(), layout);
        Map<Long, ChunkTileGeometry.Cell> cells = new Long2ObjectOpenHashMap<>();
        Map<Long, LiveChunkStatusSnapshot.Record> selectedLive = new Long2ObjectOpenHashMap<>();
        long deadline = dense ? Long.MAX_VALUE : now + COLOR_REFRESH_MS;
        for (var entry : summaries.entrySet()) {
            long coordinate = entry.getKey();
            var summary = entry.getValue();
            var cell = ChunkMapStyle.mapCell(ChunkBounds.x(coordinate), ChunkBounds.z(coordinate), summary, now, dense);
            if (cell != null) cells.put(coordinate, cell);
            if (summary.live() != null) selectedLive.put(coordinate, summary.live());
            if (dense) deadline = Math.min(deadline, ChunkMapStyle.nextBoundaryChange(summary.newest(), now));
        }
        List<ChunkTileGeometry.Boundary> boundaries = ChunkTileGeometry.boundaries(cells, false, false);
        if (dense) boundaries = boundaries.stream().filter(boundary -> boundary.intersects(range)).toList();
        Map<Long, ChunkBucketIndex.Summary> marks = new Long2ObjectOpenHashMap<>();
        summaries.forEach((coordinate, summary) -> {
            if (ExportStatusMarkers.compact(summary.exports()) != 0 || summary.conflict()) marks.put(coordinate, summary);
        });
        frame = new Frame(layout.size(), ChunkTileGeometry.runs(cells), boundaries, ChunkRegionIndex.copyCoordinates(selectedLive), ChunkRegionIndex.copyCoordinates(marks));
        key = next;
        createdAt = now;
        validUntil = deadline;
        return frame;
    }

}
