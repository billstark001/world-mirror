package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Resolves a connection against one immutable generation of the routing configuration. */
public final class WorldSourceResolver {
    public enum State { WAITING_ID, CHOOSE_SOURCE, UNAVAILABLE, RESOLVED }

    public enum ProviderState { PENDING, ABSENT, ADVERTISED, CONFLICT }
    public enum Discovery { PROBING, DEADLINE_PASSED }

    public record ProviderObservation(ProviderState state, String provider, String advertisedId) {
        public ProviderObservation {
            Objects.requireNonNull(state);
            if (state == ProviderState.ADVERTISED) {
                WorldGraphConfig.requireKey(provider, "ID provider");
                WorldGraphConfig.requireKey(advertisedId, "advertised ID");
            } else if (provider != null || advertisedId != null) {
                throw new IllegalArgumentException("Only an advertised observation carries an ID");
            }
        }

        public static ProviderObservation pending() {
            return new ProviderObservation(ProviderState.PENDING, null, null);
        }

        public static ProviderObservation absent() {
            return new ProviderObservation(ProviderState.ABSENT, null, null);
        }

        public static ProviderObservation advertised(String provider, String advertisedId) {
            return new ProviderObservation(ProviderState.ADVERTISED, provider, advertisedId);
        }

        public static ProviderObservation conflict() {
            return new ProviderObservation(ProviderState.CONFLICT, null, null);
        }
    }

    public record ConnectionObservation(long connectionEpoch, long worldEpoch, String endpoint,
            String localNodeId, ProviderObservation providerObservation, Discovery discovery) {
        public ConnectionObservation {
            Objects.requireNonNull(discovery);
            if (endpoint != null) {
                endpoint = WorldGraphConfig.normalizeEndpoint(endpoint);
                if (localNodeId != null) {
                    throw new IllegalArgumentException(
                            "Local and remote connection identities are exclusive");
                }
            }
            Objects.requireNonNull(providerObservation);
        }

        ConnectionObservation withProvider(ProviderObservation provider) {
            return new ConnectionObservation(
                    connectionEpoch, worldEpoch, endpoint, localNodeId, provider, discovery);
        }

        ConnectionObservation enterWorldBoundary() {
            return new ConnectionObservation(
                    connectionEpoch, worldEpoch + 1, endpoint, localNodeId, providerObservation, discovery);
        }

        ConnectionObservation finishDiscovery() {
            return new ConnectionObservation(connectionEpoch, worldEpoch, endpoint, localNodeId,
                    providerObservation, Discovery.DEADLINE_PASSED);
        }
    }

    public record Resolution(State state, String sourceNodeId, Set<String> candidateSourceIds) {
        public Resolution {
            Objects.requireNonNull(state);
            if ((state == State.RESOLVED) != (sourceNodeId != null)) {
                throw new IllegalArgumentException("Resolved source and state disagree");
            }
            candidateSourceIds = Collections.unmodifiableSet(new TreeSet<>(candidateSourceIds));
        }
    }

    private WorldSourceResolver() {}

