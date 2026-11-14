package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderState;
import io.github.billstark001.worldmirror.util.WMLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import io.github.billstark001.worldmirror.download.WorldGraphService.Source;
import io.github.billstark001.worldmirror.download.WorldGraphService.Target;
import io.github.billstark001.worldmirror.download.WorldConnectionRouting.Route;

/** Reserves a direct download target and persists its routing under the world graph lease. */
final class WorldGraphTargetPreparation {
    private WorldGraphTargetPreparation() {}

    static Target prepare(WorldGraphService service, Route route, String mirrorChoice, boolean createMirror,
            BooleanSupplier stillCurrent) throws IOException {
        String parent = WorldConnectionRouting.resolve(route, service.configSnapshot()).sourceNodeId();
        if (parent == null)
            throw new IOException("No unambiguous source selected");
        Source observation = route.source();
        Snapshot graph = service.graph();
        if (!graph.isUnambiguous(parent)) {
            throw new IOException("Source identity is ambiguous: " + parent);
        }
        if (observation.endpoint() == null && !graph.nodes().containsKey(parent))
            throw new IOException("Local source unavailable: " + parent);
        Target selected =
                createMirror ? null : WorldGraphService.targetFor(service.published(), parent, mirrorChoice);
        WorldGraphConfig.Snapshot before = service.open().snapshot();
        SourceSettings old = WorldGraphService.settings(before.sourceSettings(), parent);
        if (selected != null) {
            if (route.createSource()
                    || (mirrorChoice == null && WorldGraphService.preferredMirror(old) == null))
                recordRoute(service, before, route, parent,
                        mirrorChoice == null && WorldGraphService.preferredMirror(old) == null
                                ? selected.nodeId()
                                : null);
            return selected;
        }
        if (mirrorChoice != null)
            throw new IOException("Selected mirror unavailable: " + mirrorChoice);
        if (WorldGraphService.preferredMirror(old) != null && !createMirror)
            throw new IOException("Default mirror unavailable: " + WorldGraphService.preferredMirror(old));
        if (WorldGraphInventory.hasDeferredSource(before, parent)) {
            throw new IOException("An existing mirror is still awaiting metadata upgrade");
        }
        if (graph.duplicates().stream().anyMatch(copy -> parent.equals(copy.original().parentId()))) {
            throw new IOException("A copied mirror is still being reconciled");
        }
        Path base = service.rootFor(
                old.saveLocation() == null ? ModConfig.get().defaultSaveLocation : old.saveLocation());
        Files.createDirectories(base);
        WorldInventoryFolders.requirePlainRoot(base);
        String name = old.preferredFolderName() == null ? MirrorFolders.generate(observation.label())
                                                        : old.preferredFolderName();
        if (!stillCurrent.getAsBoolean())
            throw new IOException("Connection changed during mirror preparation");
        Path folder = MirrorFolders.claim(base, name);
        String nodeId = "mirror:" + UUID.randomUUID();
        String indexSource = parent;
        try {
            MirrorClaim.create(folder, nodeId, parent, indexSource);
        } catch (IOException failure) {
            try {
                Files.delete(folder);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        WMLogger.info("Download mirror reserved world=" + folder);
        recordRoute(service, before, route, parent,
                WorldGraphService.preferredMirror(old) == null && !createMirror ? nodeId : null);
        service.refreshPublished();
        return new Target(nodeId, parent, indexSource, folder);
    }

    private static void recordRoute(WorldGraphService service, WorldGraphConfig.Snapshot before, Route route,
            String parent, String target) throws IOException {
        var next = target == null ? before
                                  : before.withSourceSettings(parent,
                                            WorldGraphService.settings(before.sourceSettings(), parent)
                                                    .withDefaultMirror(target));
        String endpoint = route.source().endpoint();
        if (endpoint != null) {
            next = next.withEndpointSource(endpoint, parent);
            EndpointRule rule = next.endpointRules().get(endpoint);
            if (route.createSource() && rule.idPolicy() != IdPolicy.ENDPOINT_ONLY) {
                ProviderObservation evidence = route.observation().providerObservation();
                if (evidence.state() == ProviderState.ADVERTISED) {
                    String group = rule.addressGroupId();
                    var binding =
                            new StableBinding(evidence.provider(), group, evidence.advertisedId(), parent);
                    boolean alreadyBound = next.stableBindings().stream().anyMatch(
                            value -> value.matches(evidence.provider(), group, evidence.advertisedId()));
                    if (!alreadyBound)
                        next = next.withStableBinding(binding);
                }
            }
        }
        service.replaceConfig(before, next);
        if (!next.equals(before)) {
            WMLogger.info("Download route saved defaultMirror=" + (target != null) + " endpointChanged="
                    + !before.endpointRules().equals(next.endpointRules()) + " stableBindingAdded="
                    + (before.stableBindings().size() < next.stableBindings().size()));
        }
    }
}
