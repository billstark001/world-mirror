package io.github.billstark001.worldmirror.download;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

import io.github.billstark001.worldmirror.download.DownloadCaptureQueue.Key;

/** Client-thread history of sampled remote chunks, independent of export cache invalidation. */
final class CapturedChunkObservations {
    private static final int MAX_LOADED_OBSERVATIONS = 16_384;
    private final LinkedHashMap<Key, WeakReference<Object>> chunks = new LinkedHashMap<>();

    boolean contains(Key key, Object identity) {
        var chunk = chunks.get(key);
        return identity != null && chunk != null && chunk.get() == identity;
    }

    void record(Key key, Object identity) {
        if (identity == null)
            return;
        chunks.putLast(key, new WeakReference<>(identity));
        if (chunks.size() > MAX_LOADED_OBSERVATIONS)
            chunks.pollFirstEntry();
    }

    void remove(Key key) {
        chunks.remove(key);
    }

    void clear() {
        chunks.clear();
    }
}
