package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.ExportProgress;
import io.github.billstark001.worldmirror.util.ChunkRegionIndex;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local export observations, independent of terrain-cache eviction and retry scheduling.
 * Writers serialize their observations; readers traverse immutable published region pages without
 * waiting for a bulk receipt update. No NBT or per-attempt history is retained.
 */
public final class ExportStatusStore {
    private static final ExportStatusStore GLOBAL = new ExportStatusStore();
    public static ExportStatusStore global() { return GLOBAL; }
    private record Scope(String source, ResourceKey<Level> dimension) { }
    private record Position(Dimension dimension, long chunk) {
        int x() { return (int) (chunk >> 32); }
        int z() { return (int) chunk; }
    }
    private static final class Dimension {
        final Source source;
        final ChunkRegionIndex.Publication<ExportStatus.Chunk> index = new ChunkRegionIndex.Publication<>();
        long nextVersion;
        volatile long version;
        Dimension(Source source) { this.source = source; }
    }
    private static final class Source {
        final String id;
        final ExportStatus.Counter counts = new ExportStatus.Counter();
        final Set<Position> metadataPending = new HashSet<>();
        boolean worldFailure;
        ExportStatus.FailureStage stage = ExportStatus.FailureStage.NONE;
        volatile ExportStatus.Health published;
        Source(String id) { this.id = id; }
    }
    private final Map<Scope, Dimension> dimensions = new ConcurrentHashMap<>();
    private final Map<String, Source> sources = new ConcurrentHashMap<>();
    private final Set<Dimension> changedDimensions = new HashSet<>();
    private final Set<Source> changedSources = new HashSet<>();
    private long version;

    synchronized void queued(ExportWork.Frozen work) {
        String source = work.context().source().id();
        work.terrain().chunks().forEach((key, chunks) -> {
            Dimension dimension = dimension(source, key);
            chunks.forEach((pos, capture) -> {
                var previous = get(dimension, pos);
                put(dimension, previous.withTerrain(capture.revision()));
            });
        });
        work.entities().forEach((key, chunks) -> {
            Dimension dimension = dimension(source, key);
            chunks.forEach((pos, update) -> {
                var previous = get(dimension, pos);
                put(dimension, previous.withParts(previous.terrain(), previous.entities().require(update.revision())));
            });
        });
        publish();
    }

    synchronized void writing(ExportWork.Frozen work, ExportProgress.Part part, ResourceKey<Level> key,
                              Collection<ChunkPos> chunks) {
        Dimension dimension = dimension(work.context().source().id(), key);
        var terrainCaptures = work.terrain().chunks().getOrDefault(key, Map.of());
        var entityCaptures = work.entities().getOrDefault(key, Map.of());
        for (ChunkPos pos : chunks) {
            var previous = get(dimension, pos);
            var terrain = previous.terrain();
            var entities = previous.entities();
            if (part == ExportProgress.Part.TERRAIN) {
                var capture = terrainCaptures.get(pos);
                if (capture != null) terrain = terrain.require(capture.revision()).start(capture.revision());
            } else {
                var capture = entityCaptures.get(pos);
                if (capture != null) entities = entities.require(capture.revision()).start(capture.revision());
            }
            put(dimension, previous.withParts(terrain, entities));
        }
        publish();
    }

