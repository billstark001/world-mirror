package io.github.billstark001.worldmirror.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Recoverable file replacement under an exclusive caller lease; no multi-file atomic visibility. */
public final class FileTransaction implements AutoCloseable {
    public static final String PREFIX = ".worldmirror-transaction-";
    public static final String CLEANUP_PREFIX = ".worldmirror-cleanup-";
    private static final String OWNER = "owner.lock";
    private static final int MAX_FILES = 65_536;
    private static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;
    private static final Map<Path, Guard> LOCKS = new HashMap<>();
    private static final class Guard {
        final ReentrantLock lock = new ReentrantLock();
        int users;
        boolean active;
    }
    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
    private record Entry(Path target, Path backup, String original) {}
    private enum Decision { UNDECIDED, COMMITTED, ROLLED_BACK }
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
            if (guard.active) throw new IOException("Nested transaction on the same directory: " + root);
            Files.createDirectories(this.root);
            worldOwned = WorldRecovery.owns(this.root);
            if (!worldOwned || WorldRecovery.needsDiscovery(this.root)) {
                recoverLocked(this.root);
                if (worldOwned) WorldRecovery.discovered(this.root);
            }
            directory = Files.createTempDirectory(this.root, PREFIX);
            acquired = lease(directory);
            Files.createDirectory(directory.resolve("new"));
            Files.createDirectory(directory.resolve("old"));
            owner = acquired;
            guard.active = true;
        } catch (IOException | RuntimeException | Error failure) {
            if (acquired != null) try { acquired.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            release(this.root, guard);
            throw failure;
        }
    }

    public Path stagingDirectory() { return directory.resolve("new"); }

    public static boolean hasPending(Path root) throws IOException {
        if (root == null || Files.notExists(root)) return false;
        try (var entries = Files.newDirectoryStream(root, PREFIX + "*")) {
            for (Path directory : entries) if (!Files.notExists(directory.resolve("prepared"))) return true;
        }
        return false;
    }

    /**
     * Callers validate staged semantics first. This method proves that every published file
     * has those exact bytes before recording a commit decision. Cleanup cannot undo that decision.
     */
    public Map<Path, FileProof.Version> commit(Map<Path, Path> replacements) throws IOException {
        if (attempted || closed) throw new IllegalStateException("Replacement transaction already used");
        attempted = true;
        // Covers the cross-process constructor race: every contender owns its directory
        // before checking peers, so two active contenders cannot both enter publication.
        if (!worldOwned) recoverPeers(root, directory);
        List<Path> targets = new ArrayList<>(replacements.keySet());
        if (targets.size() > MAX_FILES) throw new IOException("Replacement set exceeds recovery budget");
        targets.sort(Comparator.comparing(Path::toString));
        Set<Path> unique = new HashSet<>();
        Set<Path> uniqueStaged = new HashSet<>();
        for (Path relative : targets) {
            Path destination = target(root, relative.toString());
            if (!unique.add(destination)) throw new IOException("Aliased replacement target: " + relative);
            Path staged = replacements.get(relative).toAbsolutePath().normalize();
            if (!uniqueStaged.add(staged)) throw new IOException("Reused staged file: " + staged);
            plainFile(staged, stagingDirectory());
        }
        rejectOverlaps(root, unique);
        Properties manifest = new Properties();
        manifest.setProperty("schema", "2");
        manifest.setProperty("count", Integer.toString(targets.size()));
        for (int i = 0; i < targets.size(); i++) {
            Path relative = targets.get(i), destination = target(root, relative.toString());
            Path staged = replacements.get(relative);
            force(staged);
            manifest.setProperty("new." + i, FileProof.sha256(staged));
            boolean exists = !Files.notExists(destination);
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
        if (Files.size(journal) > MAX_MANIFEST_BYTES) throw new IOException("Recovery manifest exceeds budget");
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
            } catch (IOException recovery) { failure.addSuppressed(recovery); }
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
            if (guard.active) throw new IOException("Cannot recover an active transaction: " + root);
            recoverLocked(root);
        } finally { release(root, guard); }
    }

    private static synchronized Guard retain(Path root) {
        Guard guard = LOCKS.computeIfAbsent(root, ignored -> new Guard());
        guard.users++;
        return guard;
    }

    private static void release(Path root, Guard guard) {
        guard.lock.unlock();
        synchronized (FileTransaction.class) { if (--guard.users == 0) LOCKS.remove(root, guard); }
    }

    private static Lease lease(Path directory) throws IOException {
        Path file = directory.resolve(OWNER);
        if (Files.isSymbolicLink(file)) throw new IOException("Symbolic transaction owner: " + file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Transaction is active: " + directory);
            return new Lease(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (failure instanceof OverlappingFileLockException) throw new IOException("Transaction is active: " + directory, failure);
            throw failure;
        }
    }

    private static void recoverLocked(Path root) throws IOException {
        if (Files.notExists(root)) return;
        recoverPeers(root, null);
        try (var entries = Files.newDirectoryStream(root, CLEANUP_PREFIX + "*")) {
            for (Path directory : entries) {
                try { removeDirectory(directory); }
                catch (IOException retained) { WMLogger.warnRateLimited("transaction-cleanup-" + root, 60_000L,
                        "Terminal transaction debris retained: " + directory, retained); }
            }
        }
    }

    private static void recoverPeers(Path root, Path own) throws IOException {
        try (var entries = Files.newDirectoryStream(root, PREFIX + "*")) {
            for (Path directory : entries) {
                if (directory.equals(own)) continue;
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) throw new IOException("Invalid transaction directory: " + directory);
                try (Lease ignored = lease(directory)) {
                    Path journal = directory.resolve("prepared");
                    if (!Files.notExists(journal)) {
                        plainFile(journal, directory);
                        String digest = readDigest(directory.resolve("manifest.sha256"));
                        if (Files.size(journal) > MAX_MANIFEST_BYTES || !digest.equals(FileProof.sha256(journal))) {
                            throw new IOException("Recovery manifest failed verification: " + journal);
                        }
                        if (decision(directory, digest) == Decision.UNDECIDED) {
                            Properties manifest = new Properties();
                            try (InputStream input = Files.newInputStream(journal)) { manifest.load(input); }
                            rollback(root, directory, manifest);
                            publish(directory.resolve("rolled-back"), digest);
                        }
                    }
                }
                retire(directory);
            }
        }
    }

    private static Decision decision(Path directory, String digest) throws IOException {
        boolean committed = !Files.notExists(directory.resolve("committed"));
        boolean rolledBack = !Files.notExists(directory.resolve("rolled-back"));
        if (committed && rolledBack) throw new IOException("Conflicting recovery decisions: " + directory);
        if (committed && !digest.equals(readDigest(directory.resolve("committed")))
                || rolledBack && !digest.equals(readDigest(directory.resolve("rolled-back")))) {
            throw new IOException("Unverified recovery decision: " + directory);
        }
        return committed ? Decision.COMMITTED : rolledBack ? Decision.ROLLED_BACK : Decision.UNDECIDED;
    }

    private static void rollback(Path root, Path directory, Properties manifest) throws IOException {
        try {
            int count = Integer.parseInt(manifest.getProperty("count"));
            if (count < 0 || count > MAX_FILES || !"2".equals(manifest.getProperty("schema"))) throw new IOException("Unsupported recovery manifest");
            List<Entry> restore = new ArrayList<>();
            Set<Path> unique = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Path destination = target(root, manifest.getProperty("path." + i));
                if (!unique.add(destination)) throw new IOException("Duplicate recovery target");
                String existed = manifest.getProperty("existed." + i);
                if (!"true".equals(existed) && !"false".equals(existed)) throw new IOException("Missing recovery state");
                String original = Boolean.parseBoolean(existed) ? digestValue(manifest.getProperty("sha256." + i)) : null;
                String replacement = digestValue(manifest.getProperty("new." + i));
                Path backup = directory.resolve("old").resolve(Integer.toString(i));
                if (original != null) {
                    plainFile(backup, directory);
                    if (!original.equals(FileProof.sha256(backup))) throw new IOException("Invalid recovery backup: " + backup);
                }
                String current = null;
                if (!Files.notExists(destination)) { plainFile(destination, root); current = FileProof.sha256(destination); }
                if (current != null && !current.equals(original) && !current.equals(replacement)) {
                    throw new IOException("Recovery target changed outside this transaction; copies retained: " + destination);
                }
                if (!java.util.Objects.equals(current, original)) restore.add(new Entry(destination, backup, original));
            }
            rejectOverlaps(root, unique);
            // No file is changed until every backup and every current target has passed.
            for (Entry entry : restore) {
                if (entry.original() == null) Files.deleteIfExists(entry.target());
                else {
                    Path temporary = Files.createTempFile(directory, "restore-", ".tmp");
                    Files.copy(entry.backup(), temporary, StandardCopyOption.REPLACE_EXISTING);
                    force(temporary);
                    replace(temporary, entry.target());
                    force(entry.target());
                    if (!entry.original().equals(FileProof.sha256(entry.target()))) throw new IOException("Recovery verification failed: " + entry.target());
                }
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid recovery manifest: " + directory, invalid); }
    }

    private static void rejectOverlaps(Path root, Set<Path> targets) throws IOException {
        for (Path destination : targets) {
            for (Path parent = destination.getParent(); parent != null && !parent.equals(root); parent = parent.getParent()) {
                if (targets.contains(parent)) throw new IOException("Overlapping replacement targets");
            }
        }
    }

    private static Path target(Path root, String name) throws IOException {
        if (name == null) throw new IOException("Missing recovery target");
        Path relative = Path.of(name), destination = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !destination.startsWith(root) || destination.equals(root)) throw new IOException("Invalid recovery target: " + name);
        Path normalized = root.relativize(destination);
        if (normalized.toString().equals("session.lock")) throw new IOException("Reserved recovery target: " + name);
        for (Path part : normalized) {
            String segment = part.toString();
            if (segment.startsWith(PREFIX) || segment.startsWith(CLEANUP_PREFIX) || segment.equals(WorldRecovery.MARKER)) {
                throw new IOException("Reserved recovery target: " + name);
            }
        }
        rejectLinks(destination, root);
        return destination;
    }

    private static void plainFile(Path file, Path root) throws IOException {
        if (!file.startsWith(root) || file.equals(root)) throw new IOException("Foreign staged/backup file: " + file);
        rejectLinks(file, root);
        if (!Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("Not a regular transaction file: " + file);
        }
    }

    private static void rejectLinks(Path file, Path root) throws IOException {
        for (Path part = file; !part.equals(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic transaction path: " + part);
        }
    }

    private static String readDigest(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || Files.size(file) != 64) throw new IOException("Invalid decision/checksum: " + file);
        return digestValue(Files.readString(file));
    }

    private static String digestValue(String value) throws IOException {
        if (value == null || value.length() != 64 || !value.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
            throw new IOException("Invalid SHA-256 proof");
        }
        return value;
    }

    private static void publish(Path destination, String digest) throws IOException {
        Path ready = destination.resolveSibling(destination.getFileName() + ".ready");
        Files.writeString(ready, digest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        force(ready);
        replace(ready, destination);
        if (!digest.equals(readDigest(destination))) throw new IOException("Decision publication failed: " + destination);
    }

    private static void replace(Path source, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch (java.nio.file.AccessDeniedException transientLock) {
                if (attempt >= 9) throw transientLock;
                try { Thread.sleep(50L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted replacing " + target, interrupted); }
            }
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void retire(Path directory) throws IOException {
        Path retired = directory.resolveSibling(CLEANUP_PREFIX + directory.getFileName().toString().substring(PREFIX.length()));
        try {
            try { Files.move(directory, retired, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(directory, retired); }
        } catch (java.nio.file.NoSuchFileException alreadyRetired) { if (Files.notExists(directory)) return; throw alreadyRetired; }
        try { removeDirectory(retired); }
        catch (IOException retained) { WMLogger.warnRateLimited("transaction-cleanup-" + retired.getParent(), 60_000L,
                "Terminal transaction debris retained: " + retired, retained); }
    }

    private static void removeDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Symbolic cleanup directory: " + directory);
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        boolean terminal = committed || !prepared;
        IOException failure = null;
        try { if (prepared && !committed) terminal = decision(directory, manifestDigest) != Decision.UNDECIDED; }
        catch (IOException uncertain) { failure = uncertain; }
        boolean retired = false;
        try {
            try { owner.close(); }
            catch (IOException closing) {
                if (failure == null) failure = closing; else failure.addSuppressed(closing);
            }
            if (terminal && failure == null) {
                try { retire(directory); retired = true; }
                catch (IOException cleanup) {
                    // A committed document remains committed even if its garbage cannot be retired.
                    if (!prepared) throw cleanup;
                    WMLogger.warn("Verified transaction cleanup deferred: " + directory, cleanup);
                }
            }
            if (failure != null) {
                if (committed) WMLogger.warn("Committed transaction cleanup deferred: " + directory, failure);
                else throw failure;
            }
        } finally {
            guard.active = false;
            try { if (tracked) WorldRecovery.finished(root, retired); }
            finally { release(root, guard); }
        }
    }
}
