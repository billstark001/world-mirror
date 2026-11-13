package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Role;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.MirrorSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.PendingImport;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.legacy.LegacyRouting.Entry;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** Coordinates the v0 routing import and preserves planned IDs until their saves can be migrated. */
public final class LegacyGraphImport {
    private static final String JOURNAL = ".world-graph-import.json";
    private static final String LEGACY_ROUTING = "legacy-routing:";

    private record Plan(String legacyDigest, Map<String, String> allocatedIds) {}

    private final Path input;
    private final Path journal;
    private final String digest;
    private final Plan plan;
    private final LegacyRouting routing;

    private LegacyGraphImport(Path input, Path journal, String digest, Plan plan, LegacyRouting routing) {
        this.input = input;
        this.journal = journal;
        this.digest = digest;
        this.plan = plan;
        this.routing = routing;
    }

    public static LegacyGraphImport open(Path directory, Map<String, PendingImport> pending,
                                         boolean currentConfigExists) throws IOException {
        Path input = directory.resolve(LegacyRouting.FILE_NAME);
        Path journal = directory.resolve(JOURNAL);
        boolean importing = !Files.notExists(input, LinkOption.NOFOLLOW_LINKS);
        if (importing && !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Legacy mirror mapping is not a regular file: " + input);
        }
        String digest = importing ? FileProof.sha256(input) : null;
        Map<String, Entry> entries = new HashMap<>();
        pending.forEach((key, value) -> {
            if (key.startsWith(LEGACY_ROUTING) && value.legacy() != null) entries.put(value.legacy().sourceId(), value.legacy());
        });
        if (importing) entries.putAll(LegacyRouting.read(input).entries());

        var saved = JsonSupport.readDocument(journal, Plan.class);
        if (saved.state() != JsonSupport.ReadState.MISSING && saved.value() == null) {
            throw new IOException("Invalid world graph import journal", saved.failure());
        }
        Plan plan = saved.value();
        if (plan != null && plan.allocatedIds() == null) throw new IOException("Invalid world graph import allocations");
        if (plan != null && !Objects.equals(plan.legacyDigest(), digest)) {
            if (!importing && currentConfigExists) plan = new Plan(null, plan.allocatedIds());
            else throw new IOException("Legacy mapping changed during import");
        }
        return new LegacyGraphImport(input, journal, digest, plan, new LegacyRouting(entries));
    }

    public LegacyRouting routing() { return routing; }

    public boolean hasLegacyFile() { return digest != null; }

    public Map<String, String> allocations() {
        return new HashMap<>(plan == null ? Map.of() : plan.allocatedIds());
    }

    public void pin(Map<String, String> allocations) throws IOException {
        if (!hasLegacyFile() && allocations.isEmpty()) return;
        var next = new Plan(digest, allocations);
        if (!next.equals(plan)) JsonSupport.writeDocument(journal, next);
    }

    public void verifyUnchanged() throws IOException {
        if (hasLegacyFile() && !digest.equals(FileProof.sha256(input))) throw new IOException("Legacy config changed during upgrade");
    }

    public void complete(Map<String, String> allocations, Set<String> pending) throws IOException {
        if (hasLegacyFile()) {
            // Keep the existing archive naming convention for interrupted imports of the v0 input.
            Path archive = input.resolveSibling("mirrors.v1-" + digest.substring(0, 12) + ".json");
            if (Files.notExists(archive, LinkOption.NOFOLLOW_LINKS)) Files.move(input, archive, StandardCopyOption.ATOMIC_MOVE);
            else if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) && digest.equals(FileProof.sha256(archive))) Files.delete(input);
            else throw new IOException("Legacy config archive does not match the input");
        }
        Map<String, String> remainingAllocations = new HashMap<>(allocations);
        remainingAllocations.keySet().retainAll(pending);
        if (remainingAllocations.isEmpty()) Files.deleteIfExists(journal);
        else {
            var remaining = new Plan(null, remainingAllocations);
            if (!remaining.equals(plan)) JsonSupport.writeDocument(journal, remaining);
        }
    }

    public void applyAffinity(Map<Location, String> owners, List<Node> observed, Map<String, Location> affinity,
                              Supplier<ModConfig.SaveLocation> defaultLocation) {
        for (Node node : observed) {
            if (node.location() == null) continue;
            String owner = owners.get(node.location());
            Entry entry = owner == null ? null : routing.entries().get(owner);
            if (entry != null && node.location().equals(entry.location(defaultLocation))) affinity.putIfAbsent(node.id(), node.location());
        }
    }

    public void applySettings(Map<String, String> remotes, Snapshot graph, Function<Location, String> ownerAt,
                              Function<Location, String> pendingKeyForLocation,
                              Map<String, SourceSettings> sources, Map<String, MirrorSettings> mirrors,
                              Map<String, PendingImport> pending, Supplier<ModConfig.SaveLocation> defaultLocation) {
        for (Entry entry : routing.entries().values()) {
            String parent = LegacySourceIds.legacyParent(entry.sourceId(), remotes, Map.of());
            Location expected = entry.location(defaultLocation);
            Node node = graph.nodes().values().stream().filter(value -> value.kind() == Kind.MIRROR
                    && value.role() == Role.SYNC && Objects.equals(value.location(), expected)
                    && parent.equals(value.parentId())).findFirst().orElse(null);
            if (node != null && !entry.sourceId().equals(ownerAt.apply(node.location()))) node = null;
            if (parent.startsWith("unresolved:")) {
                pending.put("legacy:" + entry.sourceId(), new PendingImport(entry, expected, "source_unresolved"));
                continue;
            }
            String pendingKey = LEGACY_ROUTING + entry.sourceId();
            if (node == null && expected != null && pending.containsKey(pendingKeyForLocation.apply(expected))) {
                pending.put(pendingKey, new PendingImport(entry, expected, "target_upgrade_deferred"));
                continue;
            }
            pending.remove(pendingKey);
            String targetId = node == null ? null : node.id();
            String folder = entry.preferredFolderName();
            SourceSettings previous = sources.getOrDefault(parent, new SourceSettings(null, null, null, null));
            if (previous.defaultMirrorId() == null) {
                sources.put(parent, new SourceSettings(targetId, entry.saveLocation(), folder,
                        node == null ? entry.conflictStrategy() : null));
            }
            if (node != null && entry.conflictStrategy() != null) {
                mirrors.putIfAbsent(node.id(), new MirrorSettings(entry.conflictStrategy()));
            }
        }
    }

}