    synchronized ExportStatus.Health finish(ExportWork.Frozen work, DownloadExportCoordinator.ExportOutcome outcome) {
        String id = work.context().source().id();
        Source source = sources.computeIfAbsent(id, Source::new);
        long floor = outcome.remaining().durableCaptureTime();
        work.terrain().chunks().forEach((key, chunks) -> {
            Dimension dimension = dimension(id, key);
            var durable = outcome.durableWritten().getOrDefault(key, Map.of());
            var settled = outcome.result().settledWithoutWrite().getOrDefault(key, Map.of());
            chunks.forEach((pos, capture) -> {
                var previous = get(dimension, pos);
                Long written = durable.get(pos);
                var kept = settled.get(pos);
                boolean success = written != null && written == capture.revision()
                        || kept != null && kept.revision() == capture.revision();
                var next = previous.withParts(previous.terrain().require(capture.revision()).finish(capture.revision(), success),
                        previous.entities());
                finishChunk(dimension, next, outcome.metadataCommitted(), floor);
            });
        });
        work.entities().forEach((key, chunks) -> {
            Dimension dimension = dimension(id, key);
            var receipts = outcome.result().entityRevisions().getOrDefault(key, Map.of());
            chunks.forEach((pos, capture) -> {
                var previous = get(dimension, pos);
                Long receipt = receipts.get(pos);
                boolean success = receipt != null && receipt == capture.revision();
                var next = previous.withParts(previous.terrain(),
                        previous.entities().require(capture.revision()).finish(capture.revision(), success));
                finishChunk(dimension, next, outcome.metadataCommitted(), floor);
            });
        });
        if (outcome.metadataCommitted()) {
            var pending = source.metadataPending.iterator();
            while (pending.hasNext()) {
                Position position = pending.next();
                var previous = position.dimension.index.get(position.x(), position.z());
                if (previous.metadataFloor() <= floor) {
                    put(position.dimension, previous.withMetadata(false, 0));
                    pending.remove();
                }
            }
            source.worldFailure = false;
        } else if (!work.hasTerrain() && !work.hasEntities()) source.worldFailure = true;
        if (!outcome.successful()) source.stage = outcome.failureStage();
        if (!source.worldFailure && source.counts.unresolved() == 0) source.stage = ExportStatus.FailureStage.NONE;
        changedSources.add(source);
        publish();
        return health(id);
    }

    private void finishChunk(Dimension dimension, ExportStatus.Chunk chunk, boolean metadataCommitted, long floor) {
        if (!metadataCommitted) {
            chunk = chunk.withMetadata(true, Math.max(floor, chunk.metadataFloor()));
            dimension.source.metadataPending.add(new Position(dimension, key(chunk.x(), chunk.z())));
        }
        put(dimension, chunk);
    }

    /** Idle render frames read published versions without waiting for a bulk receipt update. */
    public long version(String source, ResourceKey<Level> dimension) {
        Dimension current = dimensions.get(new Scope(source, dimension));
        return current == null ? 0 : current.version;
    }
    public List<ExportStatus.Chunk> inRange(String source, ResourceKey<Level> dimension,
                                           int minX, int maxX, int minZ, int maxZ) {
        Dimension current = dimensions.get(new Scope(source, dimension));
        if (current == null) return List.of();
        List<ExportStatus.Chunk> result = new ArrayList<>();
        current.index.forEachInRange(minX, maxX, minZ, maxZ, result::add);
        return List.copyOf(result);
    }
    public ExportStatus.Health health(String source) {
        Source current = sources.get(source);
        ExportStatus.Health health = current == null ? null : current.published;
        return health == null ? new ExportStatus.Health(source, ExportStatus.Counts.EMPTY, false, ExportStatus.FailureStage.NONE) : health;
    }
    private Dimension dimension(String id, ResourceKey<Level> key) {
        return dimensions.computeIfAbsent(new Scope(id, key), ignored ->
                new Dimension(sources.computeIfAbsent(id, Source::new)));
    }
    private static ExportStatus.Chunk get(Dimension dimension, ChunkPos pos) {
        int x = pos.getRegionX() * 32 + pos.getRegionLocalX(), z = pos.getRegionZ() * 32 + pos.getRegionLocalZ();
        ExportStatus.Chunk record = dimension.index.get(x, z);
        return record == null ? new ExportStatus.Chunk(x, z, ExportStatus.Part.NONE, ExportStatus.Part.NONE, false, 0) : record;
    }
    private void put(Dimension dimension, ExportStatus.Chunk record) {
        ExportStatus.Chunk previous = dimension.index.get(record.x(), record.z());
        if (record.equals(previous)) return;
        dimension.source.counts.replace(previous, record);
        dimension.index.put(record.x(), record.z(), record);
        dimension.nextVersion = ++version;
        changedDimensions.add(dimension);
        changedSources.add(dimension.source);
    }
    private void publish() {
        changedDimensions.forEach(dimension -> {
            dimension.index.publish();
            dimension.version = dimension.nextVersion;
        });
        changedSources.forEach(source -> {
            var next = new ExportStatus.Health(source.id, source.counts.snapshot(), source.worldFailure, source.stage);
            if (!next.equals(source.published)) source.published = next;
        });
        changedDimensions.clear();
        changedSources.clear();
    }
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
}
