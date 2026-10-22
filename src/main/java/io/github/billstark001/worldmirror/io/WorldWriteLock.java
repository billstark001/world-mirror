package io.github.billstark001.worldmirror.io;

import net.minecraft.util.DirectoryLock;

import java.io.IOException;
import java.nio.file.Path;
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
            if (!current.world.equals(world)) throw new IllegalStateException("Cannot nest different world write locks");
            current.depth++;
            return new WorldWriteLock(current);
        }
        ReentrantLock guard = LOCKS[Math.floorMod(world.hashCode(), LOCKS.length)];
        guard.lock();
        try {
            Ownership acquired = new Ownership(world, guard, DirectoryLock.create(world));
            OWNED.set(acquired);
            return new WorldWriteLock(acquired);
        } catch (IOException | RuntimeException | Error failure) {
            guard.unlock();
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
            ownership.directory.close();
        } finally {
            ownership.guard.unlock();
        }
    }
}
