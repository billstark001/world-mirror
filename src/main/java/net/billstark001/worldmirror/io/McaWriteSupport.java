package net.billstark001.worldmirror.io;

import io.github.ensgijs.nbt.mca.McaFileBase;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared MCA file write support.
 *
 * <p>Region writes are read-modify-write operations. Keep all callers on the
 * same per-file lock and replace files through a same-directory temp file so a
 * failed write does not leave a partially overwritten region behind.</p>
 */
public final class McaWriteSupport {
    private static final int WRITE_ATTEMPTS = 3;
    private static final ConcurrentHashMap<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private McaWriteSupport() {}

    public static Object lockFor(Path path) {
        return FILE_LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new Object());
    }

    public static int writeAtomically(McaFileBase<?> mcaFile, Path target) throws IOException {
        synchronized (lockFor(target)) {
            return writeAtomicallyLocked(mcaFile, target);
        }
    }

    public static int writeAtomicallyLocked(McaFileBase<?> mcaFile, Path target) throws IOException {
        IOException lastError = null;
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            Path temp = parent != null
                    ? Files.createTempFile(parent, target.getFileName().toString(), ".tmp")
                    : Files.createTempFile(target.getFileName().toString(), ".tmp");
            Files.deleteIfExists(temp);
            try {
                int chunks = McaFileHelpers.write(mcaFile, temp.toFile());
                if (chunks > 0) {
                    moveIntoPlace(temp, target);
                } else {
                    Files.deleteIfExists(temp);
                }
                return chunks;
            } catch (IOException e) {
                lastError = e;
                Files.deleteIfExists(temp);
                if (attempt < WRITE_ATTEMPTS) {
                    sleepBeforeRetry();
                }
            }
        }

        throw lastError != null ? lastError : new IOException("MCA write failed");
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepBeforeRetry() throws IOException {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying MCA write", e);
        }
    }
}
