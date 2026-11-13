package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.WorldGraphConfig.EndpointRule;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.IdPolicy;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.Snapshot;
import io.github.billstark001.worldmirror.download.WorldGraphConfig.StableBinding;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EndpointRoutingEditsTest {
    private static final String PRIMARY = "primary.example.test";
    private static final String ALIAS = "alias.example.test";
    private static final String GROUP = "endpoint:test";
    private static final String FIRST = "remote:first";
    private static final String SECOND = "remote:second";

    @Test
    void aliasSharesGroupAndPreservesStableBindingsUntilLastAssociationIsRemoved() {
        var binding = new StableBinding("xaero", GROUP, "42", FIRST);
        var primary = new EndpointRule(GROUP, Set.of(FIRST, SECOND), FIRST, IdPolicy.PREFER_BOUND_ID);
        var initial = Snapshot.empty().withEndpointRule(PRIMARY, primary).withStableBinding(binding);
        var withAlias = EndpointRoutingEdits.addAlias(initial, PRIMARY, ALIAS);
        assertEquals(primary, withAlias.endpointRules().get(ALIAS));
        assertEquals(initial.stableBindings(), withAlias.stableBindings());
        assertThrows(EndpointRoutingEdits.InvalidEdit.class,
                () -> EndpointRoutingEdits.addAlias(withAlias, PRIMARY, ALIAS));

        var detached = EndpointRoutingEdits.detach(withAlias, PRIMARY, FIRST);
        assertNull(detached.endpointRules().get(PRIMARY).defaultSourceId());
        assertEquals(Set.of(SECOND), detached.endpointRules().get(PRIMARY).sourceIds());
        assertEquals(primary, detached.endpointRules().get(ALIAS));
        assertEquals(initial.stableBindings(), detached.stableBindings());
        assertEquals("bindingWouldBeOrphaned",
                assertThrows(EndpointRoutingEdits.InvalidEdit.class,
                        () -> EndpointRoutingEdits.remove(detached, ALIAS))
                        .getMessage());
        assertEquals("bindingWouldBeOrphaned",
                assertThrows(EndpointRoutingEdits.InvalidEdit.class,
                        () -> EndpointRoutingEdits.detach(detached, ALIAS, FIRST))
                        .getMessage());

        var withoutPrimary = EndpointRoutingEdits.detach(detached, PRIMARY, SECOND);
        assertFalse(withoutPrimary.endpointRules().containsKey(PRIMARY));
        assertEquals(primary, withoutPrimary.endpointRules().get(ALIAS));
        assertEquals(initial.stableBindings(), withoutPrimary.stableBindings());
    }
}
