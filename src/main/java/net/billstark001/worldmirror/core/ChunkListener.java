package net.billstark001.worldmirror.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;


@Environment(EnvType.CLIENT)
public class ChunkListener {

    /**
     * A single captured chunk: the serialised NBT and the time it was received.
     */
    public record CapturedChunk(
            NbtCompound nbt,
            long capturedAtMs
    ) { }

    // dimension → (chunkPos → capturedChunk)
    private static final ConcurrentHashMap<RegistryKey<World>, ConcurrentHashMap<ChunkPos, CapturedChunk>>
            dimChunks = new ConcurrentHashMap<>();

    public static void addChunkNbt(RegistryKey<World> dimension, ChunkPos pos, NbtCompound chunkNbt) {
        dimChunks.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                 .put(pos, new CapturedChunk(chunkNbt, System.currentTimeMillis()));
        WMLogger.debug("Captured chunk [" + dimension.getValue() + "] " + pos);
    }

    /**
     * Returns an immutable snapshot of all captured chunks, safe to read from any thread.
     * The inner maps are shallow copies — the {@link CapturedChunk} records are immutable.
     */
    public static Map<RegistryKey<World>, Map<ChunkPos, CapturedChunk>> snapshot() {
        Map<RegistryKey<World>, Map<ChunkPos, CapturedChunk>> result = new HashMap<>();
        for (Map.Entry<RegistryKey<World>, ConcurrentHashMap<ChunkPos, CapturedChunk>> dimEntry
                : dimChunks.entrySet()) {
            result.put(dimEntry.getKey(), new HashMap<>(dimEntry.getValue()));
        }
        return result;
    }

    /** All dimension keys that currently have captured chunks. */
    public static Set<RegistryKey<World>> getDimensions() {
        return dimChunks.keySet();
    }

    /** Live map for a single dimension (used for existence checks on the game thread). */
    public static ConcurrentHashMap<ChunkPos, CapturedChunk> getDimension(RegistryKey<World> dim) {
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
                                  RegistryKey<World> playerDimension,
                                  int playerCX, int playerCZ, int maxDistChunks) {
        long now = System.currentTimeMillis();
        int evicted = 0;

        // ── Age-based eviction ────────────────────────────────────────────────
        if (maxAgeMs > 0) {
            for (ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap : dimChunks.values()) {
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
            }
        }

        // ── Distance-based eviction (Chebyshev distance = square boundary) ───────
        if (maxDistChunks > 0 && playerDimension != null) {
            ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimChunks.get(playerDimension);
            if (dimMap != null) {
                List<ChunkPos> toRemove = new ArrayList<>();
                for (ChunkPos pos : dimMap.keySet()) {
                    int dx = pos.x - playerCX;
                    int dz = pos.z - playerCZ;
                    if (Math.abs(dx) > maxDistChunks || Math.abs(dz) > maxDistChunks) {
                        toRemove.add(pos);
                    }
                }
                for (ChunkPos p : toRemove) {
                    dimMap.remove(p);
                    evicted++;
                }
            }
        }

        // ── Count-based eviction (evict oldest first) ─────────────────────────
        if (maxCount > 0) {
            // Collect all entries across all dimensions with their timestamps
            List<Map.Entry<RegistryKey<World>, ChunkPos>> allEntries = new ArrayList<>();
            for (Map.Entry<RegistryKey<World>, ConcurrentHashMap<ChunkPos, CapturedChunk>> dimEntry
                    : dimChunks.entrySet()) {
                for (ChunkPos pos : dimEntry.getValue().keySet()) {
                    allEntries.add(new java.util.AbstractMap.SimpleEntry<>(dimEntry.getKey(), pos));
                }
            }
            int total = allEntries.size();
            if (total > maxCount) {
                // Sort by capture time ascending (oldest first)
                allEntries.sort((a, b) -> {
                    CapturedChunk ca = dimChunks.get(a.getKey()) != null
                            ? dimChunks.get(a.getKey()).get(a.getValue()) : null;
                    CapturedChunk cb = dimChunks.get(b.getKey()) != null
                            ? dimChunks.get(b.getKey()).get(b.getValue()) : null;
                    long ta = (ca != null) ? ca.capturedAtMs() : 0;
                    long tb = (cb != null) ? cb.capturedAtMs() : 0;
                    return Long.compare(ta, tb);
                });
                int toEvict = total - maxCount;
                for (int i = 0; i < toEvict; i++) {
                    Map.Entry<RegistryKey<World>, ChunkPos> e = allEntries.get(i);
                    ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimChunks.get(e.getKey());
                    if (dimMap != null) {
                        dimMap.remove(e.getValue());
                        evicted++;
                    }
                }
            }
        }

        if (evicted > 0) {
            WMLogger.debug("Evicted " + evicted + " stale chunks from cache.");
        }
    }

    /**
     * Removes the specified chunks from the cache (used for invalidate-after-export).
     */
    public static void invalidateChunks(Map<RegistryKey<World>, Set<ChunkPos>> writtenByDim) {
        int removed = 0;
        for (Map.Entry<RegistryKey<World>, Set<ChunkPos>> dimEntry : writtenByDim.entrySet()) {
            ConcurrentHashMap<ChunkPos, CapturedChunk> dimMap = dimChunks.get(dimEntry.getKey());
            if (dimMap == null) continue;
            for (ChunkPos pos : dimEntry.getValue()) {
                if (dimMap.remove(pos) != null) removed++;
            }
        }
        if (removed > 0) {
            WMLogger.debug("Invalidated " + removed + " exported chunks from cache.");
        }
    }
}

