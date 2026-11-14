package io.github.billstark001.worldmirror.util;

import io.github.billstark001.worldmirror.util.FileTransactionJournal.Decision;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static io.github.billstark001.worldmirror.util.FileTransactionJournal.MAX_FILES;
import static io.github.billstark001.worldmirror.util.FileTransactionJournal.MAX_MANIFEST_BYTES;
import static io.github.billstark001.worldmirror.util.FileTransactionJournal.decision;
import static io.github.billstark001.worldmirror.util.FileTransactionJournal.plainFile;
import static io.github.billstark001.worldmirror.util.FileTransactionJournal.publish;
import static io.github.billstark001.worldmirror.util.FileTransactionJournal.rejectOverlaps;
import static io.github.billstark001.worldmirror.util.FileTransactionJournal.rollback;
import static io.github.billstark001.worldmirror.util.FileTransactionJournal.target;

/** Recoverable file replacement under an exclusive caller lease; no multi-file atomic visibility. */
public final class FileTransaction implements AutoCloseable {
    public static final String PREFIX = ".worldmirror-transaction-";
    public static final String CLEANUP_PREFIX = ".worldmirror-cleanup-";
    private static final String OWNER = "owner.lock";
    private static final Map<Path, Guard> LOCKS = new HashMap<>();
    private static final class Guard {
        final ReentrantLock lock = new ReentrantLock();
        int users;
        boolean active;
    }
    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
    private final Path root;
    private final Path directory;
    private final Guard guard;
    private final Lease owner;
    private final boolean worldOwned;
    private boolean prepared, tracked, attempted, closed, committed;
    private String manifestDigest;

