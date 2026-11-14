package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.download.LocalWorldIdentity;
import io.github.billstark001.worldmirror.download.WorldGraph;
import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Role;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.MirrorSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.PendingImport;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.download.WorldGraphWorlds;
import io.github.billstark001.worldmirror.format.WorldLineage;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Claims an old per-local-world route for an explicitly chosen, stable source. */
public final class LegacyLocalRouteAdoption {
    private static final String JOURNAL = ".world-graph-local-adoption.json";

    public record PendingRoute(String key, PendingImport evidence) {
        public String oldName() {
            return evidence.legacy().sourceId().substring("local:".length());
        }
        public Location destination() {
            return evidence.location();
        }
    }

    record Plan(PendingRoute pending, Location source, String sourceId, Node target) {
        Plan {
            Objects.requireNonNull(source);
            if (pending == null || !LegacyGraphImport.isLocalEntry(pending.key(), pending.evidence())
                    || WorldLineage.kindOfId(sourceId) != WorldLineage.Kind.LOCAL)
                throw new IllegalArgumentException("Invalid local route adoption journal");
            if (target != null
                    && (!target.location().equals(pending.destination()) || target.kind() != Kind.MIRROR
                            || target.role() != Role.SYNC || target.location().equals(source)))
                throw new IllegalArgumentException("Invalid local route destination in adoption journal");
        }
    }

    private final Path journal;
    private final WorldGraphWorlds worlds;

    public LegacyLocalRouteAdoption(Path configDirectory, WorldGraphWorlds worlds) {
        journal = configDirectory.resolve(JOURNAL);
        this.worlds = worlds;
    }

