package io.github.billstark001.worldmirror.merge;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Coordinates a region write and read-back while the caller holds the save's write lock. */
public final class RegionMerge {
    private RegionMerge() { }

    @FunctionalInterface
    public interface Writer<K, V> {
        Map<K, V> write() throws IOException;
    }

    @FunctionalInterface
    public interface Verifier<K, V> {
        Verification<K> reopenAndVerify(Map<K, V> staged) throws IOException;
    }

    public record Verification<K>(boolean regionUsable, Set<K> verified, Set<K> unreadable) { }

    public record Result<K, V>(Map<K, V> written, Set<K> unreadable, boolean regionUsable, boolean complete) { }

    /** Only staged entries confirmed by reopening the region are returned for indexing. */
    public static <K, V> Result<K, V> writeVerified(Writer<K, V> writer, Verifier<K, V> verifier)
            throws IOException {
        Map<K, V> staged = writer.write();
        if (staged.isEmpty()) return new Result<>(Map.of(), Set.of(), true, true);

        Verification<K> verification = verifier.reopenAndVerify(staged);
        Map<K, V> written = new HashMap<>();
        Set<K> unreadable = new HashSet<>(verification.unreadable());
        for (Map.Entry<K, V> entry : staged.entrySet()) {
            K key = entry.getKey();
            if (verification.regionUsable() && verification.verified().contains(key) && !unreadable.contains(key)) {
                written.put(key, entry.getValue());
            } else {
                unreadable.add(key);
            }
        }
        return new Result<>(Map.copyOf(written), Set.copyOf(unreadable), verification.regionUsable(),
                written.size() == staged.size());
    }
}
