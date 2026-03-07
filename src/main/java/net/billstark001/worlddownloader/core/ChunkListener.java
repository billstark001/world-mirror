package net.billstark001.worlddownloader.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.billstark001.worlddownloader.util.WDLogger;
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
        WDLogger.debug("Captured chunk [" + dimension.getValue() + "] " + pos);
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
}