    public static List<PendingRoute> pending(WorldGraphConfig.Snapshot config) {
        return config.pendingImports()
                .entrySet()
                .stream()
                .filter(entry -> LegacyGraphImport.isLocalEntry(entry.getKey(), entry.getValue()))
                .map(entry -> new PendingRoute(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Caller holds the graph lease and supplies the current inventory and config. */
    public void adopt(WorldGraphConfig store, WorldGraph.Snapshot graph, PendingRoute expected,
            Location source, String expectedId) throws IOException {
        requirePending(store.snapshot(), expected);
        Node target = targetAtRecordedLocation(graph, expected, expectedId);
        Path sourceFolder = worlds.localSourcePath(source);
        if (target != null && source.equals(target.location()))
            throw new IOException("A world cannot be its own upstream");
        try (var sourceLock = WorldWriteLock.acquireDirectory(sourceFolder)) {
            validateLocalSource(graph, source, sourceFolder, expectedId);
            adoptTarget(store, expected, source, sourceFolder, target);
        }
    }

    public void discard(WorldGraphConfig store, PendingRoute expected) throws IOException {
        var before = store.snapshot();
        requirePending(before, expected);
        var pending = new HashMap<>(before.pendingImports());
        pending.remove(expected.key());
        replace(store, before, before.sourceSettings(), before.mirrorSettings(), pending);
    }

    private static void requirePending(WorldGraphConfig.Snapshot config, PendingRoute expected)
            throws IOException {
        if (expected == null || !LegacyGraphImport.isLocalEntry(expected.key(), expected.evidence())
                || !expected.evidence().equals(config.pendingImports().get(expected.key())))
            throw new IOException("The old local route changed. Refresh the world routes.");
    }

    private void validateLocalSource(
            WorldGraph.Snapshot graph, Location source, Path folder, String expectedId) throws IOException {
        worlds.requireUsableLocalSource(source);
        if (!Objects.equals(expectedId, LocalWorldIdentity.read(folder)))
            throw new IOException("The local world identity changed. Refresh the world routes.");
        if (expectedId != null && !graph.hasUniqueLocation(expectedId))
            throw new IOException("The local world identity is ambiguous");
    }

    private Node targetAtRecordedLocation(WorldGraph.Snapshot graph, PendingRoute pending, String sourceId)
            throws IOException {
        Location location = pending.destination();
        if (location == null || Files.notExists(worlds.path(location), LinkOption.NOFOLLOW_LINKS))
            return null;
        for (Node node : graph.nodes().values()) {
            if (location.equals(node.location()) && node.kind() == Kind.MIRROR && node.role() == Role.SYNC
                    && graph.hasUniqueLocation(node.id()) && graph.isUnambiguous(node.id())
                    && (sourceId == null || !graph.wouldCreateCycle(node.id(), sourceId)))
                return node;
        }
        throw new IOException(
                "The old destination exists but a unique download mirror could not be verified");
    }

    private void adoptTarget(WorldGraphConfig store, PendingRoute pending, Location source, Path sourceFolder,
            Node target) throws IOException {
        Path targetFolder = target == null ? null : worlds.checkedPath(target.location());
        try (var targetLock = targetFolder == null ? null : WorldWriteLock.acquire(targetFolder)) {
            if (target != null) {
                String sourceId = LocalWorldIdentity.read(sourceFolder);
                requireOriginalParent(pending, target, sourceId);
                requireOwner(pending, targetFolder, target);
            }
            var plan = new Plan(pending, source, LocalWorldIdentity.ensure(sourceFolder), target);
            JsonSupport.compareAndWriteDocument(journal, plan, null);
            finish(store, plan);
        }
    }

    /** Runs under the graph lease before publishing a refreshed inventory. */
    public void recover(WorldGraphConfig store) throws IOException {
        var document = JsonSupport.readDocument(journal, Plan.class);
        if (document.state() == JsonSupport.ReadState.MISSING)
            return;
        if (document.value() == null)
            throw new IOException("Cannot recover the local route adoption", document.failure());
        Plan plan = document.value();
        if (!store.snapshot().pendingImports().containsKey(plan.pending().key())) {
            Files.delete(journal);
            return;
        }
        Path source = worlds.localSourcePath(plan.source());
        if (!plan.sourceId().equals(LocalWorldIdentity.read(source)))
            throw new IOException("The local identity changed during route recovery");
        Path target = plan.target() == null ? null : worlds.checkedPath(plan.target().location());
        try (var ignored = target == null ? null : WorldWriteLock.acquire(target)) {
            finish(store, plan);
        }
    }

    private void finish(WorldGraphConfig store, Plan plan) throws IOException {
        var before = store.snapshot();
        requirePending(before, plan.pending());
        var target = plan.target();
        if (target != null)
            finishEdge(plan, target);
        publish(store, plan, before);
        Files.delete(journal);
    }

    private void finishEdge(Plan plan, Node target) throws IOException {
        Path folder = worlds.checkedPath(target.location());
        requireOriginalParent(plan.pending(), target, plan.sourceId());
        var finished =
                new Node(target.id(), plan.sourceId(), target.location(), target.kind(), target.role());
        try {
            requireOwner(plan.pending(), folder, finished);
            return;
        } catch (IOException notYetWritten) {
            requireOwner(plan.pending(), folder, target);
        }
        WorldGraphWorlds.changeParentOnDisk(folder, target, plan.sourceId());
    }

    private static void requireOriginalParent(PendingRoute pending, Node target, String adopted)
            throws IOException {
        String oldParent =
                LegacySourceIds.legacyParent(pending.evidence().legacy().sourceId(), Map.of(), Map.of());
        if (!oldParent.equals(target.parentId()) && (adopted == null || !adopted.equals(target.parentId())))
            throw new IOException("The old destination now belongs to a different upstream");
    }

    private static void requireOwner(PendingRoute pending, Path folder, Node node) throws IOException {
        String owner = WorldGraphWorlds.verifiedIndexSource(folder, node);
        if (!pending.evidence().legacy().sourceId().equals(owner))
            throw new IOException("The old destination's chunk index namespace does not match the route");
    }

    private static void publish(WorldGraphConfig store, Plan plan, WorldGraphConfig.Snapshot before)
            throws IOException {
        var entry = plan.pending().evidence().legacy();
        var target = plan.target();
        var sources = new HashMap<>(before.sourceSettings());
        var old = sources.get(plan.sourceId());
        var source = sources.getOrDefault(plan.sourceId(), SourceSettings.EMPTY);
        String pin = old == null && target != null ? target.id() : source.pinnedMirrorId();
        sources.put(plan.sourceId(),
                new SourceSettings(pin,
                        source.saveLocation() == null ? entry.saveLocation() : source.saveLocation(),
                        source.preferredFolderName() == null ? entry.preferredFolderName()
                                                             : source.preferredFolderName(),
                        source.conflictStrategy() == null && target == null ? entry.conflictStrategy()
                                                                            : source.conflictStrategy(),
                        source.lastAutomaticMirrorId()));
        var mirrors = new HashMap<>(before.mirrorSettings());
        var pending = new HashMap<>(before.pendingImports());
        pending.remove(plan.pending().key());
        if (target != null) {
            pending.remove("upstream:" + target.id());
            if (entry.conflictStrategy() != null)
                mirrors.putIfAbsent(target.id(), new MirrorSettings(entry.conflictStrategy()));
        }
        replace(store, before, sources, mirrors, pending);
    }

    private static void replace(WorldGraphConfig store, WorldGraphConfig.Snapshot before,
            Map<String, SourceSettings> sources, Map<String, MirrorSettings> mirrors,
            Map<String, PendingImport> pending) throws IOException {
        var next = new WorldGraphConfig.Snapshot(before.schema(), before.nodes(), before.locations(), sources,
                mirrors, before.endpointRules(), before.stableBindings(), pending);
        if (!store.replace(before, next))
            throw new IOException("World graph changed before publication");
    }
}
