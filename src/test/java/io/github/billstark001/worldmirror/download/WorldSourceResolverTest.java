package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ConnectionObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.Discovery;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSourceResolverTest {
    private static final String ADDRESS = "server.test";
    private static final String GROUP = "group";
    private static final String FIRST = "remote:first";
    private static final String SECOND = "remote:second";
    private static final ProviderObservation FIRST_ID = ProviderObservation.advertised("xaero", "-123");
    private static final ProviderObservation SECOND_ID = ProviderObservation.advertised("xaero", "0");

    @Test
    void preferUsesABoundIdAndUsesEndpointDefaultOnlyAfterConfirmedAbsence() {
        Snapshot config = configured(IdPolicy.PREFER_BOUND_ID, FIRST);
        assertEquals(State.WAITING_ID, resolve(config, ProviderObservation.pending(), null).state());
        assertEquals(FIRST, resolve(config, ProviderObservation.absent(), null).sourceNodeId());
        assertEquals(SECOND, resolve(config, SECOND_ID, null).sourceNodeId());
        assertEquals(State.CHOOSE_SOURCE, resolve(config, FIRST_ID, null).state());
        assertEquals(State.CHOOSE_SOURCE, resolve(config, ProviderObservation.conflict(), null).state());
        assertEquals(FIRST, resolve(config, FIRST_ID, FIRST).sourceNodeId());
        assertNull(resolve(config, FIRST_ID, "remote:other-address").sourceNodeId());
    }

    @Test
    void requireDoesNotFallBackAndEndpointOnlyIgnoresTheProvider() {
        Snapshot require = configured(IdPolicy.REQUIRE_BOUND_ID, FIRST);
        assertEquals(State.WAITING_ID, resolve(require, ProviderObservation.absent(), FIRST).state());
        assertEquals(State.WAITING_ID, resolve(require, ProviderObservation.pending(), FIRST).state());
        assertEquals(State.CHOOSE_SOURCE, resolve(require, FIRST_ID, null).state());
        assertNull(resolve(require, FIRST_ID, FIRST).sourceNodeId());
        assertEquals(SECOND, resolve(require, SECOND_ID, null).sourceNodeId());
        assertEquals(SECOND, resolve(require, SECOND_ID, FIRST).sourceNodeId());

        Snapshot endpointOnly = configured(IdPolicy.ENDPOINT_ONLY, FIRST);
        assertEquals(FIRST, resolve(endpointOnly, ProviderObservation.pending(), null).sourceNodeId());
        assertEquals(FIRST, resolve(endpointOnly, SECOND_ID, null).sourceNodeId());
        assertEquals(State.CHOOSE_SOURCE,
                resolve(configured(IdPolicy.ENDPOINT_ONLY, null), SECOND_ID, null).state());
    }

    @Test
    void discoveryDeadlineOffersAChoiceWithoutTreatingSilenceAsAbsence() {
        var expired = observation(ADDRESS, ProviderObservation.pending()).finishDiscovery();
        assertEquals(ProviderObservation.pending(), expired.providerObservation());
        assertEquals(
                State.CHOOSE_SOURCE, WorldSourceResolver.resolve(Snapshot.empty(), expired, null).state());
        var prefer = configured(IdPolicy.PREFER_BOUND_ID, FIRST);
        assertEquals(State.CHOOSE_SOURCE, WorldSourceResolver.resolve(prefer, expired, null).state());
        assertEquals(FIRST, WorldSourceResolver.resolveAddress(prefer, expired).sourceNodeId());
        assertEquals(SECOND,
                WorldSourceResolver.resolve(prefer, expired.withProvider(SECOND_ID), null).sourceNodeId());
        assertEquals(State.WAITING_ID,
                WorldSourceResolver.resolve(configured(IdPolicy.REQUIRE_BOUND_ID, FIRST), expired, null)
                        .state());
        assertEquals(FIRST,
                WorldSourceResolver.resolve(configured(IdPolicy.ENDPOINT_ONLY, FIRST), expired, null)
                        .sourceNodeId());
        assertEquals(State.CHOOSE_SOURCE,
                WorldSourceResolver.resolveAddress(configured(IdPolicy.PREFER_BOUND_ID, null), expired)
                        .state());
    }

    @Test
    void firstStableCandidateIsDeterministicAndDoesNotReplaceTheAddressIdentity() {
        Snapshot empty = Snapshot.empty();
        String addressId = resolve(empty, ProviderObservation.absent(), null).sourceNodeId();
        ConnectionObservation observation = observation(ADDRESS, FIRST_ID);
        var unknown = WorldSourceResolver.resolve(empty, observation, null);
        assertEquals(State.CHOOSE_SOURCE, unknown.state());
        String freshId = WorldSourceResolver.suggestedSourceId(empty, observation);
        assertTrue(unknown.candidateSourceIds().contains(freshId));
        assertNotEquals(addressId, freshId);
        assertNotEquals(freshId,
                WorldSourceResolver.suggestedSourceId(empty, observation("elsewhere.test", FIRST_ID)));

        Snapshot candidate = empty.withEndpointSource(ADDRESS, freshId);
        String group = candidate.endpointRules().get(ADDRESS).addressGroupId();
        var bound = candidate.withStableBinding(new StableBinding("xaero", group, "-123", freshId));
        assertEquals(freshId, WorldSourceResolver.resolve(bound, observation, null).sourceNodeId());
        assertEquals(State.CHOOSE_SOURCE,
                WorldSourceResolver.resolve(bound, observation(ADDRESS, SECOND_ID), null).state());
    }

    @Test
    void stableBindingsConvergeWithinTheirGroupAndNeverLeakToOtherEndpoints() {
        var base = configured(IdPolicy.PREFER_BOUND_ID, FIRST);
        var aliases = base.withEndpointRule("alias.test",
                                  new EndpointRule(GROUP, Set.of(FIRST), FIRST, IdPolicy.PREFER_BOUND_ID))
                              .withEndpointRule("unrelated.test",
                                      new EndpointRule("another-group", Set.of(FIRST), FIRST,
                                              IdPolicy.PREFER_BOUND_ID));
        assertEquals(SECOND,
                WorldSourceResolver.resolve(aliases, observation("ALIAS.test:25565", SECOND_ID), null)
                        .sourceNodeId());
        assertEquals(State.CHOOSE_SOURCE,
                WorldSourceResolver.resolve(aliases, observation("unrelated.test", SECOND_ID), null).state());
        var detached = aliases.withEndpointRule(ADDRESS, null);
        assertEquals(State.CHOOSE_SOURCE,
                WorldSourceResolver.resolve(detached, observation("alias.test", SECOND_ID), null).state());
    }

    @Test
    void localAndUnavailableDoNotConsultRemoteRules() {
        Snapshot config = configured(IdPolicy.PREFER_BOUND_ID, null);
        assertEquals(State.UNAVAILABLE, WorldSourceResolver.resolve(config, null, null).state());
        assertEquals("local:world",
                WorldSourceResolver
                        .resolve(config,
                                new ConnectionObservation(8, 0, null, "local:world",
                                        ProviderObservation.pending(), Discovery.PROBING),
                                SECOND)
                        .sourceNodeId());
    }

    private static Snapshot configured(IdPolicy policy, String defaultSource) {
        return Snapshot.empty()
                .withEndpointRule(
                        ADDRESS, new EndpointRule(GROUP, Set.of(FIRST, SECOND), defaultSource, policy))
                .withStableBinding(new StableBinding("xaero", GROUP, "0", SECOND));
    }

    private static WorldSourceResolver.Resolution resolve(
            Snapshot config, ProviderObservation provider, String selected) {
        return WorldSourceResolver.resolve(config, observation(ADDRESS, provider), selected);
    }

    private static ConnectionObservation observation(String endpoint, ProviderObservation provider) {
        return new ConnectionObservation(7, 0, endpoint, null, provider, Discovery.PROBING);
    }
}
