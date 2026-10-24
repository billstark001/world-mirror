package io.github.billstark001.worldmirror.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared two-dimensional topology. World renderers add height, walls, and GPU submission. */
final class ChunkTileGeometry {
    private ChunkTileGeometry() { }
    record Border(int key, int color, int priority) { }
    record Cell(int x, int z, int color, boolean conflict, Border border) {
        static Cell world(int x, int z, int color) { return new Cell(x, z, color, false, new Border(color, color, 0)); }
    }
    record Run(int startBucketX, int endBucketX, int bucketZ, int color, boolean conflict) { }
    record Boundary(boolean vertical, long fixed, long start, long end, int color, boolean interior, boolean outwardPositive) {
        boolean intersects(ChunkBounds bounds) {
            return vertical ? fixed >= bounds.minX() && fixed <= (long) bounds.maxX() + 1 && end > bounds.minZ() && start < (long) bounds.maxZ() + 1
                    : fixed >= bounds.minZ() && fixed <= (long) bounds.maxZ() + 1 && end > bounds.minX() && start < (long) bounds.maxX() + 1;
        }
    }
    record Rectangle(int minX, int maxX, int minZ, int maxZ, int color) { }
    private record Span(int minX, int maxX, int color) { }

    static List<Run> runs(Map<Long, Cell> cells) {
        List<Cell> sorted = cells.values().stream().sorted(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x)).toList();
        List<Run> runs = new ArrayList<>();
        for (Cell cell : sorted) {
            Run previous = runs.isEmpty() ? null : runs.getLast();
            if (previous != null && previous.bucketZ == cell.z && (long) previous.endBucketX + 1 == cell.x
                    && previous.color == cell.color && previous.conflict == cell.conflict) {
                runs.set(runs.size() - 1, new Run(previous.startBucketX, cell.x, cell.z, cell.color, cell.conflict));
            } else runs.add(new Run(cell.x, cell.x, cell.z, cell.color, cell.conflict));
        }
        return List.copyOf(runs);
    }

    static List<Rectangle> rectangles(Map<Long, Cell> cells) {
        List<Rectangle> result = new ArrayList<>();
        Map<Span, Integer> previousBySpan = new HashMap<>();
        for (Run run : runs(cells)) {
            Span span = new Span(run.startBucketX, run.endBucketX, run.color);
            Integer index = previousBySpan.get(span);
            if (index != null) {
                Rectangle previous = result.get(index);
                if ((long) previous.maxZ + 1 == run.bucketZ) {
                    result.set(index, new Rectangle(previous.minX, previous.maxX, previous.minZ, run.bucketZ, run.color));
                    continue;
                }
            }
            previousBySpan.put(span, result.size());
            result.add(new Rectangle(run.startBucketX, run.endBucketX, run.bucketZ, run.bucketZ, run.color));
        }
        return List.copyOf(result);
    }

    /** Map outlines choose a border owner by style; world edges keep interior and facing flags. */
    static List<Boundary> boundaries(Map<Long, Cell> cells, boolean world, boolean innerGrid) {
        List<Boundary> units = new ArrayList<>();
        for (Cell cell : cells.values()) {
            add(units, cells, cell, true, false, world, innerGrid);
            add(units, cells, cell, false, false, world, innerGrid);
            if (neighbor(cells, (long) cell.x + 1, cell.z) == null) add(units, cells, cell, true, true, world, innerGrid);
            if (neighbor(cells, cell.x, (long) cell.z + 1) == null) add(units, cells, cell, false, true, world, innerGrid);
        }
        units.sort(Comparator.comparing(Boundary::vertical).thenComparingLong(Boundary::fixed)
                .thenComparingInt(Boundary::color).thenComparing(Boundary::interior)
                .thenComparing(Boundary::outwardPositive).thenComparingLong(Boundary::start));
        List<Boundary> result = new ArrayList<>();
        for (Boundary edge : units) {
            Boundary previous = result.isEmpty() ? null : result.getLast();
            if (previous != null && previous.vertical == edge.vertical && previous.fixed == edge.fixed && previous.color == edge.color
                    && previous.interior == edge.interior && previous.outwardPositive == edge.outwardPositive && previous.end == edge.start) {
                result.set(result.size() - 1, new Boundary(edge.vertical, edge.fixed, previous.start, edge.end,
                        edge.color, edge.interior, edge.outwardPositive));
            } else result.add(edge);
        }
        return List.copyOf(result);
    }

    private static Cell neighbor(Map<Long, Cell> cells, long x, long z) {
        return x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE
                ? null : cells.get(ChunkBounds.key((int) x, (int) z));
    }

    private static void add(List<Boundary> edges, Map<Long, Cell> cells, Cell cell, boolean vertical,
                            boolean positive, boolean world, boolean innerGrid) {
        Cell other = positive ? null : neighbor(cells, (long) cell.x - (vertical ? 1 : 0), (long) cell.z - (vertical ? 0 : 1));
        boolean interior = other != null && other.color == cell.color;
        int color;
        if (world) {
            if (!innerGrid && interior) return;
            color = cell.color;
        } else {
            if (other != null && other.border.key == cell.border.key) return;
            Cell owner = other != null && other.border.priority >= cell.border.priority ? other : cell;
            // Match the original left/top tie breaker.
            color = owner.border.color;
        }
        long fixed = (vertical ? cell.x : cell.z) + (positive ? 1L : 0L);
        long start = vertical ? cell.z : cell.x;
        edges.add(new Boundary(vertical, fixed, start, start + 1, color, world && interior, world && positive));
    }
}
