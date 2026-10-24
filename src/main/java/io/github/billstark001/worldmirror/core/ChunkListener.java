package io.github.billstark001.worldmirror.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.billstark001.worldmirror.io.BlockEntityNbtSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;


@Environment(EnvType.CLIENT)
public class ChunkListener {

    // Capture, lighting, eviction, acknowledgements, and clear all run on the
    // game thread. Export workers only materialize independent DirtySnapshots.

    /**
     * A captured chunk and its process-local revision. The cache owns this NBT;
     * callers must materialize an export copy before mutating it.
     */
    public record CapturedChunk(
            CompoundTag nbt,
            long capturedAtMs,
            long revision,
            boolean authoritativeEmpty
    ) {
        public CapturedChunk(CompoundTag nbt, long capturedAtMs, long revision) {
            this(nbt, capturedAtMs, revision, false);
        }
    }

    /**
     * Immutable export view of dirty chunk references and frozen lighting maps.
     * Later light packets, recaptures, or a source transition cannot change the
     * lighting paired with an export's terrain revision. Chunk NBT is copied
     * only when the background exporter materializes that chunk.
     */
    public static final class DirtySnapshot {
        private final Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> chunks;
        private final Map<ResourceKey<Level>, Map<ChunkPos, LightingOverlay>> lighting;

        private DirtySnapshot(
                Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> chunks,
                Map<ResourceKey<Level>, Map<ChunkPos, LightingOverlay>> lighting) {
            this.chunks = chunks;
            this.lighting = lighting;
        }

        public Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> chunks() {
            return chunks;
        }

        /** Releases durable revisions while retaining the remaining chunks' frozen lighting. */
        public DirtySnapshot withoutAcknowledged(
                Map<ResourceKey<Level>, Map<ChunkPos, Long>> acknowledged) {
            Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> remaining = new HashMap<>();
            Map<ResourceKey<Level>, Map<ChunkPos, LightingOverlay>> remainingLighting = new HashMap<>();
            chunks.forEach((dimension, captured) -> {
                Map<ChunkPos, Long> receipts = acknowledged.getOrDefault(dimension, Map.of());
                Map<ChunkPos, CapturedChunk> kept = new HashMap<>();
                Map<ChunkPos, LightingOverlay> keptLighting = new HashMap<>();
                captured.forEach((pos, chunk) -> {
                    if (Long.valueOf(chunk.revision()).equals(receipts.get(pos))) return;
                    kept.put(pos, chunk);
                    LightingOverlay overlay = lighting.getOrDefault(dimension, Map.of()).get(pos);
                    if (overlay != null) keptLighting.put(pos, overlay);
                });
                if (!kept.isEmpty()) remaining.put(dimension, Map.copyOf(kept));
                if (!keptLighting.isEmpty()) remainingLighting.put(dimension, Map.copyOf(keptLighting));
            });
            return new DirtySnapshot(Map.copyOf(remaining), Map.copyOf(remainingLighting));
        }

        public CapturedChunk materialize(
                ResourceKey<Level> dimension, ChunkPos pos, CapturedChunk captured) {
            Map<ChunkPos, LightingOverlay> lightingByChunk = lighting.get(dimension);
            LightingOverlay overlay = lightingByChunk == null ? null : lightingByChunk.get(pos);
            CompoundTag nbt = overlay == null
                    ? captured.nbt().copy()
                    : overlay.materialize(captured.nbt());
            return new CapturedChunk(nbt, captured.capturedAtMs(), captured.revision(), captured.authoritativeEmpty());
        }
    }

    // dimension → (chunkPos → capturedChunk)
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, CapturedChunk>>
            dimChunks = new ConcurrentHashMap<>();
    // dimension → (chunkPos → latest known light sections).  The base chunk NBT
    // intentionally has its light tags stripped so missing client data cannot
    // replace older known data during a later full capture.
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, LightingOverlay>>
            dimLighting = new ConcurrentHashMap<>();
    // Highest in-memory revision known to have reached both the MCA file and
    // the SQLite durability index. Revisions increase across source resets but
    // are never persisted; capturedAtMs orders cross-session/database updates.
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, Long>>
            dimDurableRevisions = new ConcurrentHashMap<>();
    private static final AtomicLong nextRevision = new AtomicLong();
    private static final AtomicLong nextCaptureTimestamp = new AtomicLong();
    private static final AtomicInteger dirtyCount = new AtomicInteger();

