package io.github.billstark001.worldmirror.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static io.github.billstark001.worldmirror.util.FileTransaction.CLEANUP_PREFIX;
import static io.github.billstark001.worldmirror.util.FileTransaction.PREFIX;
import static io.github.billstark001.worldmirror.util.FileTransaction.force;
import static io.github.billstark001.worldmirror.util.FileTransaction.replace;

/** Durable decision records and a verified rollback plan for a leased transaction directory. */
final class FileTransactionJournal {
    static final int MAX_FILES = 65_536;
    static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;

    enum Decision { UNDECIDED, COMMITTED, ROLLED_BACK }

    private record Entry(Path target, Path backup, String original) {}

    private FileTransactionJournal() {}

    static void recover(Path root, Path directory) throws IOException {
        Path journal = directory.resolve("prepared");
        if (Files.notExists(journal))
            return;
        plainFile(journal, directory);
        String digest = readDigest(directory.resolve("manifest.sha256"));
        if (Files.size(journal) > MAX_MANIFEST_BYTES || !digest.equals(FileProof.sha256(journal)))
            throw new IOException("Recovery manifest failed verification: " + journal);
        if (decision(directory, digest) != Decision.UNDECIDED)
            return;
        Properties manifest = new Properties();
        try (var input = Files.newInputStream(journal)) {
            manifest.load(input);
        }
        rollback(root, directory, manifest);
        publish(directory.resolve("rolled-back"), digest);
        WMLogger.info("Interrupted file transaction rolled back root=" + root
                + " transaction=" + directory.getFileName());
    }

    static Decision decision(Path directory, String digest) throws IOException {
        boolean committed = !Files.notExists(directory.resolve("committed"));
        boolean rolledBack = !Files.notExists(directory.resolve("rolled-back"));
        if (committed && rolledBack)
            throw new IOException("Conflicting recovery decisions: " + directory);
        if (committed && !digest.equals(readDigest(directory.resolve("committed")))
                || rolledBack && !digest.equals(readDigest(directory.resolve("rolled-back")))) {
            throw new IOException("Unverified recovery decision: " + directory);
        }
        return committed ? Decision.COMMITTED : rolledBack ? Decision.ROLLED_BACK : Decision.UNDECIDED;
    }

    private static List<Entry> restorePlan(Path root, Path directory, Properties manifest)
            throws IOException {
        int count = Integer.parseInt(manifest.getProperty("count"));
        String schema = manifest.getProperty("schema");
        if (count < 0 || count > MAX_FILES || !Set.of("2", "3").contains(schema))
            throw new IOException("Unsupported recovery manifest");
        List<Entry> restore = new ArrayList<>();
        Set<Path> unique = new HashSet<>();
        for (int i = 0; i < count; i++) {
            Path destination = target(root, manifest.getProperty("path." + i));
            if (!unique.add(destination))
                throw new IOException("Duplicate recovery target");
            String existed = manifest.getProperty("existed." + i);
            if (!"true".equals(existed) && !"false".equals(existed))
                throw new IOException("Missing recovery state");
            String original =
                    Boolean.parseBoolean(existed) ? digestValue(manifest.getProperty("sha256." + i)) : null;
            String deletion = manifest.getProperty("delete." + i);
            if (deletion != null
                    && (!"3".equals(schema) || !"true".equals(deletion) || manifest.containsKey("new." + i)))
                throw new IOException("Invalid deletion entry");
            String replacement = deletion != null ? null : digestValue(manifest.getProperty("new." + i));
            Path backup = directory.resolve("old").resolve(Integer.toString(i));
            if (original != null) {
                plainFile(backup, directory);
                if (!original.equals(FileProof.sha256(backup)))
                    throw new IOException("Invalid recovery backup: " + backup);
            }
            String current = null;
            if (!Files.notExists(destination)) {
                plainFile(destination, root);
                current = FileProof.sha256(destination);
            }
            if (current != null && !current.equals(original) && !current.equals(replacement)) {
                throw new IOException(
                        "Recovery target changed outside this transaction; copies retained: " + destination);
            }
            if (!java.util.Objects.equals(current, original))
                restore.add(new Entry(destination, backup, original));
        }
        rejectOverlaps(root, unique);
        return restore;
    }

    static void rollback(Path root, Path directory, Properties manifest) throws IOException {
        try {
            // Prove every backup and current target before modifying any file.
            for (Entry entry : restorePlan(root, directory, manifest)) restore(directory, entry);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid recovery manifest: " + directory, invalid);
        }
    }

    private static void restore(Path directory, Entry entry) throws IOException {
        if (entry.original() == null) {
            Files.deleteIfExists(entry.target());
            return;
        }
        Path temporary = Files.createTempFile(directory, "restore-", ".tmp");
        Files.copy(entry.backup(), temporary, StandardCopyOption.REPLACE_EXISTING);
        force(temporary);
        replace(temporary, entry.target());
        force(entry.target());
        if (!entry.original().equals(FileProof.sha256(entry.target())))
            throw new IOException("Recovery verification failed: " + entry.target());
    }

    static void rejectOverlaps(Path root, Set<Path> targets) throws IOException {
        for (Path destination : targets) {
            for (Path parent = destination.getParent(); parent != null && !parent.equals(root);
                    parent = parent.getParent()) {
                if (targets.contains(parent))
                    throw new IOException("Overlapping replacement targets");
            }
        }
    }

    static Path target(Path root, String name) throws IOException {
        if (name == null)
            throw new IOException("Missing recovery target");
        Path relative = Path.of(name), destination = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !destination.startsWith(root) || destination.equals(root))
            throw new IOException("Invalid recovery target: " + name);
        Path normalized = root.relativize(destination);
        if (normalized.toString().equals("session.lock"))
            throw new IOException("Reserved recovery target: " + name);
        for (Path part : normalized) {
            String segment = part.toString();
            if (segment.startsWith(PREFIX) || segment.startsWith(CLEANUP_PREFIX)
                    || segment.equals(WorldRecovery.MARKER)) {
                throw new IOException("Reserved recovery target: " + name);
            }
        }
        FileProof.requirePlainPath(root, destination);
        return destination;
    }

    static void plainFile(Path file, Path root) throws IOException {
        if (!file.startsWith(root) || file.equals(root))
            throw new IOException("Foreign staged/backup file: " + file);
        FileProof.requirePlainPath(root, file);
        FileProof.requirePlainFile(file);
    }

    private static String readDigest(Path file) throws IOException {
        if (FileProof.requirePlainFile(file).size() != 64)
            throw new IOException("Invalid decision/checksum: " + file);
        return digestValue(Files.readString(file));
    }

    private static String digestValue(String value) throws IOException {
        if (value == null || value.length() != 64
                || !value.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
            throw new IOException("Invalid SHA-256 proof");
        }
        return value;
    }

    static void publish(Path destination, String digest) throws IOException {
        Path ready = destination.resolveSibling(destination.getFileName() + ".ready");
        Files.writeString(ready, digest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        force(ready);
        replace(ready, destination);
        if (!digest.equals(readDigest(destination)))
            throw new IOException("Decision publication failed: " + destination);
    }
}
