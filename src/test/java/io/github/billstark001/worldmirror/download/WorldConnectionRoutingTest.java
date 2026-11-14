package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import io.github.billstark001.worldmirror.download.WorldSourceResolver.State;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldConnectionRoutingTest extends WorldGraphFixture {
    private static final String GROUP = "endpoint:live";
    private static final String FIRST = "remote:first";
    private static final String SECOND = "remote:second";

    @Test
    void policyChangeResolvesTheIdAlreadyAdvertisedOnTheSameConnection() throws Exception {
        var service = service();
        service.refresh();
        service.editRouting(snapshot
                -> snapshot.withEndpointRule(ENDPOINT, rule(IdPolicy.ENDPOINT_ONLY))
                        .withStableBinding(new StableBinding("xaero", GROUP, "87", SECOND)));
        var routing = service.routing();
        routing.beginObservation(remote());
        assertEquals(FIRST, routing.currentResolution().sourceNodeId());

        assertFalse(routing.observeProvider(ProviderObservation.advertised("xaero", "87"), false));
        assertEquals(FIRST, routing.currentResolution().sourceNodeId());
        assertTrue(routing.matchesSessionSource(FIRST));
        service.editRouting(snapshot -> snapshot.withEndpointRule(ENDPOINT, rule(IdPolicy.REQUIRE_BOUND_ID)));
        assertEquals(SECOND, routing.currentResolution().sourceNodeId());
        assertFalse(routing.matchesSessionSource(FIRST));
    }

    @Test
    void absenceResolvesPendingEvidenceWithoutUndoingThePlayersSessionChoice() throws Exception {
        var service = service();
        service.refresh();
        service.editRouting(snapshot
                -> snapshot.withEndpointRule(ENDPOINT, rule(IdPolicy.PREFER_BOUND_ID))
                        .withStableBinding(new StableBinding("xaero", GROUP, "12", FIRST)));
        var routing = service.routing();
        routing.beginObservation(remote());
        assertEquals(State.WAITING_ID, routing.currentResolution().state());
        assertEquals(SECOND, routing.chooseSource(routing.observation(), SECOND, false));

        assertFalse(routing.observeProvider(ProviderObservation.absent(), true));
        assertEquals(SECOND, routing.currentResolution().sourceNodeId());

        // Positive evidence for another world still fences the old choice.
        assertTrue(routing.observeProvider(ProviderObservation.advertised("xaero", "12"), true));
        assertEquals(State.CHOOSE_SOURCE, routing.currentResolution().state());
        assertEquals(FIRST, routing.chooseSource(routing.observation(), FIRST, false));
        assertEquals(FIRST, routing.currentResolution().sourceNodeId());
    }

    private static EndpointRule rule(IdPolicy policy) {
        return new EndpointRule(GROUP, Set.of(FIRST, SECOND), FIRST, policy);
    }
}
