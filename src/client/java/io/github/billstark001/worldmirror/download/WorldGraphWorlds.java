package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.io.NbtReadSupport;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.github.billstark001.worldmirror.download.WorldGraphService.Target;
import io.github.billstark001.worldmirror.download.WorldGraphService.MoveResult;

/** Verifies and mutates managed saves under the world graph lease. */
public final class WorldGraphWorlds {
    private final WorldGraphService service;
    private final WorldInventoryFolders folders;

    WorldGraphWorlds(WorldGraphService service, WorldInventoryFolders folders) {
        this.service = service;
        this.folders = folders;
    }

    public void setUpstream(String nodeId, String parentId) throws IOException {
        setUpstream(nodeId, Objects.requireNonNull(parentId), null);
    }

    public void setUpstream(String nodeId, Location localSource) throws IOException {
        setUpstream(nodeId, null, Objects.requireNonNull(localSource));
    }

    public void removeUpstream(String nodeId) throws IOException {
        setUpstream(nodeId, null, null);
    }

    private void setUpstream(String nodeId, String parentId, Location localSource) throws IOException {
        synchronized (service) {
            requireIdle();
            try (var ignored = service.acquireGraphLease()) {
                service.refreshPublished();
                Snapshot graph = service.published().graph();
                Node child = requireManaged(nodeId);
                Path folder = path(child.location());
                if (localSource == null && Objects.equals(child.parentId(), parentId))
                    return;
                if (parentId != null) {
                    Node parent = graph.nodes().get(parentId);
                    if (parent == null || parent.kind() == WorldGraph.Kind.UNRESOLVED
                            || !graph.isUnambiguous(parentId) || graph.wouldCreateCycle(nodeId, parentId))
                        throw new IOException("This source cannot be the world's upstream");
                }
                try (var lock = WorldWriteLock.acquire(folder)) {
                    parentId = resolveLocalParent(folder, child, parentId, localSource);
                    changeParentOnDisk(folder, child, parentId);
                }
                WMLogger.info(
                        "World upstream " + (parentId == null ? "removed" : "changed") + " world=" + folder);
                var before = service.open().snapshot();
                var pending = new HashMap<>(before.pendingImports());
                pending.remove("upstream:" + nodeId);
                var sources = new HashMap<>(before.sourceSettings());
                var old = child.parentId() == null ? null : sources.get(child.parentId());
                if (old != null)
                    sources.put(child.parentId(), old.withoutTarget(nodeId));
                service.publishConfig(before, before.nodes(), sources, before.mirrorSettings(),
                        before.endpointRules(), pending);
                service.refreshPublished();
            }
        }
    }

    private void requireIdle() throws IOException {
        if (service.clientService() && DownloadManager.hasRoutingWork())
            throw new IOException("Stop capture and wait for pending exports before editing worlds");
    }

    private Node requireManaged(String nodeId) throws IOException {
        Node node = service.published().graph().nodes().get(nodeId);
        if (node == null || (node.kind() != WorldGraph.Kind.MIRROR && node.kind() != WorldGraph.Kind.SNAPSHOT)
                || !service.published().graph().hasUniqueLocation(nodeId))
            throw new IOException("This mirror or snapshot is unavailable");
        return node;
    }

    public Path path(Location location) {
        return folders.root(location.root()).resolve(location.folder());
    }

    /** Reads the index namespace only after verifying the current graph edge against the save. */
    public static String verifiedIndexSource(Path folder, Node child) throws IOException {
        WorldMetadata metadata = verifyUpstreamOwner(folder, child);
        if (metadata != null)
            return metadata.sourceId;
        var claim = MirrorClaim.read(folder);
        return claim == null ? null : claim.sourceId();
    }

    public Path checkedPath(Location location) throws IOException {
        Path folder = path(location);
        WorldInventoryFolders.requirePlainRoot(folder.getParent());
        WorldInventoryFolders.requirePlainRoot(folder);
        return folder;
    }

    public Path localSourcePath(Location location) throws IOException {
        return folders.localOriginal(location);
    }

    /** Caller holds the save lease when using the result to write an identity. */
    public void requireUsableLocalSource(Location location) throws IOException {
        Path folder = localSourcePath(location);
        if (WorldRelocation.journal(folder) != null || MirrorMigrationCoordinator.needsRecovery(folder)
                || NbtReadSupport.readWorldData(folder.resolve("level.dat")).getCompound("Data").isEmpty())
            throw new IOException("The selected local save cannot be used as a parent");
    }

