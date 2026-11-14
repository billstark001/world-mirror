package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.download.WorldGraph.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ConnectionObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderState;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.Resolution;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.github.billstark001.worldmirror.download.WorldGraphService.Source;

/** Tracks the live connection and resolves its logical source against the published rules. */
public final class WorldConnectionRouting {
    private final WorldGraphService service;
    private final WorldInventoryFolders folders;

    record Route(Source source, ConnectionObservation observation, String chosenSource, boolean createSource,
            boolean confirmSwitch) {}

    private record ResolvedRoute(Route route, WorldGraphConfig.Snapshot config, Resolution value) {}

    private volatile Route route;
    private volatile ResolvedRoute resolvedRoute;
    private ClientPacketListener connection;
    private IntegratedServer integratedServer;
    private CompletableFuture<String> localIdentityRequest;
    private long connectionEpoch;
    private String observedAddress;

    WorldConnectionRouting(WorldGraphService service, WorldInventoryFolders folders) {
        this.service = service;
        this.folders = folders;
    }

    public Source source() {
        Route current = route;
        return current == null ? null : current.source();
    }
    long connectionEpoch() {
        return connectionEpoch;
    }
    ConnectionObservation observation() {
        Route current = route;
        return current == null ? null : current.observation();
    }
    boolean expectsIdentity() {
        var observation = observation();
        if (observation == null || observation.endpoint() == null
                || observation.providerObservation().state() != ProviderState.ADVERTISED)
            return false;
        var rule = service.configSnapshot().endpointRules().get(observation.endpoint());
        return rule == null || rule.idPolicy() != IdPolicy.ENDPOINT_ONLY;
    }
    public Resolution currentResolution() {
        var view = service.published();
        return resolveCurrent(view.available() ? route : null, view.config());
    }

    boolean matchesSessionSource(String sourceId) {
        var view = service.published();
        return !view.available() || sourceId.equals(resolveCurrent(route, view.config()).sourceNodeId());
    }
    /** Checks game-thread capture against the observation that owns the current session. */
    public boolean observationIsCurrent(Minecraft client) {
        Source current = source();
        if (current == null || connection != client.getConnection())
            return false;
        if (current.endpoint() == null)
            return integratedServer != null && integratedServer == client.getSingleplayerServer();
        return client.getSingleplayerServer() == null
                && Objects.equals(observedAddress, currentAddress(client));
    }

    public void enter(Minecraft client) {
        cancelLocalIdentityRequest();
        connection = client.getConnection();
        integratedServer = client.getSingleplayerServer();
        Source source;
        try {
            if (integratedServer != null) {
                Path folder = integratedServer.getWorldPath(LevelResource.ROOT);
                var metadata = WorldMetadata.loadIfPresent(folder);
                String id = metadata.map(value -> value.nodeId).orElse(null);
                boolean original =
                        id == null && WorldMetadata.inspectSchema(folder) == WorldMetadata.Schema.MISSING;
                if (original) {
                    Path checked = activeLocalOriginal(folder);
                    id = LocalWorldIdentity.read(checked);
                }
                source = id != null || original
                        ? new Source(id, integratedServer.getWorldData().getLevelName(), null)
                        : null;
            } else {
                source = observeAddress(client);
            }
        } catch (IOException | IllegalArgumentException failure) {
            source = null;
            WMLogger.warnRateLimited(
                    "world-graph-source", 30_000, "Could not identify the open world", failure);
        }
        beginObservation(source);
        service.refreshAsync();
    }

    void beginObservation(Source source) {
        connectionEpoch++;
        route = observe(source);
    }

    public void leave() {
        route = null;
        connection = null;
        integratedServer = null;
        cancelLocalIdentityRequest();
        observedAddress = null;
        service.refreshAsync();
    }

    /** An ordinary save can participate before it has a persistent node. */
    boolean needsLocalIdentity(Minecraft client) {
        Source current = source();
        return current != null && current.endpoint() == null && current.nodeId() == null
                && observationIsCurrent(client);
    }

    public boolean canUseLinkedSource(Minecraft client) {
        if (!service.available() || !observationIsCurrent(client))
            return false;
        String id = currentResolution().sourceNodeId();
        return id == null ? needsLocalIdentity(client) : service.graph().isUnambiguous(id);
    }