    public FileTransaction(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        guard = retain(this.root);
        guard.lock.lock();
        Lease acquired = null;
        try {
            if (guard.active)
                throw new IOException("Nested transaction on the same directory: " + root);
            FileProof.requirePlainDirectory(this.root);
            worldOwned = WorldRecovery.owns(this.root);
            if (worldOwned)
                FileProof.requirePlainPath(WorldRecovery.root(), this.root);
            Files.createDirectories(this.root);
            if (!worldOwned || WorldRecovery.needsDiscovery(this.root)) {
                recoverLocked(this.root);
                if (worldOwned)
                    WorldRecovery.discovered(this.root);
            }
            directory = Files.createTempDirectory(this.root, PREFIX);
            acquired = lease(directory);
            Files.createDirectory(directory.resolve("new"));
            Files.createDirectory(directory.resolve("old"));
            owner = acquired;
            guard.active = true;
        } catch (IOException | RuntimeException | Error failure) {
            if (acquired != null)
                try {
                    acquired.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            release(this.root, guard);
            throw failure;
        }
    }

    public Path stagingDirectory() {
        return directory.resolve("new");
    }

    public static boolean hasPending(Path root) throws IOException {
        if (root == null || Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
            return false;
        FileProof.requirePlainDirectory(root);
        try (var entries = Files.newDirectoryStream(root, PREFIX + "*")) {
            for (Path directory : entries) {
                FileProof.requirePlainDirectory(directory);
                if (!Files.notExists(directory.resolve("prepared"), LinkOption.NOFOLLOW_LINKS))
                    return true;
            }
        }
        return false;
    }

    /**
     * Callers validate staged semantics first. This method proves that every published file
     * has those exact bytes before recording a commit decision. Cleanup cannot undo that decision.
     */
    public Map<Path, FileProof.Version> commit(Map<Path, Path> replacements) throws IOException {
        return commit(replacements, Set.of());
    }

    /** Publishes file replacements and removals under the same recoverable decision. */
    public Map<Path, FileProof.Version> commit(Map<Path, Path> replacements, Set<Path> deletions)
            throws IOException {
        if (attempted || closed)
            throw new IllegalStateException("Replacement transaction already used");
        attempted = true;
        // Covers the cross-process constructor race: every contender owns its directory
        // before checking peers, so two active contenders cannot both enter publication.
        if (!worldOwned)
            recoverPeers(root, directory);
        List<Path> targets = new ArrayList<>(replacements.keySet());
        targets.addAll(deletions);
        if (targets.size() > MAX_FILES)
            throw new IOException("Replacement set exceeds recovery budget");
        targets.sort(Comparator.comparing(Path::toString));
        Set<Path> unique = new HashSet<>();
        Set<Path> uniqueStaged = new HashSet<>();
        for (Path relative : targets) {
            Path destination = target(root, relative.toString());
            if (!unique.add(destination))
                throw new IOException("Aliased replacement target: " + relative);
            if (!deletions.contains(relative)) {
                Path staged = replacements.get(relative).toAbsolutePath().normalize();
                if (!uniqueStaged.add(staged))
                    throw new IOException("Reused staged file: " + staged);
                plainFile(staged, stagingDirectory());
            }
        }
        rejectOverlaps(root, unique);
        Properties manifest = new Properties();
        manifest.setProperty("schema", deletions.isEmpty() ? "2" : "3");
        manifest.setProperty("count", Integer.toString(targets.size()));
        for (int i = 0; i < targets.size(); i++) {
            Path relative = targets.get(i), destination = target(root, relative.toString());
            Path staged = replacements.get(relative);
            if (deletions.contains(relative)) {
                manifest.setProperty("delete." + i, "true");
            } else {
                force(staged);
                manifest.setProperty("new." + i, FileProof.sha256(staged));
            }
            boolean exists = !Files.notExists(destination, LinkOption.NOFOLLOW_LINKS);
            if (exists) {
                plainFile(destination, root);
                Path backup = directory.resolve("old").resolve(Integer.toString(i));
                String original = FileProof.copyVerified(destination, backup);
                force(backup);
                manifest.setProperty("sha256." + i, original);
            }
            manifest.setProperty("path." + i, relative.toString());
            manifest.setProperty("existed." + i, Boolean.toString(exists));
        }
        Path journal = directory.resolve("manifest");
        try (var output = Files.newOutputStream(journal, StandardOpenOption.CREATE_NEW)) {
            manifest.store(output, "World Mirror replacement recovery");
        }
        force(journal);
        if (Files.size(journal) > MAX_MANIFEST_BYTES)
            throw new IOException("Recovery manifest exceeds budget");
        manifestDigest = FileProof.sha256(journal);
        publish(directory.resolve("manifest.sha256"), manifestDigest);
        tracked = WorldRecovery.preparing(root);
        replace(journal, directory.resolve("prepared"));
        if (!manifestDigest.equals(FileProof.sha256(directory.resolve("prepared")))) {
            throw new IOException("Published recovery manifest failed verification");
        }
        prepared = true;
        Map<Path, FileProof.Version> published = new HashMap<>();
        try {
            for (int i = 0; i < targets.size(); i++) {
                Path relative = targets.get(i);
                Path destination = target(root, relative.toString());
                if (deletions.contains(relative)) {
                    Files.deleteIfExists(destination);
                    if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Published deletion could not be verified: " + destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                replace(replacements.get(relative), destination);
                force(destination);
                if (!manifest.getProperty("new." + i).equals(FileProof.sha256(destination))) {
                    throw new IOException("Published file failed verification: " + destination);
                }
                published.put(relative, FileProof.version(destination));
            }
            publish(directory.resolve("committed"), manifestDigest);
            committed = true;
        } catch (IOException | RuntimeException failure) {
            try {
                Decision current = decision(directory, manifestDigest);
                if (current == Decision.COMMITTED) {
                    committed = true;
                    return Map.copyOf(published);
                }
                if (current == Decision.UNDECIDED) {
                    rollback(root, directory, manifest);
                    publish(directory.resolve("rolled-back"), manifestDigest);
                }
            } catch (IOException recovery) {
                failure.addSuppressed(recovery);
            }
            throw failure;
        }
        return Map.copyOf(published);
    }

    /** The caller must hold the same exclusive world/configuration authority as writers. */
    public static void recover(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        Guard guard = retain(root);
        guard.lock.lock();
        try {
            if (guard.active)
                throw new IOException("Cannot recover an active transaction: " + root);
            recoverLocked(root);
        } finally {
            release(root, guard);
        }
    }

    private static synchronized Guard retain(Path root) {
        Guard guard = LOCKS.computeIfAbsent(root, ignored -> new Guard());
        guard.users++;
        return guard;
    }

    private static void release(Path root, Guard guard) {
        guard.lock.unlock();
        synchronized (FileTransaction.class) {
            if (--guard.users == 0)
                LOCKS.remove(root, guard);
        }
    }

    private static Lease lease(Path directory) throws IOException {
        Path file = directory.resolve(OWNER);
        FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null)
                throw new IOException("Transaction is active: " + directory);
            return new Lease(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException close) {
                failure.addSuppressed(close);
            }
            if (failure instanceof OverlappingFileLockException)
                throw new IOException("Transaction is active: " + directory, failure);
            throw failure;
        }
    }

    private static void recoverLocked(Path root) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
            return;
        FileProof.requirePlainDirectory(root);
        recoverPeers(root, null);
        try (var entries = Files.newDirectoryStream(root, CLEANUP_PREFIX + "*")) {
            for (Path directory : entries) {
                try {
                    removeDirectory(directory);
                } catch (IOException retained) {
                    WMLogger.warnRateLimited("transaction-cleanup-" + root, 60_000L,
                            "Terminal transaction debris retained: " + directory, retained);
                }
            }
        }
    }