    /** Takes ownership of fresh chunk NBT on the game thread and assigns a new dirty revision. */
    public static void addChunkNbt(ResourceKey<Level> dimension, ChunkPos pos, CompoundTag chunkNbt) {
        addChunkNbt(dimension, pos, chunkNbt, false);
    }

    /**
     * An authoritative empty capture follows a block-change packet and has no
     * non-air blocks or block entities. Missing old block entities must not be
     * restored into that confirmed empty terrain.
     */
    public static void addChunkNbt(ResourceKey<Level> dimension, ChunkPos pos, CompoundTag chunkNbt,
                                    boolean authoritativeEmpty) {
        ConcurrentHashMap<ChunkPos, LightingOverlay> lightingByChunk =
                dimLighting.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        dimChunks.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                 .compute(pos, (ignored, previous) -> {
                     boolean becameDirty = previous == null || isClean(dimension, pos, previous);
                     CompoundTag mergedNbt = chunkNbt;
                     if (previous != null && !authoritativeEmpty) {
                         BlockEntityNbtSupport.mergeChunkBlockEntities(mergedNbt, previous.nbt());
                     }
                     lightingByChunk.computeIfAbsent(pos, ignoredKey -> new LightingOverlay())
                             .absorbAndStrip(mergedNbt);
                     CapturedChunk captured = new CapturedChunk(mergedNbt,
                             nextTimestamp(previous), nextRevision.incrementAndGet(), authoritativeEmpty);
                     if (becameDirty) dirtyCount.incrementAndGet();
                     return captured;
                 });
    }

