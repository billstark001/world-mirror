package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WorldRecovery;
import net.minecraft.util.DirectoryLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Recoverable same-filesystem moves; both native session lock files remain stationary on Windows. */
final class WorldRelocation {
    static final String JOURNAL = ".worldmirror-relocation.json";
    private record Plan(int schema, UUID operation, String sourceId, String mirrorId,
                        String source, String target, List<String> entries) {}
    private WorldRelocation() {}

    static boolean isEmptyReservation(Path folder, String sourceId) throws IOException {
        if (!MirrorClaim.ownedBy(folder, sourceId)) return false;
        try (var entries = Files.list(folder)) {
            return entries.allMatch(path -> path.getFileName().toString().equals(MirrorClaim.FILE_NAME)
                    || path.getFileName().toString().equals(DirectoryLock.LOCK_FILE));
        }
    }

    static void move(Path source, Path target, String sourceId, BooleanSupplier persistRouting) throws IOException {
        source = source.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        if (source.startsWith(target) || target.startsWith(source)) throw new IOException("Overlapping relocation roots");
        boolean routed = false;
        boolean created = false;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(source)) {
            WorldMetadata metadata = WorldMetadata.loadIfPresent(source)
                    .filter(value -> WorldMetadata.FORMAT.equals(value.format) && sourceId.equals(value.sourceId))
                    .orElseThrow(() -> new IOException("Source identity changed before move"));
            if (metadata.mirrorId == null || metadata.mirrorId.isBlank()) {
                metadata.ensureMirrorId();
                if (!metadata.save(source)) throw new IOException("Cannot establish legacy mirror identity");
            }
            Files.createDirectories(target.getParent());
            if (Files.notExists(target)) { Files.createDirectory(target); created = true; }
            else if (!isEmptyReservation(target, sourceId)) throw new IOException("Relocation target is occupied");
            if (!Files.getFileStore(source).equals(Files.getFileStore(target))) {
                throw new IOException("Relocation requires the same filesystem; no copy-and-delete fallback");
            }
            try (DirectoryLock targetLease = WorldWriteLock.acquireDirectory(target)) {
                if (!Files.notExists(source.resolve(JOURNAL)) || !Files.notExists(target.resolve(JOURNAL))) {
                    throw new IOException("A pending relocation must be recovered before another move");
                }
                List<String> entries;
                try (var files = Files.list(source)) {
                    entries = files.map(path -> path.getFileName().toString())
                            .filter(name -> !operational(name)).sorted().toList();
                }
                Plan plan = new Plan(1, UUID.randomUUID(), sourceId, metadata.mirrorId,
                        source.toString(), target.toString(), entries);
                JsonSupport.writeDocument(source.resolve(JOURNAL), plan);
                JsonSupport.writeDocument(target.resolve(JOURNAL), plan);
                try {
                    transfer(plan, source, target);
                    if (!persistRouting.getAsBoolean()) throw new IOException("Cannot persist relocated routing");
                    routed = true;
                    finish(plan, source, target, true);
                } catch (IOException | RuntimeException failure) {
                    if (!routed) {
                        try { transfer(plan, target, source); finish(plan, source, target, false); }
                        catch (IOException rollback) { failure.addSuppressed(rollback); }
                        throw failure;
                    }
                    // Routing is already durable. Cleanup failure cannot authorize an independent rollback.
                    OperationFailures.report(sourceId, "relocation_cleanup", target, failure);
                }
            }
        } finally {
            if (!routed && created) removeUnusedTarget(target);
        }
    }

    /** Routing is the commit decision; preflight validates both identities and the whole move set. */
    static void recover(Path journalRoot, String sourceId, Path routed, Set<Path> allowedRoots) throws IOException {
        journalRoot = journalRoot.toAbsolutePath().normalize();
        Path journal = journalRoot.resolve(JOURNAL);
        if (Files.notExists(journal)) return;
        Plan plan = readPlan(journal);
        if (plan.schema() != 1 || plan.operation() == null || !sourceId.equals(plan.sourceId())
                || plan.mirrorId() == null || plan.mirrorId().isBlank()) throw new IOException("Invalid relocation identity");
        Path source = Path.of(plan.source()).toAbsolutePath().normalize();
        Path target = Path.of(plan.target()).toAbsolutePath().normalize();
        if (!allowedRoots.contains(source.getParent()) || !allowedRoots.contains(target.getParent())
                || source.startsWith(target) || target.startsWith(source)
                || !(journalRoot.equals(source) || journalRoot.equals(target))) throw new IOException("Invalid relocation paths");
        boolean committed = routed.toAbsolutePath().normalize().equals(target);
        if (!committed && !routed.toAbsolutePath().normalize().equals(source)) {
            throw new IOException("Routing no longer matches the pending relocation");
        }
        // Never run even generic world recovery inside a foreign root before establishing ownership.
        preflight(plan, source, target);
        try (WorldWriteLock ignored = WorldWriteLock.acquire(source);
             DirectoryLock targetLease = WorldWriteLock.acquireDirectory(target)) {
            transfer(plan, committed ? source : target, committed ? target : source);
            finish(plan, source, target, committed);
        }
        if (!committed) removeUnusedTarget(target);
    }

    private static Plan readPlan(Path file) throws IOException {
        var read = JsonSupport.readDocument(file, Plan.class);
        if (read.state() != JsonSupport.ReadState.VALID) throw new IOException("Unreadable relocation journal: " + file, read.failure());
        return read.value();
    }

    private static boolean operational(String name) {
        return name.equals(DirectoryLock.LOCK_FILE) || name.equals(JOURNAL) || name.equals(MirrorClaim.FILE_NAME)
                || name.equals(WorldRecovery.MARKER) || name.startsWith(FileTransaction.PREFIX)
                || name.startsWith(FileTransaction.CLEANUP_PREFIX);
    }

    private static void preflight(Plan plan, Path source, Path target) throws IOException {
        if (plan.entries() == null || !plan.entries().contains(WorldMetadata.FILE_NAME)) throw new IOException("Missing relocation entries");
        Set<String> names = new HashSet<>();
        for (String name : plan.entries()) {
            if (name == null) throw new IOException("Null relocation entry");
            Path entry = Path.of(name);
            if (entry.isAbsolute() || entry.getNameCount() != 1 || name.equals(".") || name.equals("..")
                    || operational(name) || !names.add(name)) throw new IOException("Invalid relocation entry: " + name);
        }
        int identities = 0;
        for (Path root : List.of(source, target)) {
            var attributes = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) throw new IOException("Relocation root is not a plain directory: " + root);
            Path journal = root.resolve(JOURNAL);
            if (!Files.notExists(journal) && !plan.equals(readPlan(journal))) throw new IOException("Conflicting relocation journals: " + root);
            Path metadataFile = root.resolve(WorldMetadata.FILE_NAME);
            if (!Files.notExists(metadataFile)) {
                WorldMetadata metadata = WorldMetadata.loadIfPresent(root).orElseThrow(() -> new IOException("Unreadable relocation identity: " + root));
                if (!WorldMetadata.FORMAT.equals(metadata.format) || !plan.sourceId().equals(metadata.sourceId)
                        || !plan.mirrorId().equals(metadata.mirrorId)) throw new IOException("Foreign mirror at relocation root: " + root);
                identities++;
            }
            if (!Files.notExists(root.resolve(MirrorClaim.FILE_NAME)) && !MirrorClaim.ownedBy(root, plan.sourceId())) {
                throw new IOException("Foreign relocation reservation: " + root);
            }
            try (var files = Files.list(root)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.getFileName().toString();
                    if (!names.contains(name) && !operational(name)) throw new IOException("Unexpected relocation data: " + file);
                }
            }
        }
        if (identities != 1) throw new IOException("Cannot uniquely establish the moving mirror identity");
        // No entry is moved until every later entry has passed. Moves never replace a destination.
        for (String name : names) {
            Path first = source.resolve(name), second = target.resolve(name);
            boolean inSource = !Files.notExists(first, LinkOption.NOFOLLOW_LINKS);
            boolean inTarget = !Files.notExists(second, LinkOption.NOFOLLOW_LINKS);
            if (inSource == inTarget) throw new IOException("Missing or ambiguous relocation data; copies retained: " + name);
            var attributes = Files.readAttributes(inSource ? first : second,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() && !attributes.isDirectory()) throw new IOException("Unsupported relocation entry: " + name);
        }
    }

    private static void transfer(Plan plan, Path from, Path to) throws IOException {
        preflight(plan, from, to);
        for (String name : plan.entries()) {
            Path original = from.resolve(name);
            if (!Files.notExists(original)) Files.move(original, to.resolve(name));
        }
    }

    private static void finish(Plan plan, Path source, Path target, boolean committed) throws IOException {
        if (committed) {
            MirrorClaim.create(source, plan.sourceId());
            MirrorClaim.finish(target, plan.sourceId());
        }
        Files.deleteIfExists(source.resolve(JOURNAL));
        Files.deleteIfExists(target.resolve(JOURNAL));
    }

    private static void removeUnusedTarget(Path target) {
        try {
            if (Files.notExists(target) || DirectoryLock.isLocked(target)) return;
            try (var entries = Files.list(target)) {
                if (entries.anyMatch(path -> !path.getFileName().toString().equals(DirectoryLock.LOCK_FILE))) return;
            }
            Files.deleteIfExists(target.resolve(DirectoryLock.LOCK_FILE));
            Files.deleteIfExists(target);
        } catch (IOException | RuntimeException retained) {
            // Retaining an uncertain target is safer than deleting it.
        }
    }
}
