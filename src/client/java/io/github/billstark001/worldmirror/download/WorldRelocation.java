package io.github.billstark001.worldmirror.download;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WorldRecovery;
import net.minecraft.util.DirectoryLock;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Recoverable same-filesystem moves; both native session lock files remain stationary on Windows. */
final class WorldRelocation {
    static final String JOURNAL = ".worldmirror-relocation.json";
    private static final int CURRENT_SCHEMA = 2;
    private static final Gson JSON = new Gson();
    private record Plan(int schema, UUID operation, String nodeId, String parentNodeId, String sourceId,
                        String source, String target, List<String> entries) {}
    private record LegacyPlan(int schema, UUID operation, String sourceId, String mirrorId,
                              String source, String target, List<String> entries) {}
    record Journal(String nodeId, String legacySourceId, Path source, Path target) {}
    private WorldRelocation() {}

    static boolean isEmptyReservation(Path folder, String nodeId) throws IOException {
        FileProof.requirePlainDirectory(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)
                || !MirrorClaim.ownedBy(folder, nodeId)) return false;
        try (var entries = Files.list(folder)) {
            return entries.allMatch(path -> path.getFileName().toString().equals(MirrorClaim.FILE_NAME)
                    || path.getFileName().toString().equals(DirectoryLock.LOCK_FILE));
        }
    }

    static void move(Path source, Path target, WorldGraphService.Target expected, BooleanSupplier persistRouting) throws IOException {
        source = source.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        if (source.startsWith(target) || target.startsWith(source)) throw new IOException("Overlapping relocation roots");
        if (!expected.folder().equals(source)) throw new IOException("Relocation source does not match its binding");
        String nodeId = expected.nodeId();
        requirePlainDirectory(source);
        FileProof.requirePlainDirectory(target.getParent());
        FileProof.requirePlainDirectory(target);
        boolean routed = false;
        boolean created = false;
        boolean standalone = false;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(source)) {
            WorldMetadata metadata = WorldMetadata.loadIfPresent(source)
                    .filter(value -> nodeId.equals(value.nodeId) && expected.sourceId().equals(value.sourceId)
                            && expected.parentNodeId().equals(value.parentNodeId))
                    .orElseThrow(() -> new IOException("Source identity changed before move"));
            standalone = metadata.parentNodeId == null;
            Files.createDirectories(target.getParent());
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(target); created = true; }
            else if (!isEmptyReservation(target, nodeId)) throw new IOException("Relocation target is occupied");
            if (!Files.getFileStore(source).equals(Files.getFileStore(target))) {
                throw new IOException("Relocation requires the same filesystem; no copy-and-delete fallback");
            }
            try (DirectoryLock targetLease = WorldWriteLock.acquireDirectory(target)) {
                if (!Files.notExists(source.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS)
                        || !Files.notExists(target.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("A pending relocation must be recovered before another move");
                }
                List<String> entries;
                try (var files = Files.list(source)) {
                    entries = files.map(path -> path.getFileName().toString())
                            .filter(name -> !operational(name)).sorted().toList();
                }
                Plan plan = new Plan(CURRENT_SCHEMA, UUID.randomUUID(), nodeId, metadata.parentNodeId, metadata.sourceId,
                        source.toString(), target.toString(), entries);
                preflight(plan, source, target);
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
                    OperationFailures.report(nodeId, "relocation_cleanup", target, failure);
                }
            }
        } finally {
            if (!routed && created) removeUnusedTarget(target);
        }
        if (routed && standalone) removeUnusedTarget(source);
    }

    /** Routing is the commit decision; preflight validates both identities and the whole move set. */
    static void recover(Path journalRoot, Path routed, Set<Path> allowedRoots) throws IOException {
        journalRoot = journalRoot.toAbsolutePath().normalize();
        Path journal = journalRoot.resolve(JOURNAL);
        if (Files.notExists(journal, LinkOption.NOFOLLOW_LINKS)) return;
        requirePlainDirectory(journalRoot);
        Plan plan = readPlan(journal);
        Journal info = journalFor(plan);
        Path source = info.source(), target = info.target();
        if (!allowedRoots.contains(source.getParent()) || !allowedRoots.contains(target.getParent())
                || source.startsWith(target) || target.startsWith(source)
                || !(journalRoot.equals(source) || journalRoot.equals(target))) throw new IOException("Invalid relocation paths");
        if (routed == null) throw new IOException("No durable routing for the pending relocation");
        boolean committed = routed.toAbsolutePath().normalize().equals(target);
        if (!committed && !routed.toAbsolutePath().normalize().equals(source)) {
            throw new IOException("Routing no longer matches the pending relocation");
        }
        // Never run even generic world recovery inside a foreign root before establishing ownership.
        preflight(plan, source, target);
        try (WorldWriteLock ignored = WorldWriteLock.acquire(source);
             DirectoryLock targetLease = WorldWriteLock.acquireDirectory(target)) {
            if (Files.notExists(source.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS)
                    && Files.notExists(target.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS)) return;
            transfer(plan, committed ? source : target, committed ? target : source);
            finish(plan, source, target, committed);
        }
        if (!committed) removeUnusedTarget(target);
        else if (plan.schema() == 1 || plan.parentNodeId() == null) removeUnusedTarget(source);
    }

    static Journal journal(Path folder) throws IOException {
        Path file = folder.resolve(JOURNAL);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        requirePlainDirectory(folder);
        return journalFor(readPlan(file));
    }

    private static Journal journalFor(Plan plan) throws IOException {
        try {
            Path source = Path.of(plan.source()), target = Path.of(plan.target());
            if (!source.isAbsolute() || !target.isAbsolute()) throw new IOException("Relative relocation paths");
            return new Journal(plan.nodeId(), plan.schema() == 1 ? plan.sourceId() : null, source.normalize(), target.normalize());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid relocation paths", invalid);
        }
    }

    private static Plan readPlan(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unreadable relocation journal: " + file);
        var document = JsonSupport.readDocument(file, JsonObject.class);
        if (document.state() != JsonSupport.ReadState.VALID) {
            throw new IOException("Unreadable relocation journal: " + file, document.failure());
        }
        Plan plan;
        try {
            JsonObject object = document.value();
            var version = object.get("schema");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Missing numeric relocation journal schema");
            }
            int schema = new BigDecimal(version.getAsString()).intValueExact();
            if (schema == 1) {
                LegacyPlan old = JSON.fromJson(object, LegacyPlan.class);
                if (old.mirrorId() == null || old.mirrorId().isBlank()) throw new IOException("Missing legacy mirror ID");
                plan = new Plan(schema, old.operation(), "mirror:" + old.mirrorId(), null, old.sourceId(),
                        old.source(), old.target(), old.entries());
            } else if (schema == CURRENT_SCHEMA) {
                plan = JSON.fromJson(object, Plan.class);
            } else {
                throw new IOException("Unsupported relocation journal schema: " + schema);
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid relocation journal: " + file, invalid);
        }
        if (plan.operation() == null || plan.nodeId() == null || plan.nodeId().isBlank()
                || plan.sourceId() == null || plan.sourceId().isBlank()) throw new IOException("Invalid relocation identity");
        return plan;
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
            requirePlainDirectory(root);
            Path journal = root.resolve(JOURNAL);
            if (!Files.notExists(journal, LinkOption.NOFOLLOW_LINKS) && !plan.equals(readPlan(journal))) {
                throw new IOException("Conflicting relocation journals: " + root);
            }
            Path metadataFile = root.resolve(WorldMetadata.FILE_NAME);
            if (!Files.notExists(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
                if (!matchesMetadata(plan, root)) throw new IOException("Foreign mirror at relocation root: " + root);
                identities++;
            }
            if (!Files.notExists(root.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS) && !matchesClaim(plan, root)) {
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
            var attributes = Files.readAttributes(inSource ? first : second, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isOther() || attributes.isSymbolicLink() || (!attributes.isRegularFile() && !attributes.isDirectory())) {
                throw new IOException("Unsupported relocation entry: " + name);
            }
        }
    }

    private static void requirePlainDirectory(Path root) throws IOException {
        FileProof.requirePlainDirectory(root);
        if (!root.toRealPath().equals(root.toAbsolutePath().normalize())) {
            throw new IOException("Relocation root is not a plain directory: " + root);
        }
    }

    private static boolean matchesMetadata(Plan plan, Path root) throws IOException {
        if (!Files.isRegularFile(root.resolve(WorldMetadata.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) return false;
        if (plan.schema() == 1) {
            LegacyWorldMetadata metadata = LegacyWorldMetadata.read(root);
            return metadata != null && plan.sourceId().equals(metadata.sourceId())
                    && plan.nodeId().equals("mirror:" + metadata.mirrorId());
        }
        return WorldMetadata.loadIfPresent(root).filter(metadata -> plan.nodeId().equals(metadata.nodeId)
                && plan.sourceId().equals(metadata.sourceId)
                && Objects.equals(plan.parentNodeId(), metadata.parentNodeId)).isPresent();
    }

    private static boolean matchesClaim(Plan plan, Path root) throws IOException {
        if (!Files.isRegularFile(root.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) return false;
        if (plan.schema() == 1) return plan.sourceId().equals(MirrorClaim.readLegacySource(root));
        MirrorClaim.Claim claim = MirrorClaim.read(root);
        return claim != null && plan.nodeId().equals(claim.nodeId()) && plan.sourceId().equals(claim.sourceId())
                && Objects.equals(plan.parentNodeId(), claim.parentNodeId());
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
            if (plan.schema() == 1 || plan.parentNodeId() == null) {
                Files.deleteIfExists(source.resolve(MirrorClaim.FILE_NAME));
                Files.deleteIfExists(target.resolve(MirrorClaim.FILE_NAME));
            } else {
                if (Files.notExists(source.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                    MirrorClaim.create(source, plan.nodeId(), plan.parentNodeId(), plan.sourceId());
                }
                MirrorClaim.finish(target, plan.nodeId());
            }
        }
        Files.deleteIfExists(source.resolve(JOURNAL));
        Files.deleteIfExists(target.resolve(JOURNAL));
    }

    private static void removeUnusedTarget(Path target) {
        try {
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return;
            requirePlainDirectory(target);
            if (DirectoryLock.isLocked(target)) return;
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
