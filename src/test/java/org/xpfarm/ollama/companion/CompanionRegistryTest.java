package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionRegistryTest {

    private Plugin plugin() {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn("Ollama");
        // Paper's NamespacedKey(Plugin, String) derives the namespace from
        // Plugin.namespace() (Adventure's Namespaced), not getName(). Without
        // this stub, new CompanionRegistry(plugin) NPEs while building keys.
        when(p.namespace()).thenReturn("ollama");
        return p;
    }

    @Test
    void resolvesCompanionUuidFromPlayerPdc() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.companion(plugin);
        UUID companionId = UUID.randomUUID();

        Player owner = mock(Player.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(owner.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(eq(key), eq(PersistentDataType.STRING))).thenReturn(companionId.toString());

        assertEquals(companionId, new CompanionRegistry(plugin).companionOf(owner));
    }

    @Test
    void returnsNullWhenNoCompanionBound() {
        Plugin plugin = plugin();
        Player owner = mock(Player.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(owner.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenReturn(null);
        assertNull(new CompanionRegistry(plugin).companionOf(owner));
    }

    @Test
    void isOwnerComparesEntityLinkToPlayerId() {
        Plugin plugin = plugin();
        NamespacedKey ownerKey = CompanionKeys.owner(plugin);
        UUID playerId = UUID.randomUUID();

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        LivingEntity companion = mock(LivingEntity.class);
        PersistentDataContainer epdc = mock(PersistentDataContainer.class);
        when(companion.getPersistentDataContainer()).thenReturn(epdc);
        when(epdc.get(eq(ownerKey), eq(PersistentDataType.STRING))).thenReturn(playerId.toString());

        assertTrue(new CompanionRegistry(plugin).isOwner(player, companion));
    }
}
