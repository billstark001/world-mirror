package io.github.billstark001.worldmirror.download;

import com.google.common.net.InetAddresses;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.FileTransaction;
import io.github.billstark001.worldmirror.util.JsonSupport;
import io.github.billstark001.worldmirror.legacy.LegacyGraphPolicy;
import io.github.billstark001.worldmirror.legacy.LegacyRouting;

import java.io.IOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.net.Inet6Address;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Durable graph index and user routing choices. Save metadata owns the observed graph. */
public final class WorldGraphConfig {
    public static final String FILE_NAME = "world-graph.json";
    public static final int SCHEMA = 2;
    private static final int DEFAULT_PORT = 25565;

    public enum IdPolicy { ENDPOINT_ONLY, PREFER_BOUND_ID, REQUIRE_BOUND_ID }

    /** A null pin selects automatically. The last automatic target is only an ordering preference. */
    public record SourceSettings(String pinnedMirrorId, ModConfig.SaveLocation saveLocation,
            String preferredFolderName, ModConfig.ConflictStrategy conflictStrategy,
            String lastAutomaticMirrorId) {
        public static final SourceSettings EMPTY = new SourceSettings(null, null, null, null, null);

        public SourceSettings {
            if (pinnedMirrorId != null)
                requireKey(pinnedMirrorId, "pinned mirror");
            if (lastAutomaticMirrorId != null)
                requireKey(lastAutomaticMirrorId, "last automatic mirror");
            if (preferredFolderName != null && !MirrorFolders.isUsableFolderName(preferredFolderName)) {
                throw new IllegalArgumentException("Invalid preferred folder name");
            }
        }

        public SourceSettings withPinnedMirror(String mirrorId) {
            return new SourceSettings(
                    mirrorId, saveLocation, preferredFolderName, conflictStrategy, lastAutomaticMirrorId);
        }

        SourceSettings withAutomaticMirror(String mirrorId) {
            return new SourceSettings(
                    pinnedMirrorId, saveLocation, preferredFolderName, conflictStrategy, mirrorId);
        }

        SourceSettings withConflictStrategy(ModConfig.ConflictStrategy strategy) {
            return new SourceSettings(
                    pinnedMirrorId, saveLocation, preferredFolderName, strategy, lastAutomaticMirrorId);
        }

        SourceSettings withSaveLocation(ModConfig.SaveLocation location) {
            return new SourceSettings(
                    pinnedMirrorId, location, preferredFolderName, conflictStrategy, lastAutomaticMirrorId);
        }

        SourceSettings withoutTarget(String mirrorId) {
            return new SourceSettings(mirrorId.equals(pinnedMirrorId) ? null : pinnedMirrorId, saveLocation,
                    preferredFolderName, conflictStrategy,
                    mirrorId.equals(lastAutomaticMirrorId) ? null : lastAutomaticMirrorId);
        }
    }

    public record MirrorSettings(ModConfig.ConflictStrategy conflictStrategy) {}

    public record EndpointRule(
            String addressGroupId, Set<String> sourceIds, String defaultSourceId, IdPolicy idPolicy) {
        public EndpointRule {
            requireKey(addressGroupId, "address group");
            sourceIds = Collections.unmodifiableSet(new TreeSet<>(sourceIds));
            sourceIds.forEach(WorldGraphConfig::requireRemoteId);
            Objects.requireNonNull(idPolicy, "ID policy");
            if (defaultSourceId != null && !sourceIds.contains(defaultSourceId)) {
                throw new IllegalArgumentException("Endpoint default is not one of its source candidates");
            }
        }
    }

    private record AdvertisedKey(String provider, String addressGroupId, String advertisedId) {}

    public record
            StableBinding(String provider, String addressGroupId, String advertisedId, String sourceId) {
        public StableBinding {
            requireKey(provider, "ID provider");
            requireKey(addressGroupId, "address group");
            requireKey(advertisedId, "advertised ID");
            requireRemoteId(sourceId);
        }

        private AdvertisedKey key() {
            return new AdvertisedKey(provider, addressGroupId, advertisedId);
        }

        boolean matches(String provider, String addressGroupId, String advertisedId) {
            return key().equals(new AdvertisedKey(provider, addressGroupId, advertisedId));
        }
    }

    public record PendingImport(LegacyRouting.Entry legacy, WorldGraph.Location location, String reason) {
        public PendingImport {
            if (legacy == null && location == null)
                throw new IllegalArgumentException("Missing import evidence");
            requireKey(reason, "import reason");
        }
    }

