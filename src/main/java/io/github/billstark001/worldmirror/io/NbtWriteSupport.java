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

/** Lossless-string preflight shared by native NBT writers. */
final class NbtWriteSupport {
    private NbtWriteSupport() { }

    /** Owns the native output stream; the digest describes exactly the uncompressed bytes written. */
    static byte[] writeChunkAndDigest(CompoundTag tag, DataOutputStream nativeOutput) throws IOException {
        var digest = io.github.billstark001.worldmirror.util.FileProof.sha256();
        try (var output = new DataOutputStream(new java.security.DigestOutputStream(nativeOutput, digest))) {
            net.minecraft.nbt.NbtIo.writeUnnamedTag(tag, output);
        }
        return digest.digest();
    }

    /**
     * NbtIo.write enables an empty-string fallback on modified-UTF overflow;
     * strict native writing avoids that fallback. In-place MCA writes additionally
     * validate before opening their output stream: closing even a failed native
     * chunk stream commits its buffered bytes. Staged files need no such traversal.
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