    private static WorldMetadata verifyUpstreamOwner(Path folder, Node child) throws IOException {
        WorldMetadata metadata = WorldMetadata.loadIfPresent(folder).orElse(null);
        if (metadata == null && WorldMetadata.inspectSchema(folder) == WorldMetadata.Schema.MISSING
                && WorldRelocation.isEmptyReservation(folder, child.id())) {
            var claim = MirrorClaim.read(folder);
            if (claim != null && Objects.equals(claim.parentNodeId(), child.parentId()))
                return null;
        }
        if (metadata == null || !child.id().equals(metadata.nodeId)
                || !Objects.equals(child.parentId(), metadata.parentNodeId) || child.kind() != metadata.kind
                || child.role() != metadata.role) {
            throw new IOException("World identity changed: " + folder);
        }
        return metadata;
    }

    /** Changes the parent of a verified child while the caller holds its save lease. */
    public static void changeParentOnDisk(Path folder, Node child, String parentId) throws IOException {
        Path file = folder.resolve(WorldMetadata.FILE_NAME);
        FileProof.Version stamp =
                Files.notExists(file, LinkOption.NOFOLLOW_LINKS) ? null : FileProof.version(file);
        WorldMetadata metadata = verifyUpstreamOwner(folder, child);
        if (metadata == null) {
            MirrorClaim.changeParent(folder, child.id(), child.parentId(), parentId);
            return;
        }
        metadata.parentNodeId = parentId;
        JsonSupport.compareAndWriteDocument(file, metadata, stamp);
    }

    private void retireOtherReservations(Node node, Path owner) throws IOException {
        var metadata = WorldMetadata.loadIfPresent(owner).orElse(null);
        if (metadata == null)
            return;
        for (Root root : Root.values()) {
            Path base = folders.root(root);
            if (Files.notExists(base, LinkOption.NOFOLLOW_LINKS))
                continue;
            WorldInventoryFolders.requirePlainRoot(base);
            try (var entries = Files.list(base)) {
                for (Path folder : entries.toList())
                    retireReservation(node, owner, metadata.sourceId, folder);
            }
        }
    }

    private static void retireReservation(Node node, Path owner, String indexSource, Path folder)
            throws IOException {
        if (folder.equals(owner) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(folder))
            return;
        try {
            WorldInventoryFolders.requirePlainRoot(folder);
        } catch (IOException unavailable) {
            return;
        }
        var claim = MirrorClaim.read(folder);
        if (claim == null || !node.id().equals(claim.nodeId()) || !indexSource.equals(claim.sourceId()))
            return;
        try (var lock = WorldWriteLock.acquire(folder)) {
            if (claim.equals(MirrorClaim.read(folder))
                    && WorldRelocation.isEmptyReservation(folder, node.id()))
                Files.delete(folder.resolve(MirrorClaim.FILE_NAME));
        }
    }

    private String resolveLocalParent(Path folder, Node child, String parentId, Location localSource)
            throws IOException {
        if (localSource == null)
            return parentId;
        if (localSource.equals(child.location()))
            throw new IOException("A world cannot be its own upstream");
        verifyUpstreamOwner(folder, child);
        return identifyLocalSource(localSource);
    }

    private String identifyLocalSource(Location location) throws IOException {
        if (!service.published().untrackedLocalWorlds().contains(location))
            throw new IOException("The selected local save is no longer available");
        Path folder = folders.localOriginal(location);
        try (var ignored = WorldWriteLock.acquireDirectory(folder)) {
            requireUsableLocalSource(location);
            if (LocalWorldIdentity.read(folder) != null)
                throw new IOException("The local save acquired an identity. Refresh the world list.");
            return LocalWorldIdentity.ensure(folder);
        }
    }

    MoveResult move(Target target, ModConfig.SaveLocation location) throws IOException {
        synchronized (service) {
            try (var ignored = service.acquireGraphLease()) {
                service.refreshPublished();
                Target current = service.published().targets().get(target.nodeId());
                if (current == null || !current.parentNodeId().equals(target.parentNodeId())
                        || !current.sourceId().equals(target.sourceId()))
                    throw new IOException("Mirror unavailable");
                return moveManaged(requireManaged(current.nodeId()), current.sourceId(), location);
            }
        }
    }