    /** Resolves on the client thread. First-use writes run on the integrated server that holds the save lease. */
    public CompletableFuture<String> requireLinkedSource(Minecraft client) {
        if (!client.isSameThread() || !canUseLinkedSource(client))
            return CompletableFuture.failedFuture(new IOException("The active source is unavailable"));
        String id = currentResolution().sourceNodeId();
        if (id != null)
            return CompletableFuture.completedFuture(id);
        if (localIdentityRequest != null)
            return localIdentityRequest;
        var server = integratedServer;
        var original = route;
        var result = new CompletableFuture<String>();
        localIdentityRequest = result;
        Runnable assignIdentity = () -> {
            try {
                if (result.isDone() || route != original || !server.isSameThread() || !server.isRunning()
                        || server.isStopped())
                    throw new IOException("The active world changed before assigning its identity");
                Path folder = activeLocalOriginal(server.getWorldPath(LevelResource.ROOT));
                String assigned = LocalWorldIdentity.ensure(folder);
                client.execute(() -> finishLocalIdentity(client, original, result, assigned, null));
            } catch (IOException | RuntimeException failure) {
                client.execute(() -> finishLocalIdentity(client, original, result, null, failure));
            }
        };
        try {
            server.execute(assignIdentity);
        } catch (RuntimeException failure) {
            finishLocalIdentity(client, original, result, null, failure);
        }
        return result;
    }

    private void finishLocalIdentity(Minecraft client, Route original, CompletableFuture<String> result,
            String id, Exception failure) {
        if (localIdentityRequest != result)
            return;
        localIdentityRequest = null;
        if (failure != null) {
            result.completeExceptionally(failure);
        } else if (route != original || !observationIsCurrent(client)) {
            result.completeExceptionally(new IOException("The active world changed"));
        } else {
            route = observe(new Source(id, original.source().label(), null));
            service.refreshAsync();
            result.complete(id);
        }
    }

    private void cancelLocalIdentityRequest() {
        var result = localIdentityRequest;
        localIdentityRequest = null;
        if (result != null)
            result.completeExceptionally(new IOException("The active world changed"));
    }

    private Path activeLocalOriginal(Path folder) throws IOException {
        Path normalized = folder.toAbsolutePath().normalize();
        if (!folders.root(Root.SAVES).equals(normalized.getParent()))
            throw new IOException("The active save is outside the saves directory");
        return folders.localOriginal(folders.locationOf(normalized));
    }

    /** A connection event supplies local identity. Remote address changes require no filesystem access. */
    boolean updateRemoteObservation(Minecraft client) {
        Source current = source();
        if (client.getSingleplayerServer() != null || (current != null && current.endpoint() == null))
            return false;
        String address = currentAddress(client);
        if (Objects.equals(observedAddress, address))
            return false;
        Source observed = observeAddress(client);
        if (Objects.equals(current, observed))
            return false;
        route = observe(observed);
        return true;
    }

    private Route observe(Source source) {
        return source == null
                ? null
                : new Route(source,
                          new ConnectionObservation(connectionEpoch, source.endpoint(), source.nodeId(),
                                  source.endpoint() == null ? ProviderObservation.absent()
                                                            : ProviderObservation.pending()),
                          null, false, false);
    }

    boolean observeProvider(ProviderObservation evidence, boolean late) {
        Route previous = route;
        if (previous == null || previous.source().endpoint() == null)
            return false;
        var lastEvidence = previous.observation().providerObservation();
        // Provider state is scoped to the connection, not a particular vanilla dimension.
        if (lastEvidence.equals(evidence) || evidence.state() == ProviderState.PENDING
                || (evidence.state() == ProviderState.ABSENT
                        && lastEvidence.state() == ProviderState.ADVERTISED))
            return false;
        var observation = new ConnectionObservation(
                previous.observation().connectionEpoch(), previous.source().endpoint(), null, evidence);
        var config = service.configSnapshot();
        EndpointRule rule = config.endpointRules().get(previous.source().endpoint());
        boolean ignored = rule != null && rule.idPolicy() == IdPolicy.ENDPOINT_ONLY;
        if (ignored
                || (lastEvidence.state() == ProviderState.PENDING && evidence.state() == ProviderState.ABSENT
                        && previous.chosenSource() != null)) {
            // Keep the evidence for policy edits without disturbing an endpoint or manual selection.
            route = new Route(previous.source(), observation, previous.chosenSource(),
                    previous.createSource(), previous.confirmSwitch());
            return false;
        }
        var before = resolveCurrent(previous, config);
        var next = new Route(previous.source(), observation, null, false, false);
        var after = resolve(next, config);
        boolean sameSource =
                before.sourceNodeId() != null && before.sourceNodeId().equals(after.sourceNodeId());
        boolean confirmSwitch = !sameSource && late;
        route = sameSource ? next : new Route(previous.source(), observation, null, false, late);
        WMLogger.debug("World identity observation state=" + evidence.state() + " policy="
                + (rule == null ? IdPolicy.PREFER_BOUND_ID : rule.idPolicy()) + " previousResolution="
                + before.state() + " resolution=" + (confirmSwitch ? State.CHOOSE_SOURCE : after.state())
                + " changedSource=" + !sameSource + " late=" + late);
        return !sameSource;
    }

