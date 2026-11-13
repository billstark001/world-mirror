package io.github.billstark001.worldmirror.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** The latest client-known light data for a chunk, kept independently from its base NBT. */
final class LightingOverlay {
    private static final String SECTIONS_KEY = "sections";
    private static final String BLOCK_LIGHT_KEY = "BlockLight";
    private static final String SKY_LIGHT_KEY = "SkyLight";
    private static final int LIGHT_ARRAY_SIZE = 2048;
    private static final Snapshot EMPTY = new Snapshot(Int2ObjectMaps.emptyMap(), Int2ObjectMaps.emptyMap());

    // Null values represent explicit zero light. Missing keys represent unknown sections.
    private final Int2ObjectOpenHashMap<byte[]> blockLight = new Int2ObjectOpenHashMap<>(0);
    private final Int2ObjectOpenHashMap<byte[]> skyLight = new Int2ObjectOpenHashMap<>(0);

    /** Freezes section maps by copying their backing arrays. Light arrays are immutable and shared. */
    synchronized Snapshot snapshot() {
        if (blockLight.isEmpty() && skyLight.isEmpty()) return EMPTY;
        return new Snapshot(freeze(blockLight), freeze(skyLight));
    }

    private static Int2ObjectMap<byte[]> freeze(Int2ObjectOpenHashMap<byte[]> layer) {
        return layer.isEmpty() ? Int2ObjectMaps.emptyMap() : layer.clone();
    }

    /** Moves known light arrays out of fresh chunk NBT, preserving older values for absent tags. */
    synchronized void absorbAndStrip(CompoundTag chunkNbt) {
        ListTag sections = chunkNbt.getListOrEmpty(SECTIONS_KEY);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i).orElse(null);
            if (section == null) continue;
            int y = section.getByteOr("Y", (byte) 0);
            absorbLayer(section, y, BLOCK_LIGHT_KEY, blockLight);
            absorbLayer(section, y, SKY_LIGHT_KEY, skyLight);
        }
    }

    private static void absorbLayer(CompoundTag section, int y, String key, Int2ObjectMap<byte[]> target) {
        byte[] bytes = section.getByteArray(key).orElse(null);
        if (bytes == null) return;
        target.put(y, bytes.clone());
        section.remove(key);
    }

    /** Records the exact section updates from a packet after vanilla applied it. */
    synchronized void applyUpdate(LightingUpdate update) {
        update.applyTo(blockLight, skyLight);
    }

    static final class Snapshot {
        private final Int2ObjectMap<byte[]> blockLight;
        private final Int2ObjectMap<byte[]> skyLight;

        private Snapshot(Int2ObjectMap<byte[]> blockLight, Int2ObjectMap<byte[]> skyLight) {
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        /** Produces export-safe chunk NBT with independent light arrays. */
        CompoundTag materialize(CompoundTag baseChunkNbt) {
            CompoundTag result = baseChunkNbt.copy();
            ListTag sections = result.getList(SECTIONS_KEY).orElseGet(() -> {
                ListTag created = new ListTag();
                result.put(SECTIONS_KEY, created);
                return created;
            });
            if (this == EMPTY) return result;
            Int2ObjectMap<CompoundTag> byY = new Int2ObjectOpenHashMap<>(sections.size());
            for (int i = 0; i < sections.size(); i++) {
                CompoundTag section = sections.getCompound(i).orElse(null);
                if (section != null) byY.put(section.getByteOr("Y", (byte) 0), section);
            }
            var added = applyToSections(byY, blockLight, BLOCK_LIGHT_KEY, null);
            added = applyToSections(byY, skyLight, SKY_LIGHT_KEY, added);
            if (added != null) {
                // Keep the base order and append light-only sections in a stable Y order.
                added.sort(null);
                added.forEach((int y) -> sections.add(byY.get(y)));
            }
            return result;
        }
    }

    private static IntArrayList applyToSections(Int2ObjectMap<CompoundTag> byY,
                                               Int2ObjectMap<byte[]> source, String key, IntArrayList added) {
        var entries = Int2ObjectMaps.fastIterator(source);
        while (entries.hasNext()) {
            var entry = entries.next();
            int y = entry.getIntKey();
            CompoundTag section = byY.get(y);
            if (section == null) {
                section = new CompoundTag();
                section.putByte("Y", (byte) y);
                byY.put(y, section);
                if (added == null) added = new IntArrayList();
                added.add(y);
            }
            byte[] bytes = entry.getValue();
            section.putByteArray(key, bytes == null ? new byte[LIGHT_ARRAY_SIZE] : bytes.clone());
        }
        return added;
    }
}
