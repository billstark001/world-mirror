package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;

import java.util.Objects;
import java.util.TreeSet;

/** Applies editor actions against the current routing generation. */
final class EndpointRoutingEdits {
    private EndpointRoutingEdits() {}

    static final class InvalidEdit extends IllegalArgumentException {
        InvalidEdit(String key) {
            super(key);
        }
    }

    static Snapshot addAlias(Snapshot config, String endpoint, String alias) {
        EndpointRule rule = requireEndpoint(config, endpoint);
        if (config.endpointRules().containsKey(alias))
            throw new InvalidEdit("aliasExists");
        return config.withEndpointRule(alias, rule);
    }

    static Snapshot remove(Snapshot config, String endpoint) {
        return replace(config, endpoint, requireEndpoint(config, endpoint), null);
    }

    static Snapshot detach(Snapshot config, String endpoint, String sourceId) {
        EndpointRule old = requireEndpoint(config, endpoint);
        var candidates = new TreeSet<>(old.sourceIds());
        if (!candidates.remove(sourceId))
            throw new InvalidEdit("sourceUnavailable");
        String defaultSource = Objects.equals(old.defaultSourceId(), sourceId) ? null : old.defaultSourceId();
        EndpointRule next = candidates.isEmpty()
                ? null
                : new EndpointRule(old.addressGroupId(), candidates, defaultSource, old.idPolicy());
        return replace(config, endpoint, old, next);
    }

    static Snapshot addBinding(Snapshot config, String endpoint, String expectedGroup, String sourceId,
            String provider, String id) {
        EndpointRule current = requireEndpoint(config, endpoint);
        if (!current.addressGroupId().equals(expectedGroup) || !current.sourceIds().contains(sourceId))
            throw new InvalidEdit("sourceUnavailable");
        var candidate = new StableBinding(provider, current.addressGroupId(), id, sourceId);
        boolean alreadyBound = config.stableBindings().stream().anyMatch(existing
                -> existing.provider().equals(provider) && existing.addressGroupId().equals(expectedGroup)
                        && existing.advertisedId().equals(id) && !existing.sourceId().equals(sourceId));
        if (alreadyBound)
            throw new InvalidEdit("bindingExists");
        return config.withStableBinding(candidate);
    }

    static Snapshot removeBinding(Snapshot config, StableBinding expected) {
        if (!config.stableBindings().contains(expected))
            throw new InvalidEdit("bindingChanged");
        return config.withoutStableBinding(
                expected.provider(), expected.addressGroupId(), expected.advertisedId());
    }

    static EndpointRule requireEndpoint(Snapshot config, String endpoint) {
        EndpointRule rule = config.endpointRules().get(endpoint);
        if (rule == null)
            throw new InvalidEdit("endpointUnavailable");
        return rule;
    }

    private static Snapshot replace(Snapshot config, String endpoint, EndpointRule old, EndpointRule next) {
        Snapshot updated = config.withEndpointRule(endpoint, next);
        for (var binding : config.stableBindings()) {
            if (old.addressGroupId().equals(binding.addressGroupId())
                    && old.sourceIds().contains(binding.sourceId())
                    && !updated.hasEndpointSource(binding.addressGroupId(), binding.sourceId())) {
                throw new InvalidEdit("bindingWouldBeOrphaned");
            }
        }
        return updated;
    }
}
