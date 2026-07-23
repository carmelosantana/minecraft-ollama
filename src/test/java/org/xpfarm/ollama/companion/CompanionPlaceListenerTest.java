package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class CompanionPlaceListenerTest {

    @Test
    void reportsAnExistingLiveCompanion() {
        CompanionRegistry registry = mock(CompanionRegistry.class);
        Player player = mock(Player.class);
        UUID companionId = UUID.randomUUID();
        when(registry.companionOf(player)).thenReturn(companionId);

        // hasLiveCompanion is the pure decision point extracted for testing: a bound UUID that
        // resolves to a non-null entity means "already has one". Bukkit.getEntity is passed in.
        CompanionPlaceListener listener = new CompanionPlaceListener(null, null, null, registry);
        assertTrue(listener.hasLiveCompanion(player, id -> mock(org.bukkit.entity.Entity.class)));
    }

    @Test
    void noCompanionWhenUnbound() {
        CompanionRegistry registry = mock(CompanionRegistry.class);
        Player player = mock(Player.class);
        when(registry.companionOf(player)).thenReturn(null);
        CompanionPlaceListener listener = new CompanionPlaceListener(null, null, null, registry);
        assertTrue(!listener.hasLiveCompanion(player, id -> null));
    }
}
