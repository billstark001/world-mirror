package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.security.DigestOutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Lossless-string preflight shared by native NBT writers. */
final class NbtWriteSupport {
    private static final int MAX_UTF_BYTES = 65_535;

    private NbtWriteSupport() {}

    /** Owns the native output stream; the digest describes exactly the uncompressed bytes written. */
    static byte[] writeChunkAndDigest(CompoundTag tag, DataOutputStream nativeOutput) throws IOException {
        var digest = FileProof.sha256();
        try (var output = new DataOutputStream(
                     new BufferedOutputStream(new DigestOutputStream(nativeOutput, digest)))) {
            NbtIo.writeUnnamedTag(tag, output);
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
        while (!pending.isEmpty()) {
            Tag tag = pending.pop();
            if (tag instanceof StringTag string) {
                validateUtfLength(string.asString().orElseThrow());
            } else if (tag instanceof CompoundTag compound && seenContainers.add(tag)) {
                for (var entry : compound.entrySet()) {
                    validateUtfLength(entry.getKey());
                    pending.push(entry.getValue());
                }
            } else if (tag instanceof ListTag list && seenContainers.add(tag)) {
                list.forEach(pending::push);
            }
        }
    }

    private static void validateUtfLength(String value) throws UTFDataFormatException {
        int length = value.length();
        if (length <= MAX_UTF_BYTES / 3)
            return;
        // Modified UTF-8 counts UTF-16 code units, including both halves of a surrogate pair.
        int bytes = length;
        for (int i = 0; i < length && bytes <= MAX_UTF_BYTES; i++) {
            char unit = value.charAt(i);
            if (unit == 0 || unit > 0x7F)
                bytes += unit <= 0x7FF ? 1 : 2;
        }
        if (bytes > MAX_UTF_BYTES) {
            throw new UTFDataFormatException("NBT string exceeds " + MAX_UTF_BYTES + " modified UTF-8 bytes");
        }
    }
}