    /**
     * Records a light-only packet against an existing base chunk.  Returns
     * {@code false} when no base chunk is cached and the caller should queue a
     * normal chunk capture instead. Assigns a revision immediately so an older
     * export cannot invalidate light waiting in the export coalescing window.
     */
    public static boolean applyLightUpdate(
            ResourceKey<Level> dimension,
            ChunkPos pos,
            LightingUpdate update) {
        ConcurrentHashMap<ChunkPos, CapturedChunk> chunks = dimChunks.get(dimension);
        if (chunks == null) return false;
        AtomicBoolean applied = new AtomicBoolean();
        chunks.computeIfPresent(pos, (ignored, previous) -> {
            dimLighting.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>())
                    .computeIfAbsent(pos, key -> new LightingOverlay()).applyUpdate(update);
            CapturedChunk captured = new CapturedChunk(previous.nbt(), nextTimestamp(previous),
                    nextRevision.incrementAndGet(), previous.authoritativeEmpty());
            if (isClean(dimension, pos, previous)) dirtyCount.incrementAndGet();
            applied.set(true);
            return captured;
        });
        return applied.get();
    }

    /** Marks a chunk dirty after its light-update coalescing window has elapsed. */
    public static void markChunkDirty(ResourceKey<Level> dimension, ChunkPos pos) {
        ConcurrentHashMap<ChunkPos, CapturedChunk> chunks = dimChunks.get(dimension);
        if (chunks == null) return;
        chunks.computeIfPresent(pos, (ignored, previous) -> {
            if (isClean(dimension, pos, previous)) dirtyCount.incrementAndGet();
            return new CapturedChunk(previous.nbt(), nextTimestamp(previous),
                    nextRevision.incrementAndGet(), previous.authoritativeEmpty());
        });
    }

    /**
     * Returns immutable maps containing only dirty captured-chunk references.
     * NBT remains un-copied here and is materialized one chunk at a time by the
     * background exporter.
     */
    public static Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> snapshotDirtyReferences() {
        return snapshotDirtyState().chunks();
    }

    /** Captures dirty references and freezes their session-local lighting overlays. */
    public static DirtySnapshot snapshotDirtyState() {
        Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> result = new HashMap<>();
        Map<ResourceKey<Level>, Map<ChunkPos, LightingOverlay>> lighting = new HashMap<>();
        for (Map.Entry<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, CapturedChunk>> dimEntry
                : dimChunks.entrySet()) {
            Map<ChunkPos, CapturedChunk> dirty = new HashMap<>();
            Map<ChunkPos, LightingOverlay> dimensionLighting = new HashMap<>();
            Map<ChunkPos, LightingOverlay> liveLighting = dimLighting.get(dimEntry.getKey());
            for (Map.Entry<ChunkPos, CapturedChunk> entry : dimEntry.getValue().entrySet()) {
                if (!isClean(dimEntry.getKey(), entry.getKey(), entry.getValue())) {
                    dirty.put(entry.getKey(), entry.getValue());
                    if (liveLighting != null) {
                        LightingOverlay overlay = liveLighting.get(entry.getKey());
                        if (overlay != null) dimensionLighting.put(entry.getKey(), overlay.snapshot());
                    }
                }
            }
            if (!dirty.isEmpty()) {
                result.put(dimEntry.getKey(), Map.copyOf(dirty));
                if (!dimensionLighting.isEmpty()) {
                    lighting.put(dimEntry.getKey(), Map.copyOf(dimensionLighting));
                }
            }
        }
        return new DirtySnapshot(Map.copyOf(result), Map.copyOf(lighting));
    }

    /** Wraps an already independent chunk map, such as a one-shot nearby export. */
    public static DirtySnapshot snapshotOf(
            Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> chunks) {
        Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> immutable = new HashMap<>();
        chunks.forEach((dimension, dimensionChunks) ->
                immutable.put(dimension, Map.copyOf(dimensionChunks)));
        return new DirtySnapshot(Map.copyOf(immutable), Map.of());
    }

    /**
     * Materializes one immutable write snapshot on demand.  This is deliberately
     * per-chunk so a background export never deep-copies the whole cache at once.
     */
    public static CapturedChunk materialize(
            ResourceKey<Level> dimension, ChunkPos pos, CapturedChunk captured) {
        Map<ChunkPos, LightingOverlay> lightingByChunk = dimLighting.get(dimension);
        LightingOverlay lighting = lightingByChunk == null ? null : lightingByChunk.get(pos);
        CompoundTag nbt = lighting == null
                ? captured.nbt().copy()
                : lighting.materialize(captured.nbt());
        return new CapturedChunk(nbt, captured.capturedAtMs(), captured.revision(), captured.authoritativeEmpty());
    }

    /** Read-only live map for game-thread queries; use DirtySnapshot for background exports. */
    public static Map<ChunkPos, CapturedChunk> getDimension(ResourceKey<Level> dim) {
        Map<ChunkPos, CapturedChunk> chunks = dimChunks.get(dim);
        return chunks == null ? Map.of() : Collections.unmodifiableMap(chunks);
    }

    /** Lightweight viewport state for maps; serialized chunk NBT never leaves the cache. */
    public record MapChunkStatus(int x, int z, long capturedAtMs, boolean pendingWrite) { }

    public static List<MapChunkStatus> snapshotMapStatusInRange(
            ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
        Map<ChunkPos, CapturedChunk> chunks = getDimension(dimension);
        Map<ChunkPos, Long> durable = dimDurableRevisions.get(dimension);
        List<MapChunkStatus> result = new ArrayList<>();
        for (Map.Entry<ChunkPos, CapturedChunk> entry : chunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            int x = pos.getMinBlockX() >> 4;
            int z = pos.getMinBlockZ() >> 4;
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            CapturedChunk chunk = entry.getValue();
            long durableRevision = durable == null ? 0L : durable.getOrDefault(pos, 0L);
            result.add(new MapChunkStatus(x, z, chunk.capturedAtMs(),
                    chunk.revision() > durableRevision));
        }
        return result;
    }

    /** Total captured chunks across all dimensions. */
    public static int getTotalCount() {
        return dimChunks.values().stream().mapToInt(Map::size).sum();
    }

    public static boolean isEmpty() {
        if (dimChunks.isEmpty()) return true;
        return dimChunks.values().stream().allMatch(Map::isEmpty);
    }

    public static void clear() {
        dimChunks.clear();
        dimLighting.clear();
        dimDurableRevisions.clear();
        dirtyCount.set(0);
    }

    /** Number of cache entries newer than their last durable acknowledgement. */
    public static int getDirtyCount() {
        return dirtyCount.get();
    }

    /**
     * Acknowledges exact revisions on the game thread after durable MCA and database commits.
     * A newer revision that arrived while the writer was running is retained.
     */
    public static Map<ResourceKey<Level>, Set<ChunkPos>> acknowledge(
            Map<ResourceKey<Level>, ? extends Map<ChunkPos, Long>> revisionsByDim,
            boolean invalidateCleanEntries) {
        int invalidated = 0;
        Map<ResourceKey<Level>, Set<ChunkPos>> invalidatedByDim = new HashMap<>();
        for (Map.Entry<ResourceKey<Level>, ? extends Map<ChunkPos, Long>> dimEntry
                : revisionsByDim.entrySet()) {
            ResourceKey<Level> dimension = dimEntry.getKey();
            ConcurrentHashMap<ChunkPos, CapturedChunk> chunks = dimChunks.get(dimension);
            if (chunks == null) continue;
            ConcurrentHashMap<ChunkPos, Long> durable =
                    dimDurableRevisions.computeIfAbsent(dimension, ignored -> new ConcurrentHashMap<>());
            for (Map.Entry<ChunkPos, Long> entry : dimEntry.getValue().entrySet()) {
                ChunkPos pos = entry.getKey();
                long revision = entry.getValue();
                CapturedChunk current = chunks.get(pos);
                if (current == null) continue;
                long previousDurable = durable.getOrDefault(pos, 0L);
                long acknowledgedRevision = durable.merge(pos, revision, Math::max);
                if (current != null
                        && previousDurable < current.revision()
                        && acknowledgedRevision >= current.revision()) {
                    decrementDirtyCount();
                }
                if (!invalidateCleanEntries) continue;

                current = chunks.get(pos);
                if (current != null && current.revision() <= revision && chunks.remove(pos, current)) {
                    removeLighting(dimension, pos);
                    durable.remove(pos);
                    invalidated++;
                    invalidatedByDim.computeIfAbsent(dimension,
                            ignored -> ConcurrentHashMap.newKeySet()).add(pos);
                }
            }
        }
        if (invalidated > 0) {
            WMLogger.debug("Invalidated " + invalidated + " durably exported chunks from cache.");
        }
        return invalidatedByDim;
    }

    /**
     * Evicts stale chunks from the cache according to the supplied policy parameters.
     *
     * @param maxAgeMs     evict chunks older than this many milliseconds; 0 = disabled
     * @param maxCount     keep at most this many chunks total (oldest evicted first); 0 = disabled
     * @param playerDimension  player's current dimension (may be {@code null})
     * @param playerCX     player chunk X (used only when {@code maxDistChunks > 0})
     * @param playerCZ     player chunk Z (used only when {@code maxDistChunks > 0})
     * @param maxDistChunks evict chunks farther than this radius; 0 = disabled
     */
    public static void evictStale(long maxAgeMs, int maxCount,
                                  ResourceKey<Level> playerDimension,
                                  int playerCX, int playerCZ, int maxDistChunks) {
        long now = System.currentTimeMillis();
        int evicted = 0;
        Map<ResourceKey<Level>, Set<ChunkPos>> evictedByDim = new HashMap<>();

        // ── Age-based eviction ────────────────────────────────────────────────
        if (maxAgeMs > 0) {
            for (Map.Entry<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, CapturedChunk>> dimEntry
                    : dimChunks.entrySet()) {
                ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimEntry.getValue();
                List<ChunkPos> toRemove = new ArrayList<>();
                for (Map.Entry<ChunkPos, CapturedChunk> e : dimMap.entrySet()) {
                    if (now - e.getValue().capturedAtMs() > maxAgeMs
                            && isClean(dimEntry.getKey(), e.getKey(), e.getValue())) {
                        toRemove.add(e.getKey());
                    }
                }
                for (ChunkPos p : toRemove) {
                    if (removeChunk(dimEntry.getKey(), p)) evicted++;
                }
                if (!toRemove.isEmpty()) {
                    evictedByDim.computeIfAbsent(dimEntry.getKey(), k -> ConcurrentHashMap.newKeySet())
                            .addAll(toRemove);
                }
            }
        }

        // ── Distance-based eviction (Chebyshev distance = square boundary) ───────
        if (maxDistChunks > 0 && playerDimension != null) {
            ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimChunks.get(playerDimension);
            if (dimMap != null) {
                List<ChunkPos> toRemove = new ArrayList<>();
                for (ChunkPos pos : dimMap.keySet()) {
                    int dx = (pos.getMinBlockX() >> 4) - playerCX;
                    int dz = (pos.getMinBlockZ() >> 4) - playerCZ;
                    CapturedChunk captured = dimMap.get(pos);
                    if ((Math.abs(dx) > maxDistChunks || Math.abs(dz) > maxDistChunks)
                            && captured != null && isClean(playerDimension, pos, captured)) {
                        toRemove.add(pos);
                    }
                }
                for (ChunkPos p : toRemove) {
                    if (removeChunk(playerDimension, p)) evicted++;
                }
                if (!toRemove.isEmpty()) {
                    evictedByDim.computeIfAbsent(playerDimension, k -> ConcurrentHashMap.newKeySet())
                            .addAll(toRemove);
                }
            }
        }

        // ── Count-based eviction (evict oldest first) ─────────────────────────
        if (maxCount > 0) {
            record Entry(ResourceKey<Level> dim, ChunkPos pos, long ts) {}
            List<Entry> allEntries = new ArrayList<>();
            for (Map.Entry<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, CapturedChunk>> dimEntry
                    : dimChunks.entrySet()) {
                for (Map.Entry<ChunkPos, CapturedChunk> e : dimEntry.getValue().entrySet()) {
                    if (isClean(dimEntry.getKey(), e.getKey(), e.getValue())) {
                        allEntries.add(new Entry(dimEntry.getKey(), e.getKey(), e.getValue().capturedAtMs()));
                    }
                }
            }
            int total = getTotalCount();
            if (total > maxCount) {
                allEntries.sort(java.util.Comparator.comparingLong(Entry::ts));
                int toEvict = Math.min(total - maxCount, allEntries.size());
                for (int i = 0; i < toEvict; i++) {
                    Entry e = allEntries.get(i);
                    ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimChunks.get(e.dim());
                    if (dimMap != null && removeChunk(e.dim(), e.pos())) {
                        evicted++;
                        evictedByDim.computeIfAbsent(e.dim(), k -> ConcurrentHashMap.newKeySet())
                                .add(e.pos());
                    }
                }
            }
        }

        if (evicted > 0) {
            WMLogger.debug("Evicted " + evicted + " stale chunks from cache.");
            ContainerTracker.evictForChunks(evictedByDim);
        }
    }

    private static boolean removeChunk(ResourceKey<Level> dimension, ChunkPos pos) {
        ConcurrentHashMap<ChunkPos, CapturedChunk> chunks = dimChunks.get(dimension);
        if (chunks == null || chunks.remove(pos) == null) return false;
        removeLighting(dimension, pos);
        ConcurrentHashMap<ChunkPos, Long> durable = dimDurableRevisions.get(dimension);
        if (durable != null) durable.remove(pos);
        return true;
    }

    private static void removeLighting(ResourceKey<Level> dimension, ChunkPos pos) {
        ConcurrentHashMap<ChunkPos, LightingOverlay> lighting = dimLighting.get(dimension);
        if (lighting != null) {
            lighting.remove(pos);
            if (lighting.isEmpty()) {
                dimLighting.remove(dimension, lighting);
            }
        }
    }

    private static boolean isClean(
            ResourceKey<Level> dimension, ChunkPos pos, CapturedChunk captured) {
        Map<ChunkPos, Long> durable = dimDurableRevisions.get(dimension);
        return durable != null && durable.getOrDefault(pos, 0L) >= captured.revision();
    }

    private static void decrementDirtyCount() {
        dirtyCount.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static long nextTimestamp(CapturedChunk previous) {
        long now = System.currentTimeMillis();
        long previousMinimum = previous == null ? 0L : previous.capturedAtMs() + 1L;
        return nextCaptureTimestamp.updateAndGet(last ->
                Math.max(Math.max(now, previousMinimum), last + 1L));
    }
}

