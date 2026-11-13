package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.util.FileTransaction;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Handles v0 world-level recovery discovery before the marker protocol.
 * The journals themselves use the standard FileTransaction format.
 */
public final class LegacyWorldRecovery {
    private LegacyWorldRecovery() {}

    public static boolean hasPending(Path world) throws IOException {
        return FileTransaction.hasPending(world);
    }

    /** The caller holds the world's native session lock. */
    public static void recover(Path world) throws IOException {
        FileTransaction.recover(world);
    }
}