    public record Snapshot(int schema, Map<String, WorldGraph.Node> nodes,
            Map<String, WorldGraph.Location> locations, Map<String, SourceSettings> sourceSettings,
            Map<String, MirrorSettings> mirrorSettings, Map<String, EndpointRule> endpointRules,
            List<StableBinding> stableBindings, Map<String, PendingImport> pendingImports) {
        public Snapshot {
            if (schema != SCHEMA)
                throw new IllegalArgumentException("Unsupported world graph schema " + schema);
            nodes = freeze(nodes);
            locations = freeze(locations);
            sourceSettings = freeze(sourceSettings);
            mirrorSettings = freeze(mirrorSettings);
            endpointRules = freeze(endpointRules);
            pendingImports = freeze(pendingImports);
            nodes.forEach((key, node) -> {
                if (!key.equals(node.id()))
                    throw new IllegalArgumentException("Graph key differs from node ID");
            });
            endpointRules.keySet().forEach(key -> {
                if (!key.equals(normalizeEndpoint(key)))
                    throw new IllegalArgumentException("Noncanonical endpoint " + key);
            });
            stableBindings = stableBindings.stream()
                                     .sorted(Comparator.comparing(StableBinding::provider)
                                                     .thenComparing(StableBinding::addressGroupId)
                                                     .thenComparing(StableBinding::advertisedId))
                                     .toList();
            Set<AdvertisedKey> seen = new HashSet<>();
            for (StableBinding binding : stableBindings) {
                if (!seen.add(binding.key()))
                    throw new IllegalArgumentException("Duplicate advertised world ID");
            }
        }

        public static Snapshot empty() {
            return new Snapshot(
                    SCHEMA, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of());
        }

        public Snapshot withNodes(Map<String, WorldGraph.Node> observed) {
            Map<String, WorldGraph.Location> updated = new TreeMap<>(locations);
            observed.forEach((id, node) -> {
                if (node.location() != null)
                    updated.put(id, node.location());
            });
            return new Snapshot(schema, observed, updated, sourceSettings, mirrorSettings, endpointRules,
                    stableBindings, pendingImports);
        }

        public Snapshot withEndpointRule(String endpoint, EndpointRule rule) {
            Map<String, EndpointRule> updated = new TreeMap<>(endpointRules);
            replaceEntry(updated, normalizeEndpoint(endpoint), rule);
            return new Snapshot(schema, nodes, locations, sourceSettings, mirrorSettings, updated,
                    stableBindings, pendingImports);
        }

        /** Adds a candidate without changing the policy or default of an existing address. */
        public Snapshot withEndpointSource(String endpoint, String sourceNodeId) {
            String address = normalizeEndpoint(endpoint);
            EndpointRule old = endpointRules.get(address);
            Set<String> candidates = new TreeSet<>(old == null ? Set.of() : old.sourceIds());
            candidates.add(sourceNodeId);
            return withEndpointRule(address,
                    new EndpointRule(
                            old == null ? deterministicId("endpoint", address) : old.addressGroupId(),
                            candidates, old == null ? null : old.defaultSourceId(),
                            old == null ? IdPolicy.PREFER_BOUND_ID : old.idPolicy()));
        }

        public Snapshot withSourceSettings(String sourceNodeId, SourceSettings settings) {
            Map<String, SourceSettings> updated = new TreeMap<>(sourceSettings);
            replaceEntry(updated, sourceNodeId, settings);
            return new Snapshot(schema, nodes, locations, updated, mirrorSettings, endpointRules,
                    stableBindings, pendingImports);
        }

        public Snapshot withMirrorSettings(String mirrorNodeId, MirrorSettings settings) {
            Map<String, MirrorSettings> updated = new TreeMap<>(mirrorSettings);
            replaceEntry(updated, mirrorNodeId, settings);
            return new Snapshot(schema, nodes, locations, sourceSettings, updated, endpointRules,
                    stableBindings, pendingImports);
        }

        public boolean hasEndpointSource(String addressGroupId, String sourceNodeId) {
            return endpointRules.values().stream().anyMatch(rule
                    -> rule.addressGroupId().equals(addressGroupId)
                            && rule.sourceIds().contains(sourceNodeId));
        }

        public Snapshot withStableBinding(StableBinding binding) {
            if (!hasEndpointSource(binding.addressGroupId(), binding.sourceId())) {
                throw new IllegalArgumentException("Stable ID must name a source in its address group");
            }
            List<StableBinding> updated = new java.util.ArrayList<>(stableBindings);
            updated.removeIf(existing -> existing.key().equals(binding.key()));
            updated.add(binding);
            return withStableBindings(updated);
        }

        public Snapshot withoutStableBinding(String provider, String addressGroupId, String advertisedId) {
            return withStableBindings(stableBindings.stream()
                            .filter(binding -> !binding.matches(provider, addressGroupId, advertisedId))
                            .toList());
        }

        private Snapshot withStableBindings(List<StableBinding> updated) {
            return new Snapshot(schema, nodes, locations, sourceSettings, mirrorSettings, endpointRules,
                    updated, pendingImports);
        }

        private static <V> void replaceEntry(Map<String, V> map, String key, V value) {
            requireKey(key, "configuration key");
            if (value == null) {
                map.remove(key);
            } else {
                map.put(key, value);
            }
        }
    }

    private record Published(Snapshot snapshot, FileProof.Version version) {}

    private final Path file;
    private volatile Published published;

    private WorldGraphConfig(Path file, Published published) {
        this.file = file;
        this.published = published;
    }

