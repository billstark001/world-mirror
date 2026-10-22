package io.github.billstark001.worldmirror.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Immutable, game-version-neutral representation of one applied chunk lighting update.
 * Version adapters translate Minecraft packet objects into this value before
 * the cache mutates its state. Both layers are validated up front so a malformed
 * sky layer cannot leave an already-applied block layer in the cache.
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

    private static final int LIGHT_ARRAY_BYTES = 2048;

    public LightingUpdate {
        if (lightSectionCount < 0
                || (long) minLightSection + lightSectionCount > (long) Integer.MAX_VALUE + 1L) {
            throw new IllegalArgumentException("Invalid light section range");
        }
        blockUpdateMask = (BitSet) blockUpdateMask.clone();
        blockEmptyMask = (BitSet) blockEmptyMask.clone();
        skyUpdateMask = (BitSet) skyUpdateMask.clone();
        skyEmptyMask = (BitSet) skyEmptyMask.clone();
        blockUpdates = copyLayers(blockUpdates);
        skyUpdates = copyLayers(skyUpdates);
        validateLayer(lightSectionCount, blockUpdateMask, blockEmptyMask, blockUpdates);
        validateLayer(lightSectionCount, skyUpdateMask, skyEmptyMask, skyUpdates);
    }

    @Override
    public BitSet blockUpdateMask() {
        return (BitSet) blockUpdateMask.clone();
    }

    @Override
    public BitSet blockEmptyMask() {
        return (BitSet) blockEmptyMask.clone();
    }

    @Override
    public List<byte[]> blockUpdates() {
        return copyLayers(blockUpdates);
    }

    @Override
    public BitSet skyUpdateMask() {
        return (BitSet) skyUpdateMask.clone();
    }

    @Override
    public BitSet skyEmptyMask() {
        return (BitSet) skyEmptyMask.clone();
    }

    @Override
    public List<byte[]> skyUpdates() {
        return copyLayers(skyUpdates);
    }

    private static void validateLayer(int sectionCount, BitSet updates, BitSet empty, List<byte[]> layers) {
        if (updates.length() > sectionCount || empty.length() > sectionCount) {
            throw new IllegalArgumentException("Light mask selects a section outside its range");
        }
        if (updates.intersects(empty)) {
            throw new IllegalArgumentException("Light section cannot be both updated and empty");
        }
        if (updates.cardinality() != layers.size()) {
            throw new IllegalArgumentException("Light update mask does not match its array count");
        }
        for (byte[] layer : layers) {
            if (layer.length != LIGHT_ARRAY_BYTES) {
                throw new IllegalArgumentException("Light array must contain " + LIGHT_ARRAY_BYTES + " bytes");
            }
        }
    }

    private static List<byte[]> copyLayers(List<byte[]> layers) {
        List<byte[]> copy = new ArrayList<>(layers.size());
        for (byte[] layer : layers) {
            copy.add(layer.clone());
        }
        return List.copyOf(copy);
    }
}
