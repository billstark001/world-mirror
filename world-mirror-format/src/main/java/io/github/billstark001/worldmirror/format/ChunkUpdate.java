package io.github.billstark001.worldmirror.format;

import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;

/** Capture metadata for one chunk in the target source selected by the caller. */
public record ChunkUpdate(String dimension, ChunkCoordinate coordinate, long captureTime, String updateSource) { }
