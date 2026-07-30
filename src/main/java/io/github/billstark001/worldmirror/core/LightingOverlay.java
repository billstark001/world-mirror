package io.github.billstark001.worldmirror.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.BitSet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * The latest client-known light data for a chunk, kept independently from the
 * chunk's base NBT.  An absent entry means that the client did not have that
 * section; an empty entry means that the packet explicitly set it to zero.
 */
final class LightingOverlay {
    private static final String SECTIONS_KEY = "sections";
    private static final String BLOCK_LIGHT_KEY = "BlockLight";
    private static final String SKY_LIGHT_KEY = "SkyLight";
    private static final int LIGHT_ARRAY_SIZE = 2048;

    private final Map<Integer, LayerValue> blockLight = new HashMap<>();
    private final Map<Integer, LayerValue> skyLight = new HashMap<>();

    /**
     * Moves all known light arrays out of a freshly captured chunk NBT and
     * into this overlay.  Tags which are absent remain unknown and therefore
     * cannot erase an older known value already stored here.
     */
    synchronized void absorbAndStrip(CompoundTag chunkNbt) {
        forEachSection(chunkNbt, section -> {
            int y = section.getByteOr("Y", (byte) 0);
            section.getByteArray(BLOCK_LIGHT_KEY).ifPresent(data -> {
                blockLight.put(y, LayerValue.data(data));
                section.remove(BLOCK_LIGHT_KEY);
            });
            section.getByteArray(SKY_LIGHT_KEY).ifPresent(data -> {
                skyLight.put(y, LayerValue.data(data));
                section.remove(SKY_LIGHT_KEY);
            });
        });
    }

    /** Records the exact section updates from a packet after vanilla applied it. */
    synchronized void applyUpdate(LightingUpdate update) {
        applyLayer(update.minLightSection(), update.lightSectionCount(),
                update.blockUpdateMask(), update.blockEmptyMask(),
                update.blockUpdates(), blockLight);
        applyLayer(update.minLightSection(), update.lightSectionCount(),
                update.skyUpdateMask(), update.skyEmptyMask(),
                update.skyUpdates(), skyLight);
    }

    /** Produces an export-safe chunk NBT without mutating the cached base. */
    synchronized CompoundTag materialize(CompoundTag baseChunkNbt) {
        CompoundTag result = baseChunkNbt.copy();
        ListTag sections = result.getList(SECTIONS_KEY).orElseGet(() -> {
            ListTag created = new ListTag();
            result.put(SECTIONS_KEY, created);
            return created;
        });

        Map<Integer, CompoundTag> byY = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            sections.getCompound(i).ifPresent(section ->
                    byY.put((int) section.getByteOr("Y", (byte) 0), section));
        }

        applyToSections(sections, byY, blockLight, BLOCK_LIGHT_KEY);
        applyToSections(sections, byY, skyLight, SKY_LIGHT_KEY);
        return result;
    }

    private static void applyLayer(
            int minLightSection,
            int lightSectionCount,
            BitSet updateMask,
            BitSet emptyMask,
            java.util.List<byte[]> updates,
            Map<Integer, LayerValue> target) {
        Iterator<byte[]> updateIterator = updates.iterator();
        for (int index = 0; index < lightSectionCount; index++) {
            if (updateMask.get(index)) {
                if (!updateIterator.hasNext()) {
                    throw new IllegalArgumentException("Light packet update mask has too few arrays");
                }
                target.put(minLightSection + index, LayerValue.data(updateIterator.next()));
            } else if (emptyMask.get(index)) {
                target.put(minLightSection + index, LayerValue.zero());
            }
        }
        if (updateIterator.hasNext()) {
            throw new IllegalArgumentException("Light packet has arrays not selected by its update mask");
        }
    }

    private static void applyToSections(
            ListTag sections,
            Map<Integer, CompoundTag> byY,
            Map<Integer, LayerValue> source,
            String key) {
        for (Map.Entry<Integer, LayerValue> entry : source.entrySet()) {
            int y = entry.getKey();
            CompoundTag section = byY.get(y);
            if (section == null) {
                section = new CompoundTag();
                section.putByte("Y", (byte) y);
                sections.add(section);
                byY.put(y, section);
            }
            section.putByteArray(key, entry.getValue().bytes());
        }
    }

    private static void forEachSection(CompoundTag chunkNbt, java.util.function.Consumer<CompoundTag> action) {
        ListTag sections = chunkNbt.getListOrEmpty(SECTIONS_KEY);
        for (int i = 0; i < sections.size(); i++) {
            sections.getCompound(i).ifPresent(action);
        }
    }

    private record LayerValue(byte[] data, boolean empty) {
        static LayerValue zero() {
            return new LayerValue(null, true);
        }

        static LayerValue data(byte[] data) {
            return new LayerValue(data.clone(), false);
        }

        byte[] bytes() {
            return empty ? new byte[LIGHT_ARRAY_SIZE] : data.clone();
        }
    }
}
