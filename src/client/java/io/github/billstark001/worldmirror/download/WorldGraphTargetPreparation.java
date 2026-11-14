package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.SourceSettings;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderState;
import io.github.billstark001.worldmirror.download.WorldTargetSelection.Decision;
import io.github.billstark001.worldmirror.download.WorldTargetSelection.State;
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
        var view = service.published();
        if (observation.endpoint() == null && !view.graph().nodes().containsKey(parent))
            throw new IOException("Local source unavailable: " + parent);
        var decision = WorldGraphService.chooseTarget(view, parent, mirrorChoice, createMirror);
        if (decision.state() == State.EXISTING) {
            var target = decision.target();
            if (!stillCurrent.getAsBoolean())
                throw new IOException("Connection changed during mirror preparation");
            if (route.createSource() || decision.requestedMirrorId() == null)
                recordRoute(service, view.config(), route, parent,
                        decision.requestedMirrorId() == null ? target.nodeId() : null);
            return target;
        }
        requireCreation(decision);
        var settings = WorldGraphService.settings(view.config().sourceSettings(), parent);
        var target = reserve(service, observation, settings, parent, stillCurrent);
        recordRoute(service, view.config(), route, parent, createMirror ? null : target.nodeId());
        service.refreshPublished();
        return target;
    }

    private static void requireCreation(Decision decision) throws IOException {
        switch (decision.state()) {
            case CHOICE_UNAVAILABLE ->
                throw new IOException("Selected mirror unavailable: " + decision.requestedMirrorId());
            case PIN_UNAVAILABLE ->
                throw new IOException("Default mirror unavailable: " + decision.requestedMirrorId());
            case DEFERRED ->
                throw new IOException("The source or an existing mirror is still being reconciled");
            case CREATE -> {
            }
            case EXISTING -> throw new IllegalStateException("Target already exists");
        }
    }

    private static Target reserve(WorldGraphService service, Source observation, SourceSettings settings,
            String parent, BooleanSupplier stillCurrent) throws IOException {
        Path base = service.creationRoot(settings);
        Files.createDirectories(base);
        WorldInventoryFolders.requirePlainRoot(base);
        String name = WorldGraphService.creationName(settings, observation.label());
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
        return new Target(nodeId, parent, indexSource, folder);
    }

    private static void recordRoute(WorldGraphService service, WorldGraphConfig.Snapshot before, Route route,
            String parent, String automaticTarget) throws IOException {
        var next = automaticTarget == null
                ? before
                : before.withSourceSettings(parent,
                          WorldGraphService.settings(before.sourceSettings(), parent)
                                  .withAutomaticMirror(automaticTarget));
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
        if (next.equals(before))
            return;
        service.replaceConfig(before, next);
        WMLogger.info("Download route saved automaticTarget=" + (automaticTarget != null)
                + " endpointChanged=" + !before.endpointRules().equals(next.endpointRules())
                + " stableBindingAdded=" + (before.stableBindings().size() < next.stableBindings().size()));
    }
}