    public static Resolution resolve(
            Snapshot config, ConnectionObservation observation, String selectedSourceId) {
        if (config == null || observation == null) {
            return result(State.UNAVAILABLE, Set.of());
        }
        if (observation.localNodeId() != null) {
            String id = observation.localNodeId();
            return resolved(id, Set.of(id));
        }
        if (observation.endpoint() == null) {
            return result(State.UNAVAILABLE, Set.of());
        }

        String endpoint = observation.endpoint();
        EndpointRule rule = config.endpointRules().get(endpoint);
        IdPolicy policy = rule == null ? IdPolicy.PREFER_BOUND_ID : rule.idPolicy();
        Set<String> candidates = new TreeSet<>(rule == null ? Set.of() : rule.sourceIds());
        String endpointId = WorldGraphConfig.deterministicId("remote", endpoint);
        if (candidates.isEmpty()) {
            candidates.add(endpointId);
        }

        ProviderObservation provider = observation.providerObservation();
        String bound = policy == IdPolicy.ENDPOINT_ONLY ? null : boundSource(config, rule, provider);
        if (bound != null) {
            candidates.add(bound);
        }
        if (policy != IdPolicy.ENDPOINT_ONLY && provider.state() == ProviderState.ADVERTISED
                && bound == null) {
            candidates.add(suggestedSourceId(config, observation));
        }
        if (policy != IdPolicy.REQUIRE_BOUND_ID && selectedSourceId != null
                && candidates.contains(selectedSourceId)) {
            return resolved(selectedSourceId, candidates);
        }
        if (policy == IdPolicy.ENDPOINT_ONLY) {
            return endpointResult(rule, candidates);
        }
        return switch (provider.state()) {
            case PENDING ->
                result(policy == IdPolicy.PREFER_BOUND_ID
                                        && observation.discovery() == Discovery.DEADLINE_PASSED
                                ? State.CHOOSE_SOURCE
                                : State.WAITING_ID,
                        candidates);
            case ABSENT ->
                policy == IdPolicy.REQUIRE_BOUND_ID ? result(State.WAITING_ID, candidates)
                                                    : endpointResult(rule, candidates);
            case CONFLICT -> result(State.CHOOSE_SOURCE, candidates);
            case ADVERTISED ->
                bound == null ? result(State.CHOOSE_SOURCE, candidates) : resolved(bound, candidates);
        };
    }

    /** Address use is available when the endpoint uniquely determines a source. */
    static Resolution resolveAddress(Snapshot config, ConnectionObservation observation) {
        if (config == null || observation == null || observation.endpoint() == null)
            return result(State.UNAVAILABLE, Set.of());
        EndpointRule rule = config.endpointRules().get(observation.endpoint());
        Set<String> candidates = rule == null || rule.sourceIds().isEmpty()
                ? Set.of(WorldGraphConfig.deterministicId("remote", observation.endpoint()))
                : rule.sourceIds();
        return endpointResult(rule, candidates);
    }

    /** New identified worlds get a stable graph identity without touching an existing source key. */
    public static String suggestedSourceId(Snapshot config, ConnectionObservation observation) {
        String endpoint = Objects.requireNonNull(observation.endpoint(), "remote endpoint");
        EndpointRule rule = config.endpointRules().get(endpoint);
        ProviderObservation provider = observation.providerObservation();
        if (provider.state() != ProviderState.ADVERTISED
                || (rule != null && rule.idPolicy() == IdPolicy.ENDPOINT_ONLY)) {
            return WorldGraphConfig.deterministicId("remote", endpoint);
        }
        String group =
                rule == null ? WorldGraphConfig.deterministicId("endpoint", endpoint) : rule.addressGroupId();
        return WorldGraphConfig.deterministicId(
                "remote", group + "\0" + provider.provider() + "\0" + provider.advertisedId());
    }

    private static String boundSource(Snapshot config, EndpointRule rule, ProviderObservation provider) {
        if (rule == null || provider.state() != ProviderState.ADVERTISED) {
            return null;
        }
        return config.stableBindings()
                .stream()
                .filter(binding
                        -> binding.matches(
                                provider.provider(), rule.addressGroupId(), provider.advertisedId()))
                .map(StableBinding::sourceId)
                .filter(id -> config.hasEndpointSource(rule.addressGroupId(), id))
                .findFirst()
                .orElse(null);
    }

    private static Resolution endpointResult(EndpointRule rule, Set<String> candidates) {
        if (rule != null && rule.defaultSourceId() != null) {
            return resolved(rule.defaultSourceId(), candidates);
        }
        return candidates.size() == 1 ? resolved(candidates.iterator().next(), candidates)
                                      : result(State.CHOOSE_SOURCE, candidates);
    }

    private static Resolution result(State state, Set<String> candidates) {
        return new Resolution(state, null, candidates);
    }

    private static Resolution resolved(String id, Set<String> candidates) {
        return new Resolution(State.RESOLVED, id, candidates);
    }
}
