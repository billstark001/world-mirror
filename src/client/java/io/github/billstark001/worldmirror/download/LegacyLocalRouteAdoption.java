package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Role;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.MirrorSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.PendingImport;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.io.NbtReadSupport;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.legacy.LegacyGraphImport;
import io.github.billstark001.worldmirror.legacy.LegacySourceIds;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
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
                    || !WorldGraph.isNodeId(sourceId) || !sourceId.startsWith("local:"))
                throw new IllegalArgumentException("Invalid local route adoption journal");
            if (target != null
                    && (!target.location().equals(pending.destination()) || target.kind() != Kind.MIRROR
                            || target.role() != Role.SYNC || target.location().equals(source)))
                throw new IllegalArgumentException("Invalid local route destination in adoption journal");
        }
    }

    private final WorldGraphService service;
    private final WorldInventoryFolders folders;

    LegacyLocalRouteAdoption(WorldGraphService service, WorldInventoryFolders folders) {
        this.service = service;
        this.folders = folders;
    }

    public static List<PendingRoute> pending(WorldGraphConfig.Snapshot config) {
        return config.pendingImports()
                .entrySet()
                .stream()
                .filter(entry -> LegacyGraphImport.isLocalEntry(entry.getKey(), entry.getValue()))
                .map(entry -> new PendingRoute(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** The picker supplies the observed identifier, if any, so a different identity cannot be adopted. */
    public void adopt(PendingRoute expected, Location source, String expectedId) throws IOException {
        synchronized (service) {
            requireIdle();
            try (var ignored = service.acquireGraphLease()) {
                service.refreshPublished();
                var config = service.configSnapshot();
                requirePending(config, expected);
                Node target = targetAtRecordedLocation(expected, expectedId);
                Path sourceFolder = folders.localOriginal(source);
                if (target != null && source.equals(target.location()))
                    throw new IOException("A world cannot be its own upstream");
                try (var sourceLock = WorldWriteLock.acquireDirectory(sourceFolder)) {
                    validateLocalSource(source, sourceFolder, expectedId);
                    adoptTarget(expected, source, sourceFolder, target);
                }
                service.refreshPublished();
            }
        }
    }

    public void discard(PendingRoute expected) throws IOException {
        synchronized (service) {
            requireIdle();
            try (var ignored = service.acquireGraphLease()) {
                service.refreshPublished();
                var before = service.configSnapshot();
                requirePending(before, expected);
                var pending = new HashMap<>(before.pendingImports());
                pending.remove(expected.key());
                service.publishConfig(before, before.nodes(), before.sourceSettings(),
                        before.mirrorSettings(), before.endpointRules(), pending);
            }
        }
    }

    private void requireIdle() throws IOException {
        if (service.clientService() && DownloadManager.hasRoutingWork())
            throw new IOException("Stop capture and wait for pending exports before importing old routes");
    }

    private static void requirePending(WorldGraphConfig.Snapshot config, PendingRoute expected)
            throws IOException {
        if (expected == null || !LegacyGraphImport.isLocalEntry(expected.key(), expected.evidence())
                || !expected.evidence().equals(config.pendingImports().get(expected.key())))
            throw new IOException("The old local route changed. Refresh the world routes.");
    }

    private void validateLocalSource(Location source, Path folder, String expectedId) throws IOException {
        folders.localOriginal(source);
        if (!Objects.equals(expectedId, LocalWorldIdentity.read(folder)))
            throw new IOException("The local world identity changed. Refresh the world routes.");
        if (expectedId != null && !service.graph().hasUniqueLocation(expectedId))
            throw new IOException("The local world identity is ambiguous");
        if (WorldRelocation.journal(folder) != null || MirrorMigrationCoordinator.needsRecovery(folder)
                || NbtReadSupport.readWorldData(folder.resolve("level.dat")).getCompound("Data").isEmpty())
            throw new IOException("The local source requires recovery or has invalid level data");
    }

    private Node targetAtRecordedLocation(PendingRoute pending, String sourceId) throws IOException {
        Location location = pending.destination();
        if (location == null
                || Files.notExists(service.worlds().path(location), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return null;
        var graph = service.graph();
        for (Node node : graph.nodes().values()) {
            if (location.equals(node.location()) && node.kind() == Kind.MIRROR && node.role() == Role.SYNC
                    && graph.hasUniqueLocation(node.id()) && graph.isUnambiguous(node.id())
                    && (sourceId == null || !graph.wouldCreateCycle(node.id(), sourceId)))
                return node;
        }
        throw new IOException(
                "The old destination exists but a unique download mirror could not be verified");
    }

    private void adoptTarget(PendingRoute pending, Location source, Path sourceFolder, Node target)
            throws IOException {
        Path targetFolder = targetFolder(target);
        try (var targetLock = targetFolder == null ? null : WorldWriteLock.acquire(targetFolder)) {
            if (target != null) {
                String sourceId = LocalWorldIdentity.read(sourceFolder);
                requireOriginalParent(pending, target, sourceId);
                requireOwner(pending, targetFolder, target);
            }
            var plan = new Plan(pending, source, LocalWorldIdentity.ensure(sourceFolder), target);
            JsonSupport.compareAndWriteDocument(journal(), plan, null);
            finish(plan);
        }
    }

    /** Runs under the graph lease before publishing a refreshed inventory. */
    void recover() throws IOException {
        var document = JsonSupport.readDocument(journal(), Plan.class);
        if (document.state() == JsonSupport.ReadState.MISSING)
            return;
        if (document.value() == null)
            throw new IOException("Cannot recover the local route adoption", document.failure());
        Plan plan = document.value();
        if (!service.currentStore().snapshot().pendingImports().containsKey(plan.pending().key())) {
            Files.delete(journal());
            return;
        }
        Path source = folders.localOriginal(plan.source());
        if (!plan.sourceId().equals(LocalWorldIdentity.read(source)))
            throw new IOException("The local identity changed during route recovery");
        Path target = targetFolder(plan.target());
        try (var ignored = target == null ? null : WorldWriteLock.acquire(target)) {
            finish(plan);
        }
    }

    private Path journal() {
        return service.configDirectory().resolve(JOURNAL);
    }

    private void finish(Plan plan) throws IOException {
        var before = service.currentStore().snapshot();
        requirePending(before, plan.pending());
        var target = plan.target();
        if (target != null)
            finishEdge(plan, target);
        publish(plan, before);
        Files.delete(journal());
    }

    private void finishEdge(Plan plan, Node target) throws IOException {
        Path folder = targetFolder(target);
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

    private Path targetFolder(Node target) throws IOException {
        if (target == null)
            return null;
        Path path = service.worlds().path(target.location());
        WorldInventoryFolders.requirePlainRoot(path.getParent());
        WorldInventoryFolders.requirePlainRoot(path);
        return path;
    }

    private static void requireOriginalParent(PendingRoute pending, Node target, String adopted)
            throws IOException {
        String oldParent =
                LegacySourceIds.legacyParent(pending.evidence().legacy().sourceId(), Map.of(), Map.of());
        if (!oldParent.equals(target.parentId()) && (adopted == null || !adopted.equals(target.parentId())))
            throw new IOException("The old destination now belongs to a different upstream");
    }

    private static void requireOwner(PendingRoute pending, Path folder, Node node) throws IOException {
        WorldMetadata metadata = WorldGraphWorlds.verifyUpstreamOwner(folder, node);
        var claim = metadata == null ? MirrorClaim.read(folder) : null;
        String owner = metadata == null ? claim == null ? null : claim.sourceId() : metadata.sourceId;
        if (!pending.evidence().legacy().sourceId().equals(owner))
            throw new IOException("The old destination's chunk index namespace does not match the route");
    }

    private void publish(Plan plan, WorldGraphConfig.Snapshot before) throws IOException {
        var entry = plan.pending().evidence().legacy();
        var target = plan.target();
        var sources = new HashMap<>(before.sourceSettings());
        var old = sources.get(plan.sourceId());
        var source = WorldGraphService.settings(sources, plan.sourceId());
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
        service.publishConfig(before, before.nodes(), sources, mirrors, before.endpointRules(), pending);
    }
}
