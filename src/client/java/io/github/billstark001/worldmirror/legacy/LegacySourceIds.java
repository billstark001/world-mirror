package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.download.WorldGraph.Kind;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Converts legacy source strings to canonical graph identities during migration. */
public final class LegacySourceIds {
    private LegacySourceIds() {}

    public static String legacyNodeId(LegacyWorldMetadata old) {
        return (old.kind() == Kind.SNAPSHOT ? "snapshot:" : "mirror:") + old.mirrorId();
    }

    public static String legacyParent(
            LegacyWorldMetadata old, Map<String, String> remotes, Map<String, String> knownIds) {
        if (old.kind() == Kind.SNAPSHOT)
            return null;
        return legacyParent(old.sourceId(), remotes, knownIds);
    }

    public static String legacyParent(
            String source, Map<String, String> remotes, Map<String, String> knownIds) {
        if (source.startsWith("mirror:")) {
            return knownIds.getOrDefault(source.substring("mirror:".length()), source);
        }
        return remotes.getOrDefault(source, WorldGraphConfig.deterministicId("unresolved", source));
    }

    public static Map<String, String> importRemoteIds(
            Collection<String> oldSourceIds, Map<String, String> persisted, Map<String, EndpointRule> rules) {
        Set<String> keys = new TreeSet<>(oldSourceIds);
        Map<String, List<String>> grouped = new TreeMap<>();
        for (String key : keys) {
            if (!key.startsWith("server:"))
                continue;
            try {
                String endpoint = WorldGraphConfig.normalizeEndpoint(key.substring("server:".length()));
                grouped.computeIfAbsent(endpoint, ignored -> new ArrayList<>()).add(key);
            } catch (IllegalArgumentException invalid) {
                // Invalid endpoint text survives as unresolved import evidence.
            }
        }
        Map<String, String> ids = new HashMap<>();
        for (var entry : grouped.entrySet()) {
            String endpoint = entry.getKey();
            for (String key : entry.getValue()) {
                EndpointRule rule = rules.get(endpoint);
                boolean newEndpoint = rule == null || rule.sourceIds().isEmpty();
                String canonical = WorldGraphConfig.deterministicId("remote", endpoint);
                String split = WorldGraphConfig.deterministicId("remote", key);
                String id = persisted.get(key);
                if (id == null) {
                    boolean otherPersistedKey = persisted.entrySet().stream().anyMatch(existing
                            -> !key.equals(existing.getKey())
                                    && endpointMatches(endpoint, existing.getKey()));
                    boolean useCanonical = entry.getValue().size() == 1 && !otherPersistedKey
                            && (newEndpoint || rule.sourceIds().equals(Set.of(canonical)));
                    id = useCanonical ? canonical : split;
                }
                ids.put(key, id);
                WorldGraphConfig.addEndpoint(rules, endpoint, id);
            }
        }
        return ids;
    }

    private static boolean endpointMatches(String endpoint, String legacySource) {
        try {
            return endpoint.equals(
                    WorldGraphConfig.normalizeEndpoint(legacySource.substring("server:".length())));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