    void requireSourceChoice() {
        Route previous = route;
        if (previous != null && previous.source().endpoint() != null)
            route = new Route(previous.source(), previous.observation(), null, false, true);
    }

    String chooseSource(ConnectionObservation expected, String id, boolean create) {
        Route previous = route;
        if (previous == null || previous.observation() != expected || previous.source().endpoint() == null)
            return null;
        var config = service.configSnapshot();
        String selected = create ? WorldSourceResolver.suggestedSourceId(config, expected) : id;
        Resolution current = resolve(previous, config);
        var rule = config.endpointRules().get(expected.endpoint());
        if (create && rule != null && config.hasEndpointSource(rule.addressGroupId(), selected))
            selected = "remote:" + UUID.randomUUID();
        if (selected == null
                || (current.state() == State.WAITING_ID && rule != null
                        && rule.idPolicy() == IdPolicy.REQUIRE_BOUND_ID)
                || (!create && !current.candidateSourceIds().contains(selected)))
            return null;
        boolean newAdvertisedSource = expected.providerObservation().state() == ProviderState.ADVERTISED
                && (rule == null || !config.hasEndpointSource(rule.addressGroupId(), selected))
                && selected.equals(WorldSourceResolver.suggestedSourceId(config, expected));
        route = new Route(previous.source(), expected, selected, create || newAdvertisedSource, false);
        WMLogger.info("Download source selected newSource=" + route.createSource());
        return selected;
    }

    private static String currentAddress(Minecraft client) {
        var server = client.getCurrentServer();
        return server == null ? null : server.ip;
    }

    private Source observeAddress(Minecraft client) {
        observedAddress = currentAddress(client);
        if (observedAddress == null)
            return null;
        try {
            String endpoint = WorldGraphConfig.normalizeEndpoint(observedAddress);
            return new Source(null, endpoint, endpoint);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public String sourceNode(Source observation) {
        return sourceNode(observation, service.configSnapshot());
    }

    String sourceNode(Source observation, WorldGraphConfig.Snapshot config) {
        Route current = route;
        if (current != null && current.source() == observation)
            return resolveCurrent(current, config).sourceNodeId();
        return resolve(unobservedRoute(observation), config).sourceNodeId();
    }

    private Resolution resolveCurrent(Route current, WorldGraphConfig.Snapshot config) {
        ResolvedRoute resolved = resolvedRoute;
        if (resolved != null && resolved.route() == current && resolved.config() == config)
            return resolved.value();
        Resolution value = resolve(current, config);
        resolvedRoute = new ResolvedRoute(current, config, value);
        return value;
    }

    Route routeFor(Source source) {
        Route current = route;
        if (current != null && current.source() == source)
            return current;
        return unobservedRoute(source);
    }

    private static Route unobservedRoute(Source source) {
        return source == null ? null
                              : new Route(source,
                                        new ConnectionObservation(0, source.endpoint(), source.nodeId(),
                                                ProviderObservation.absent()),
                                        null, false, false);
    }

    static Resolution resolve(Route route, WorldGraphConfig.Snapshot config) {
        if (route == null)
            return new Resolution(State.UNAVAILABLE, null, java.util.Set.of());
        var resolved = WorldSourceResolver.resolve(config, route.observation(), route.chosenSource());
        if (route.chosenSource() != null && route.createSource())
            return new Resolution(State.RESOLVED, route.chosenSource(), resolved.candidateSourceIds());
        if (route.confirmSwitch()) {
            var choices = new java.util.TreeSet<>(resolved.candidateSourceIds());
            if (resolved.sourceNodeId() != null)
                choices.add(resolved.sourceNodeId());
            return new Resolution(State.CHOOSE_SOURCE, null, choices);
        }
        return resolved;
    }
}
