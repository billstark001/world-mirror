package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Node;
import io.github.billstark001.worldmirror.download.WorldGraph.Role;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.PendingImport;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.legacy.LegacyMirrorClaim;
import io.github.billstark001.worldmirror.legacy.LegacyRouting;
import io.github.billstark001.worldmirror.legacy.LegacySourceIds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.billstark001.worldmirror.download.WorldInventoryFolders.locationKey;
import io.github.billstark001.worldmirror.download.WorldGraphInventory.Candidate;
import static io.github.billstark001.worldmirror.download.WorldInventoryFolders.requireLocalOriginalFiles;

/** Reads current and legacy reservation markers and ordinary local save identities. */
final class WorldInventoryClaims {
    private final WorldInventoryFolders roots;
    private final LegacyRouting legacy;
    private final Map<Path, String> legacyClaims;
    private final Map<String, String> remoteIds;
    private final Map<String, String> knownLegacyIds;
    private final Map<String, PendingImport> pending;

    WorldInventoryClaims(WorldInventoryFolders roots, LegacyRouting legacy, Map<Path, String> legacyClaims,
            Map<String, String> remoteIds, Map<String, String> knownLegacyIds,
            Map<String, PendingImport> pending) {
        this.roots = roots;
        this.legacy = legacy;
        this.legacyClaims = legacyClaims;
        this.remoteIds = remoteIds;
        this.knownLegacyIds = knownLegacyIds;
        this.pending = pending;
    }

    List<Location> scan(List<Path> folders, Set<Path> deferred, List<Node> observed,
            Map<Location, Candidate> byLocation, List<Location> orphanedMirrors) {
        List<Location> untracked = new ArrayList<>();
        Map<String, List<WorldMetadata>> completed = new HashMap<>();
        for (Candidate candidate : byLocation.values()) {
            WorldMetadata metadata = candidate.metadata();
            completed.computeIfAbsent(metadata.nodeId, ignored -> new ArrayList<>()).add(metadata);
        }
        for (Path folder : folders) {
            if (deferred.contains(folder))
                continue;
            Location location = roots.locationOf(folder);
            String key = locationKey(location);
            if (byLocation.containsKey(location))
                continue;
            PendingImport previous = pending.get(key);
            if (previous != null && !previous.reason().equals("claim_upgrade_deferred")
                    && !previous.reason().equals("local_identity_deferred")
                    && !previous.reason().equals("duplicate_identity_deferred")
                    && !previous.reason().equals("relocation_deferred")
                    && !previous.reason().equals("invalid_mirror_claim"))
                continue;
            try {
                MirrorClaim.Claim claim = MirrorClaim.read(folder);
                if (claim == null && legacyClaims.containsKey(folder)) {
                    claim = upgradeLegacyClaim(folder, location, key);
                    if (claim == null)
                        continue;
                }
                if (claim == null
                        && !Files.notExists(
                                folder.resolve(MirrorClaim.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                    pending.put(key, new PendingImport(null, location, "invalid_mirror_claim"));
                    WMLogger.warnRateLimited("graph-claim-invalid:" + folder, 30_000L,
                            "World inventory unavailable due to an invalid mirror reservation world="
                                    + folder);
                    continue;
                }
                if (claim != null) {
                    List<WorldMetadata> owners = completed.getOrDefault(claim.nodeId(), List.of());
                    String parent = claim.parentNodeId();
                    String indexSource = claim.sourceId();
                    boolean completedElsewhere =
                            owners.stream().anyMatch(owner -> owner.sourceId.equals(indexSource));
                    if (completedElsewhere && WorldRelocation.isEmptyReservation(folder, claim.nodeId())) {
                        pending.remove(key);
                        continue;
                    }
                    pending.remove(key);
                    observed.add(
                            new Node(claim.nodeId(), claim.parentNodeId(), location, Kind.MIRROR, Role.SYNC));
                    if (parent != null && parent.startsWith("remote:")) {
                        observed.add(new Node(claim.parentNodeId(), null, null, Kind.REMOTE, null));
                    }
                    continue;
                }
                if (!Files.isRegularFile(folder.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
                    continue;
                if (SeedConversionTarget.hasOrphanVoidGenerator(folder)) {
                    if (SeedConversionTarget.isAdoptableVoid(folder)) {
                        orphanedMirrors.add(location);
                        pending.remove(key);
                    }
                    continue;
                }
                if (location.root() != Root.SAVES)
                    continue;
                requireLocalOriginalFiles(folder);
                String id = LocalWorldIdentity.read(folder);
                pending.remove(key);
                if (id == null)
                    untracked.add(location);
                else
                    observed.add(new Node(id, null, location, Kind.LOCAL, null));
            } catch (IOException | RuntimeException failure) {
                pending.put(key, new PendingImport(null, location, "local_identity_deferred"));
                WMLogger.warnRateLimited("graph-local-scan:" + folder, 30_000L,
                        "Local world inventory deferred world=" + folder, failure);
            }
        }
        return List.copyOf(untracked);
    }

    private MirrorClaim.Claim upgradeLegacyClaim(Path folder, Location location, String key) {
        String oldSource = legacyClaims.get(folder);
        String parent = LegacySourceIds.legacyParent(oldSource, remoteIds, knownLegacyIds);
        try {
            var claim = LegacyMirrorClaim.upgrade(folder, oldSource, parent);
            WMLogger.info("Legacy mirror reservation upgraded world=" + folder);
            return claim;
        } catch (IOException failure) {
            var evidence = new PendingImport(legacy.entryFor(oldSource), location, "claim_upgrade_deferred");
            pending.put(key, evidence);
            pending.put(WorldGraphInventory.BLOCKED_SOURCE + parent, evidence);
            WMLogger.warnRateLimited("graph-claim-upgrade:" + folder, 30_000L,
                    "Legacy mirror reservation upgrade deferred world=" + folder, failure);
            return null;
        }
    }
}