    public void moveWorld(String nodeId, ModConfig.SaveLocation location) throws IOException {
        synchronized (service) {
            requireIdle();
            try (var ignored = service.acquireGraphLease()) {
                service.refreshPublished();
                Node node = requireManaged(nodeId);
                Path source = path(node.location());
                var metadata = verifyUpstreamOwner(source, node);
                var claim = metadata == null ? MirrorClaim.read(source) : null;
                if (metadata == null && claim == null)
                    throw new IOException("The mirror reservation changed");
                moveManaged(node, metadata == null ? claim.sourceId() : metadata.sourceId, location);
            }
        }
    }

    private MoveResult moveManaged(Node node, String indexSource, ModConfig.SaveLocation location)
            throws IOException {
        Path base = service.rootFor(location);
        Files.createDirectories(base);
        WorldInventoryFolders.requirePlainRoot(base);
        Path from = path(node.location());
        if (from.getParent().equals(base))
            return new MoveResult(from, from);
        Path desired = base.resolve(from.getFileName());
        Path destination = WorldMetadata.loadIfPresent(from).isPresent()
                        && WorldRelocation.isEmptyReservation(desired, node.id())
                ? desired
                : MirrorFolders.available(base, from.getFileName().toString());
        WorldGraphConfig.Snapshot before = service.open().snapshot();
        WorldRelocation.move(from, destination, node.id(), node.parentId(), indexSource, () -> {
            Node existing = before.nodes().get(node.id());
            if (existing == null)
                return false;
            Map<String, Node> nodes = new HashMap<>(before.nodes());
            nodes.put(existing.id(),
                    new Node(existing.id(), existing.parentId(), folders.locationOf(destination),
                            existing.kind(), existing.role()));
            Map<String, Location> locations = new HashMap<>(before.locations());
            locations.put(existing.id(), folders.locationOf(destination));
            try {
                service.publishConfig(before, nodes, locations, before.sourceSettings(),
                        before.mirrorSettings(), before.endpointRules(), before.pendingImports());
                return true;
            } catch (IOException failure) {
                WMLogger.warn("Could not publish relocated world", failure);
                return false;
            }
        });
        WMLogger.info("World relocated source=" + from + " target=" + destination);
        service.refreshPublished();
        return new MoveResult(from, destination);
    }

    public void deleteWorld(String nodeId, Location confirmedLocation) throws Exception {
        synchronized (service) {
            requireIdle();
            try (var ignored = service.acquireGraphLease()) {
                service.refreshPublished();
                Node node = service.published().graph().nodes().get(nodeId);
                if (node == null || !service.published().graph().hasUniqueLocation(nodeId))
                    throw new IOException("This world is unavailable");
                if (!node.location().equals(confirmedLocation))
                    throw new IOException("The world moved after deletion was confirmed");
                Path folder = path(confirmedLocation);
                WorldInventoryFolders.requirePlainRoot(folder.getParent());
                FileProof.requirePlainDirectory(folder);
                FileProof.requirePlainFileIfPresent(
                        folder.resolve(net.minecraft.util.DirectoryLock.LOCK_FILE));
                if (net.minecraft.util.DirectoryLock.isLocked(folder))
                    throw new IOException("Close the world before deleting it");
                try (var access = LevelStorageSource.createDefault(folder.getParent())
                                .validateAndCreateAccess(confirmedLocation.folder())) {
                    if (io.github.billstark001.worldmirror.util.WorldRecovery.required(folder)
                            || WorldRelocation.journal(folder) != null)
                        throw new IOException("Complete pending world recovery before deleting it");
                    verifyWorldForDeletion(node, confirmedLocation, folder);
                    access.deleteLevel();
                }
                WMLogger.info("World deleted world=" + folder + " type=" + node.kind());
                service.refreshPublished();
            }
        }
    }

    private void verifyWorldForDeletion(Node node, Location confirmedLocation, Path folder)
            throws IOException {
        if (node.kind() == WorldGraph.Kind.LOCAL) {
            folders.localOriginal(confirmedLocation);
            if (!node.id().equals(LocalWorldIdentity.read(folder)))
                throw new IOException("The local save identity changed");
            return;
        }
        verifyUpstreamOwner(folder, node);
        retireOtherReservations(node, folder);
    }
}
