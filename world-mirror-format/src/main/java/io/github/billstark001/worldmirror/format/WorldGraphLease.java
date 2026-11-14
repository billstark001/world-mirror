package io.github.billstark001.worldmirror.format;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process lease for the native graph. Acquire this before taking a save lock. */
public final class WorldGraphLease {
    public static final String FILE_NAME = ".world-graph.lock";

    private WorldGraphLease() {}

    /** The caller checks the configuration directory. Closing the channel releases the lease. */
    public static FileChannel acquire(Path directory) throws IOException {
        var channel = FileChannel.open(directory.resolve(FILE_NAME), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.tryLock() == null)
                throw new IOException("World graph operation is already running");
            return channel;
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("World graph operation is already running", failure);
            }
            throw failure;
        }
    }
}