    /** Opens one verified generation and recovers a previous interrupted config publication. */
    public static WorldGraphConfig open(Path file) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        FileTransaction.recover(path.getParent());
        FileProof.Version observed = stamp(path);
        var loaded = LegacyGraphPolicy.readAndUpgrade(path, observed);
        observed = loaded.version();
        JsonSupport.Document<Snapshot> document = loaded.document();
        if (!Objects.equals(observed, stamp(path)))
            throw new IOException("World graph changed while loading: " + path);
        return switch (document.state()) {
            case MISSING -> new WorldGraphConfig(path, new Published(Snapshot.empty(), null));
            case VALID -> new WorldGraphConfig(path, new Published(document.value(), observed));
            case INVALID, UNAVAILABLE ->
                throw new IOException("Cannot load world graph: " + path, document.failure());
        };
    }

    public Snapshot snapshot() {
        return published.snapshot();
    }

    public boolean exists() {
        return published.version() != null;
    }

    boolean isCurrent() throws IOException {
        return Objects.equals(published.version(), stamp(file));
    }

    /** Publishes the new snapshot only after a successful compare-and-replace on disk. */
    public synchronized boolean replace(Snapshot expected, Snapshot next) throws IOException {
        Objects.requireNonNull(next, "new graph snapshot");
        Published previous = published;
        if (previous.snapshot() != expected)
            return false;
        if (previous.version() != null && previous.snapshot().equals(next)) {
            if (!Objects.equals(previous.version(), stamp(file))) {
                throw new IOException("World graph changed before publication: " + file);
            }
            return true;
        }
        FileProof.Version version = JsonSupport.compareAndWriteDocument(file, next, previous.version());
        published = new Published(next, version);
        return true;
    }

    public static String deterministicId(String kind, String key) {
        return kind + ":"
                + UUID.nameUUIDFromBytes(
                        ("worldmirror:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static void addEndpoint(Map<String, EndpointRule> rules, String endpoint, String id) {
        EndpointRule old = rules.get(endpoint);
        Set<String> sources = new TreeSet<>(old == null ? Set.of() : old.sourceIds());
        sources.add(id);
        rules.put(endpoint,
                new EndpointRule(old == null ? deterministicId("endpoint", endpoint) : old.addressGroupId(),
                        sources, old == null ? null : old.defaultSourceId(),
                        old == null ? IdPolicy.ENDPOINT_ONLY : old.idPolicy()));
    }

    /** Normalizes a player-entered server address without resolving DNS or SRV records. */
    public static String normalizeEndpoint(String input) {
        if (input == null || input.isBlank())
            throw new IllegalArgumentException("Missing server address");
        String address = input.strip();
        String host;
        int port = DEFAULT_PORT;
        int firstColon = address.indexOf(':');
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close < 0)
                throw new IllegalArgumentException("Unclosed IPv6 address");
            host = ipv6(address.substring(1, close));
            if (close < address.length() - 1) {
                if (address.charAt(close + 1) != ':')
                    throw new IllegalArgumentException("Invalid IPv6 port separator");
                port = parsePort(address.substring(close + 2));
            }
        } else if (firstColon >= 0 && firstColon != address.lastIndexOf(':')) {
            host = ipv6(address);
        } else {
            String name = firstColon < 0 ? address : address.substring(0, firstColon);
            if (name.isBlank() || name.equals("."))
                throw new IllegalArgumentException("Missing server host");
            host = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (firstColon >= 0)
                port = parsePort(address.substring(firstColon + 1));
        }
        return port == DEFAULT_PORT ? host : host + ":" + port;
    }

    private static String ipv6(String host) {
        if (host.indexOf(':') < 0)
            throw new IllegalArgumentException("Expected an IPv6 address");
        int zoneStart = host.indexOf('%');
        String zone = zoneStart < 0 ? "" : host.substring(zoneStart);
        if (!zone.isEmpty() && !zone.matches("%[a-zA-Z0-9_.-]+"))
            throw new IllegalArgumentException("Invalid IPv6 zone");
        var parsed = InetAddresses.forString(zoneStart < 0 ? host : host.substring(0, zoneStart));
        String literal = InetAddresses.toAddrString(parsed);
        if (!(parsed instanceof Inet6Address))
            literal = "::ffff:" + literal;
        return "[" + literal + zone + "]";
    }

    private static int parsePort(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid server port");
        }
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid server port");
        return port;
    }

    private static FileProof.Version stamp(Path file) throws IOException {
        try {
            return FileProof.version(file);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static <V> Map<String, V> freeze(Map<String, V> entries) {
        Map<String, V> ordered = new TreeMap<>();
        entries.forEach((key, value) -> {
            requireKey(key, "configuration key");
            ordered.put(key, Objects.requireNonNull(value));
        });
        return Collections.unmodifiableMap(ordered);
    }

    static void requireKey(String value, String label) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private static void requireRemoteId(String id) {
        requireKey(id, "remote source");
        if (!id.startsWith("remote:") || id.substring("remote:".length()).isBlank()) {
            throw new IllegalArgumentException("Expected a remote source ID");
        }
    }
}
