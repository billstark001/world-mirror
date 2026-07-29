package net.billstark001.worldmirror.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.billstark001.worldmirror.io.BlockEntityNbtSupport;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;


@Environment(EnvType.CLIENT)
public class ChunkListener {

    /**
     * A single captured chunk: the serialised NBT and the time it was received.
     */
    public record CapturedChunk(
            CompoundTag nbt,
            long capturedAtMs
    ) { }

    // dimension → (chunkPos → capturedChunk)
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, CapturedChunk>>
            dimChunks = new ConcurrentHashMap<>();

    public static void addChunkNbt(ResourceKey<Level> dimension, ChunkPos pos, CompoundTag chunkNbt) {
        dimChunks.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                 .compute(pos, (ignored, previous) -> {
                     CompoundTag mergedNbt = chunkNbt;
                     if (previous != null) {
                         BlockEntityNbtSupport.mergeChunkBlockEntities(mergedNbt, previous.nbt());
                     }
                     return new CapturedChunk(mergedNbt, System.currentTimeMillis());
                 });
        WMLogger.debug("Captured chunk [" + dimension.identifier() + "] " + pos);
    }

    /**
     * Returns an immutable snapshot of all captured chunks, safe to read from any thread.
     * The inner maps are shallow copies — the {@link CapturedChunk} records are immutable.
     */
    public static Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> snapshot() {
        Map<ResourceKey<Level>, Map<ChunkPos, CapturedChunk>> result = new HashMap<>();
        for (Map.Entry<ResourceKey<Level>, ConcurrentHashMap<ChunkPos, CapturedChunk>> dimEntry
                : dimChunks.entrySet()) {
            result.put(dimEntry.getKey(), new HashMap<>(dimEntry.getValue()));
        }
        return result;
    }

    /** All dimension keys that currently have captured chunks. */
    public static Set<ResourceKey<Level>> getDimensions() {
        return dimChunks.keySet();
    }

    /** Live map for a single dimension (used for existence checks on the game thread). */
    public static ConcurrentHashMap<ChunkPos, CapturedChunk> getDimension(ResourceKey<Level> dim) {
        return dimChunks.getOrDefault(dim, new ConcurrentHashMap<>());
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
                    if (now - e.getValue().capturedAtMs() > maxAgeMs) {
                        toRemove.add(e.getKey());
                    }
                }
                for (ChunkPos p : toRemove) {
                    dimMap.remove(p);
                    evicted++;
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
                    int dx = pos.x() - playerCX;
                    int dz = pos.z() - playerCZ;
                    if (Math.abs(dx) > maxDistChunks || Math.abs(dz) > maxDistChunks) {
                        toRemove.add(pos);
                    }
                }
                for (ChunkPos p : toRemove) {
                    dimMap.remove(p);
                    evicted++;
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
                    allEntries.add(new Entry(dimEntry.getKey(), e.getKey(), e.getValue().capturedAtMs()));
                }
            }
            int total = allEntries.size();
            if (total > maxCount) {
                allEntries.sort(java.util.Comparator.comparingLong(Entry::ts));
                int toEvict = total - maxCount;
                for (int i = 0; i < toEvict; i++) {
                    Entry e = allEntries.get(i);
                    ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimChunks.get(e.dim());
                    if (dimMap != null) {
                        dimMap.remove(e.pos());
                        evicted++;
                        evictedByDim.computeIfAbsent(e.dim(), k -> ConcurrentHashMap.newKeySet())
                                .add(e.pos());
                    }
                }
            }
        }

        if (evicted > 0) {
            WMLogger.debug("Evicted " + evicted + " stale chunks from cache.");
            EntityTracker.pruneToMatchCapturedChunks();
        }
    }

    /**
     * Removes the specified chunks from the cache (used for invalidate-after-export).
     */
    public static void invalidateChunks(Map<ResourceKey<Level>, Set<ChunkPos>> writtenByDim) {
        int removed = 0;
        for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> dimEntry : writtenByDim.entrySet()) {
            ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimChunks.get(dimEntry.getKey());
            if (dimMap == null) continue;
            for (ChunkPos pos : dimEntry.getValue()) {
                if (dimMap.remove(pos) != null) removed++;
            }
        }
        if (removed > 0) {
            WMLogger.debug("Invalidated " + removed + " exported chunks from cache.");
            ContainerTracker.evictForChunks(writtenByDim);
            EntityTracker.pruneToMatchCapturedChunks();
        }
    }
}

