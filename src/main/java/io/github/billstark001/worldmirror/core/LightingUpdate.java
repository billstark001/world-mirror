package io.github.billstark001.worldmirror.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Game-version-neutral representation of one applied chunk lighting update.
 * Version adapters translate Minecraft packet objects into this value before
 * the cache mutates its state.
 */
public record LightingUpdate(
        int minLightSection,
        int lightSectionCount,
        BitSet blockUpdateMask,
        BitSet blockEmptyMask,
        List<byte[]> blockUpdates,
        BitSet skyUpdateMask,
        BitSet skyEmptyMask,
        List<byte[]> skyUpdates) {

    public LightingUpdate {
        blockUpdateMask = (BitSet) blockUpdateMask.clone();
        blockEmptyMask = (BitSet) blockEmptyMask.clone();
        skyUpdateMask = (BitSet) skyUpdateMask.clone();
        skyEmptyMask = (BitSet) skyEmptyMask.clone();
        blockUpdates = copyLayers(blockUpdates);
        skyUpdates = copyLayers(skyUpdates);
    }

    private static List<byte[]> copyLayers(List<byte[]> layers) {
        List<byte[]> copy = new ArrayList<>(layers.size());
        for (byte[] layer : layers) {
            copy.add(layer.clone());
        }
        return List.copyOf(copy);
    }
}
