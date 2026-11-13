package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps SQLite's read-side WAL and shared memory files out of the original save. */
final class SeedIndexSnapshot implements AutoCloseable {
    static final Path DATABASE = Path.of(MirrorFormat.DATABASE_FILE);
    private static final Path WAL = DATABASE.resolveSibling(DATABASE.getFileName() + "-wal");
    private static final Path JOURNAL = DATABASE.resolveSibling(DATABASE.getFileName() + "-journal");
    private static final Path SHM = DATABASE.resolveSibling(DATABASE.getFileName() + "-shm");
    private static final List<Path> DURABLE_FILES = List.of(DATABASE, WAL, JOURNAL);

    private final Path root;
    private final Map<Path, String> proofs = new HashMap<>();

    private SeedIndexSnapshot(Path root) {
        this.root = root;
    }

    /** The caller holds the native world lease and excludes cooperative index readers. */
    static SeedIndexSnapshot open(Path world, Runnable checkpoint) throws IOException {
        var versions = versions(world);
        if (!versions.containsKey(DATABASE)) {
            if (!versions.isEmpty())
                throw new IOException("Chunk index journal exists without its database");
            return new SeedIndexSnapshot(null);
        }
        var snapshot = new SeedIndexSnapshot(Files.createTempDirectory("wm-seed-index-"));
        try {
            Files.createDirectories(snapshot.root.resolve(DATABASE).getParent());
            for (Path file : DURABLE_FILES) {
                if (versions.containsKey(file)) {
                    String digest = FileProof.copyVerified(
                            world.resolve(file), snapshot.root.resolve(file), checkpoint);
                    snapshot.proofs.put(file, digest);
                }
            }
            if (!versions.equals(versions(world)))
                throw new IOException("Chunk index changed while copying for seed conversion");
            checkpoint.run();
            return snapshot;
        } catch (IOException | RuntimeException | Error failure) {
            snapshot.close();
            throw failure;
        }
    }

    private static Map<Path, FileProof.Version> versions(Path world) throws IOException {
        FileProof.requirePlainDirectory(world.resolve(DATABASE).getParent());
        FileProof.requirePlainFileIfPresent(world.resolve(SHM));
        var found = new HashMap<Path, FileProof.Version>();
        for (Path file : DURABLE_FILES) {
            try {
                found.put(file, FileProof.version(world.resolve(file)));
            } catch (NoSuchFileException absent) {
                // No payload to copy for this file.
            }
        }
        return found;
    }

    Path root() {
        return root;
    }

    String orphanNamespace() throws SQLException {
        return root == null ? null : ChunkDatabase.readOrphanNamespace(root);
    }

    Map<Path, String> proofs() {
        return proofs;
    }

    @Override
    public void close() {
        if (root == null)
            return;
        try {
            for (Path file : List.of(SHM, WAL, JOURNAL, DATABASE)) Files.deleteIfExists(root.resolve(file));
            Files.deleteIfExists(root.resolve(DATABASE).getParent());
            Files.delete(root);
        } catch (IOException cleanup) {
            WMLogger.warn("Could not remove the temporary seed index " + root, cleanup);
        }
    }
}