    private static void recoverPeers(Path root, Path own) throws IOException {
        try (var entries = Files.newDirectoryStream(root, PREFIX + "*")) {
            for (Path directory : entries) {
                if (directory.equals(own))
                    continue;
                FileProof.requirePlainDirectory(directory);
                try (Lease ignored = lease(directory)) {
                    FileTransactionJournal.recover(root, directory);
                }
                retire(directory);
            }
        }
    }

    static void replace(Path source, Path target) throws IOException {
        for (int attempt = 0;; attempt++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.AccessDeniedException transientLock) {
                if (attempt >= 9)
                    throw transientLock;
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted replacing " + target, interrupted);
                }
            }
        }
    }

    static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void retire(Path directory) throws IOException {
        Path retired = directory.resolveSibling(
                CLEANUP_PREFIX + directory.getFileName().toString().substring(PREFIX.length()));
        try {
            try {
                Files.move(directory, retired, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(directory, retired);
            }
        } catch (java.nio.file.NoSuchFileException alreadyRetired) {
            if (Files.notExists(directory))
                return;
            throw alreadyRetired;
        }
        try {
            removeDirectory(retired);
        } catch (IOException retained) {
            WMLogger.warnRateLimited("transaction-cleanup-" + retired.getParent(), 60_000L,
                    "Terminal transaction debris retained: " + retired, retained);
        }
    }

    private static void removeDirectory(Path directory) throws IOException {
        FileProof.requirePlainDirectory(directory);
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
                    throws IOException {
                if (!FileProof.isPlainDirectory(attributes))
                    throw new IOException("Aliased cleanup directory: " + dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error)
                    throws IOException {
                if (error != null)
                    throw error;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() throws IOException {
        if (closed)
            return;
        closed = true;
        boolean terminal = committed || !prepared;
        IOException failure = null;
        try {
            if (prepared && !committed)
                terminal = decision(directory, manifestDigest) != Decision.UNDECIDED;
        } catch (IOException uncertain) {
            failure = uncertain;
        }
        boolean retired = false;
        try {
            try {
                owner.close();
            } catch (IOException closing) {
                if (failure == null)
                    failure = closing;
                else
                    failure.addSuppressed(closing);
            }
            if (terminal && failure == null) {
                try {
                    retire(directory);
                    retired = true;
                } catch (IOException cleanup) {
                    // A committed document remains committed even if its garbage cannot be retired.
                    if (!prepared)
                        throw cleanup;
                    WMLogger.warn("Verified transaction cleanup deferred: " + directory, cleanup);
                }
            }
            if (failure != null) {
                if (committed)
                    WMLogger.warn("Committed transaction cleanup deferred: " + directory, failure);
                else
                    throw failure;
            }
        } finally {
            guard.active = false;
            try {
                if (tracked)
                    WorldRecovery.finished(root, retired);
            } finally {
                release(root, guard);
            }
        }
    }
}
