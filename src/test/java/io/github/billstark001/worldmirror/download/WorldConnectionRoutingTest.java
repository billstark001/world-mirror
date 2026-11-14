package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;
import io.github.billstark001.worldmirror.download.WorldConnectionRouting.Choice;
import io.github.billstark001.worldmirror.config.ModConfig;
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
        assertEquals(SECOND, routing.chooseSource(routing.observation(), SECOND, Choice.EXISTING));

        assertFalse(routing.observeProvider(ProviderObservation.absent(), true));
        assertEquals(SECOND, routing.currentResolution().sourceNodeId());

        // Positive evidence for another world still fences the old choice.
        assertTrue(routing.observeProvider(ProviderObservation.advertised("xaero", "12"), true));
        assertEquals(State.CHOOSE_SOURCE, routing.currentResolution().state());
        assertEquals(FIRST, routing.chooseSource(routing.observation(), FIRST, Choice.EXISTING));
        assertEquals(FIRST, routing.currentResolution().sourceNodeId());
    }

    private static EndpointRule rule(IdPolicy policy) {
        return new EndpointRule(GROUP, Set.of(FIRST, SECOND), FIRST, policy);
    }

    @Test
    void useAddressOnceDoesNotCreateAStableBindingOrPersistThePolicy() throws Exception {
        var service = service();
        service.refresh();
        var routing = service.routing();
        var source = remote();
        routing.beginObservation(source);
        var initial = routing.observation();
        assertTrue(routing.finishDiscovery(initial));
        assertNull(routing.chooseSource(initial, null, Choice.ADDRESS_ONCE));
        String id = routing.chooseSource(routing.observation(), null, Choice.ADDRESS_ONCE);
        assertNotNull(id);
        service.setSourceSaveLocation(id, ModConfig.SaveLocation.SAVES);
        assertFalse(routing.expectsIdentity());
        assertFalse(routing.observeProvider(ProviderObservation.advertised("xaero", "87"), true));
        assertEquals(id, service.prepare(source).parentNodeId());
        assertTrue(service.configSnapshot().stableBindings().isEmpty());
        assertEquals(
                IdPolicy.PREFER_BOUND_ID, service.configSnapshot().endpointRules().get(ENDPOINT).idPolicy());
    }

    @Test
    void rememberAddressPersistsTheExplicitAddressPolicyOnFirstUse() throws Exception {
        var service = service();
        service.refresh();
        var source = remote();
        var routing = service.routing();
        routing.beginObservation(source);
        assertTrue(routing.finishDiscovery(routing.observation()));
        String id = routing.chooseSource(routing.observation(), null, Choice.REMEMBER_ADDRESS);
        service.setSourceSaveLocation(id, ModConfig.SaveLocation.SAVES);
        assertEquals(id, service.prepare(source).parentNodeId());

        var reopened = service();
        reopened.refresh();
        reopened.routing().beginObservation(remote());
        assertEquals(
                IdPolicy.ENDPOINT_ONLY, reopened.configSnapshot().endpointRules().get(ENDPOINT).idPolicy());
        assertEquals(id, reopened.routing().currentResolution().sourceNodeId());
    }

    @Test
    void currentBoundaryRejectsOldEvidenceAndDifferentIdInvalidatesCaptureSource() throws Exception {
        var service = service();
        service.refresh();
        service.editRouting(snapshot
                -> snapshot.withEndpointRule(ENDPOINT, rule(IdPolicy.PREFER_BOUND_ID))
                        .withStableBinding(new StableBinding("xaero", GROUP, "1", FIRST))
                        .withStableBinding(new StableBinding("xaero", GROUP, "2", SECOND)));
        var routing = service.routing();
        var first = ProviderObservation.advertised("xaero", "1");
        routing.beginObservation(remote());
        routing.observeProvider(first, false);
        var before = routing.observation();

        routing.enterRemoteBoundary(true);
        var boundary = routing.observation();
        assertFalse(routing.matchesSessionSource(FIRST));
        assertFalse(routing.observeAtBoundary(before.connectionEpoch(), before.worldEpoch(), first, FIRST));
        assertTrue(
                routing.observeAtBoundary(boundary.connectionEpoch(), boundary.worldEpoch(), first, FIRST));
        assertTrue(routing.matchesSessionSource(FIRST));

        routing.enterRemoteBoundary(true);
        boundary = routing.observation();
        assertTrue(routing.observeAtBoundary(boundary.connectionEpoch(), boundary.worldEpoch(),
                ProviderObservation.advertised("xaero", "2"), FIRST));
        assertFalse(routing.matchesSessionSource(FIRST));
        assertEquals(State.CHOOSE_SOURCE, routing.currentResolution().state());
        assertEquals(SECOND, routing.chooseSource(routing.observation(), SECOND, Choice.EXISTING));
        assertTrue(routing.matchesSessionSource(SECOND));
    }

    @Test
    void firstAttestationDuringARespawnDoesNotDemandAnotherExplicitChoice() throws Exception {
        var service = service();
        service.refresh();
        service.editRouting(snapshot
                -> snapshot.withEndpointRule(ENDPOINT, rule(IdPolicy.PREFER_BOUND_ID))
                        .withStableBinding(new StableBinding("xaero", GROUP, "1", FIRST)));
        var routing = service.routing();
        routing.beginObservation(remote());
        routing.enterRemoteBoundary(true);
        var current = routing.observation();
        assertTrue(routing.observeAtBoundary(current.connectionEpoch(), current.worldEpoch(),
                ProviderObservation.advertised("xaero", "1"), null));
        assertEquals(FIRST, routing.currentResolution().sourceNodeId());
    }
}
