package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

final class DownedStateListenerTest {

    @Test
    void killAlwaysPassesThrough() {
        assertEquals(DownedStateListener.Outcome.ALLOW_KILL,
                DownedStateListener.classify(DamageCause.KILL, 100, 20, true));
    }

    @Test
    void voidTriggersRescue() {
        assertEquals(DownedStateListener.Outcome.VOID_RESCUE,
                DownedStateListener.classify(DamageCause.VOID, 100, 20, true));
    }

    @Test
    void lethalDamageDowns() {
        assertEquals(DownedStateListener.Outcome.DOWN,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 25, 20, false));
        assertEquals(DownedStateListener.Outcome.DOWN,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 20, 20, true));
    }

    @Test
    void nonLethalBlockedWhenInvulnerable() {
        assertEquals(DownedStateListener.Outcome.BLOCK,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 5, 20, true));
    }

    @Test
    void nonLethalAllowedWhenNotInvulnerable() {
        assertEquals(DownedStateListener.Outcome.IGNORE,
                DownedStateListener.classify(DamageCause.ENTITY_ATTACK, 5, 20, false));
    }
}
