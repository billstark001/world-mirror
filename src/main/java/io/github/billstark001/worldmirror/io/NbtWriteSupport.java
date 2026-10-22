package io.github.billstark001.worldmirror.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Lossless-string preflight shared by native NBT writers and the vendored NBT bridge. */
final class NbtWriteSupport {
    private NbtWriteSupport() { }

    /**
     * Mojang's StringTag writer falls back to an empty string on modified-UTF
     * overflow. Validate both keys and values with Java's actual encoder before
     * opening a region output stream, which commits its bytes when closed.
     */
    static void validateStrings(CompoundTag root) throws IOException {
        Set<Tag> seenContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Tag> pending = new ArrayDeque<>();
        pending.push(root);
        try (DataOutputStream utf = new DataOutputStream(OutputStream.nullOutputStream())) {
            while (!pending.isEmpty()) {
                Tag tag = pending.pop();
                if (tag instanceof StringTag string) {
                    utf.writeUTF(string.asString().orElseThrow());
                } else if (tag instanceof CompoundTag compound && seenContainers.add(tag)) {
                    for (var entry : compound.entrySet()) {
                        utf.writeUTF(entry.getKey());
                        pending.push(entry.getValue());
                    }
                } else if (tag instanceof ListTag list && seenContainers.add(tag)) {
                    list.forEach(pending::push);
                }
            }
        }
    }
}
