package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.DownloadCaptureQueue.Key;
import io.github.billstark001.worldmirror.util.ChunkRegionIndex;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Spatial index used by the map for pending capture and lighting work. */
final class CapturePendingStatus {
    private final Map<ResourceKey<Level>, ChunkRegionIndex<Key>> indexes = new HashMap<>();
    private final Map<ResourceKey<Level>, Long> versions = new HashMap<>();
    private long version;
    private long resetVersion;

    Set<ChunkPos> positions(ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
        var index = indexes.get(dimension);
        if (index == null)
            return Set.of();
        Set<ChunkPos> result = new HashSet<>();
        index.forEachInRange(
                minX, maxX, minZ, maxZ, key -> result.add(new ChunkPos(key.chunkX(), key.chunkZ())));
        return result;
    }

    long version(ResourceKey<Level> dimension) {
        return versions.getOrDefault(dimension, resetVersion);
    }

    void update(Key key, boolean pending) {
        var index = indexes.computeIfAbsent(key.dimension(), ignored -> new ChunkRegionIndex<>());
        boolean changed = pending ? index.put(key.chunkX(), key.chunkZ(), key)
                                  : index.remove(key.chunkX(), key.chunkZ());
        if (changed)
            versions.put(key.dimension(), ++version);
    }

    void clear() {
        indexes.clear();
        versions.clear();
        resetVersion = ++version;
    }
}
