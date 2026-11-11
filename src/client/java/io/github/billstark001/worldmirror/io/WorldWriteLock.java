package io.github.billstark001.worldmirror.io;

import net.minecraft.util.DirectoryLock;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.channels.OverlappingFileLockException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/** Holds Minecraft's session lock for one complete offline world mutation. */
public final class WorldWriteLock implements AutoCloseable {
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];
    private static final ThreadLocal<Ownership> OWNED = new ThreadLocal<>();

    static {
        Arrays.setAll(LOCKS, ignored -> new ReentrantLock());
    }

    private static final class Ownership {
        final Path world;
        final ReentrantLock guard;
        final DirectoryLock directory;
        int depth = 1;

        Ownership(Path world, ReentrantLock guard, DirectoryLock directory) {
            this.world = world;
            this.guard = guard;
            this.directory = directory;
        }
    }

    private final Ownership ownership;
    private boolean closed;

    private WorldWriteLock(Ownership ownership) {
        this.ownership = ownership;
    }

    /**
     * Acquires the vanilla session lock, failing if Minecraft already has this
     * save open. Nested calls on the same thread and world share one lease.
     * A mutation must not nest a different world root or hold an MCA file lock
     * while acquiring this lease. Close it on the acquiring thread.
     */
    public static WorldWriteLock acquire(Path folder) throws IOException {
        Path world = folder.toAbsolutePath().normalize();
        Ownership current = OWNED.get();
        if (current != null) {
            boolean stagedMigration = world.startsWith(current.world)
                    && current.world.relativize(world).getNameCount() == 2
                    && current.world.relativize(world).getName(0).toString().startsWith(".worldmirror-transaction-")
                    && world.getFileName().toString().equals("new");
            if (!current.world.equals(world) && !stagedMigration) {
                throw new IllegalStateException("Cannot nest different world write locks");
            }
            current.depth++;
            return new WorldWriteLock(current);
        }
        ReentrantLock guard = LOCKS[Math.floorMod(world.hashCode(), LOCKS.length)];
        guard.lock();
        try {
            Ownership acquired = new Ownership(world, guard, acquireDirectory(world));
            OWNED.set(acquired);
            try { io.github.billstark001.worldmirror.util.WorldRecovery.begin(world); }
            catch (IOException | RuntimeException | Error failure) {
                OWNED.remove();
                try { acquired.directory.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
            return new WorldWriteLock(acquired);
        } catch (IOException | RuntimeException | Error failure) {
            guard.unlock();
            throw failure;
        }
    }

    /** Acquires only the native lock; use acquire() when this root also needs World Mirror recovery. */
    public static DirectoryLock acquireDirectory(Path world) throws IOException {
        try {
            // The probe closes its channel even on a same-JVM overlap. Vanilla's
            // create() only cleans up IOExceptions, so avoid that path when busy.
            if (DirectoryLock.isLocked(world)) throw DirectoryLock.LockException.alreadyLocked(world.resolve(DirectoryLock.LOCK_FILE));
            // The probe is only a fast rejection; this native lock remains the
            // authority if another process opens the world between the two calls.
            return DirectoryLock.create(world);
        } catch (OverlappingFileLockException overlap) {
            var failure = DirectoryLock.LockException.alreadyLocked(world.resolve(DirectoryLock.LOCK_FILE));
            failure.initCause(overlap);
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        if (OWNED.get() != ownership) throw new IllegalStateException("World write lock closed on another thread");
        closed = true;
        if (--ownership.depth > 0) return;
        OWNED.remove();
        try {
            io.github.billstark001.worldmirror.util.WorldRecovery.end();
        } finally {
            try { ownership.directory.close(); }
            finally { ownership.guard.unlock(); }
        }
    }
}
