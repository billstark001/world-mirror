package io.github.billstark001.worldmirror.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;

/** One small marker makes interrupted nested replacements discoverable without scanning healthy worlds. */
public final class WorldRecovery {
    public static final String MARKER = ".worldmirror-recovery-required";
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final class Scope {
        final Path root;
        int pending;
        boolean uncertain;
        boolean marked;
        final java.util.Set<Path> discovered = new java.util.HashSet<>();
        Scope(Path root) { this.root = root; }
    }

    private WorldRecovery() {}

    public static boolean required(Path root) { return !Files.notExists(root.resolve(MARKER)); }

    static boolean owns(Path directory) {
        Scope scope = CURRENT.get();
        return scope != null && directory.startsWith(scope.root);
    }

    static boolean needsDiscovery(Path directory) { return !CURRENT.get().discovered.contains(directory); }

    static void discovered(Path directory) { CURRENT.get().discovered.add(directory); }

    /** Only the holder of the world's native session lock may begin/end this scope. */
    public static void begin(Path root) throws IOException {
        if (CURRENT.get() != null) throw new IllegalStateException("Recovery scope already owned");
        recover(root);
        CURRENT.set(new Scope(root));
    }

    public static void end() {
        Scope scope = CURRENT.get();
        try {
            if (scope != null && scope.marked && !scope.uncertain && scope.pending == 0) {
                try { Files.deleteIfExists(scope.root.resolve(MARKER)); }
                catch (IOException retained) {
                    // Retrying discovery is harmless; misreporting a durable commit
                    // as failed could incorrectly authorize an unrelated rollback.
                    WMLogger.warn("World recovery marker cleanup deferred: " + scope.root, retained);
                }
            }
        } finally { CURRENT.remove(); }
    }

    static boolean preparing(Path transactionRoot) throws IOException {
        Scope scope = CURRENT.get();
        if (scope == null || !transactionRoot.startsWith(scope.root)) return false;
        if (!scope.marked) {
            Path marker = scope.root.resolve(MARKER);
            Files.writeString(marker, "Recovery requires the native world session lock");
            try (var channel = FileChannel.open(marker, StandardOpenOption.WRITE)) { channel.force(true); }
            scope.marked = true;
        }
        scope.pending++;
        return true;
    }

    static void finished(Path directory, boolean safe) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.pending <= 0) throw new IllegalStateException("No owned replacement");
        scope.uncertain |= !safe;
        if (!safe) scope.discovered.remove(directory);
        scope.pending--;
    }

    private static void recover(Path root) throws IOException {
        if (!required(root)) {
            // Also supports old root-level journals created before a marker was durable.
            FileTransaction.recover(root);
            return;
        }
        var directories = new LinkedHashSet<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = directory.getFileName().toString();
                if (!directory.equals(root) && (name.startsWith(FileTransaction.PREFIX) || name.startsWith(FileTransaction.CLEANUP_PREFIX))) {
                    directories.add(directory.getParent());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path directory : directories) FileTransaction.recover(directory);
        Files.delete(root.resolve(MARKER));
    }
}
