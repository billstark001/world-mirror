package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.WorldSourceResolver.ProviderObservation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWorldBoundaryTest {
    @Test
    void addressAuthorizedRespawnResumesAtTheNextSafeTick() {
        var boundary = new RemoteWorldBoundary();
        boundary.enter(8, 100, false, "remote:prior");
        assertTrue(boundary.blocksCapture());
        assertFalse(boundary.needsIdentity());
        assertTrue(boundary.advance(101));
        assertFalse(boundary.blocksCapture());
    }

    @Test
    void slowRepublishPausesButDoesNotForceANewSession() {
        var boundary = new RemoteWorldBoundary();
        boundary.enter(8, 100, true, "remote:prior");
        assertFalse(boundary.advance(RemoteWorldBoundary.DISCOVERY_NS + 99));
        assertFalse(boundary.needsConfirmation());
        assertFalse(boundary.advance(RemoteWorldBoundary.DISCOVERY_NS + 100));
        assertTrue(boundary.blocksCapture());
        assertTrue(boundary.needsConfirmation());
        assertEquals("remote:prior", boundary.previousSource());
        assertFalse(boundary.attest(8, ProviderObservation.absent()));
        assertFalse(boundary.attest(7, ProviderObservation.advertised("xaero", "1")));
        assertTrue(boundary.attest(8, ProviderObservation.advertised("xaero", "1")));
        assertFalse(boundary.blocksCapture());
    }

    @Test
    void onlyCurrentNonStrictBoundarySupportsManualConfirmation() {
        var boundary = new RemoteWorldBoundary();
        boundary.enter(10, 100, true, "remote:prior");
        boundary.advance(RemoteWorldBoundary.DISCOVERY_NS + 100);
        assertFalse(boundary.confirm(10, true));
        assertFalse(boundary.confirm(9, false));
        assertTrue(boundary.confirm(10, false));
        assertFalse(boundary.blocksCapture());
    }

    @Test
    void anotherBoundaryWhileWaitingRetainsTheLastIdentifiedSource() {
        var boundary = new RemoteWorldBoundary();
        boundary.enter(8, 100, true, "remote:prior");
        boundary.enter(9, 110, true, null);
        assertEquals("remote:prior", boundary.previousSource());
        assertFalse(boundary.attest(8, ProviderObservation.advertised("xaero", "1")));
        assertTrue(boundary.attest(9, ProviderObservation.advertised("xaero", "1")));
        assertNull(boundary.previousSource());
    }
}
