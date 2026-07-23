package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Llama;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionEntityTest {

    private Plugin plugin() {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn("Ollama");
        // Paper's NamespacedKey(Plugin, String) derives the namespace from
        // Plugin.namespace() (Adventure's Namespaced), not getName(). Without
        // this stub, new CompanionEntity(plugin, new CompanionRegistry(plugin))
        // NPEs while building keys.
        when(p.namespace()).thenReturn("ollama");
        return p;
    }

    @Test
    void readsDownedFlagTrueWhenSet() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.downed(plugin);
        Llama llama = mock(Llama.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(llama.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getOrDefault(eq(key), eq(PersistentDataType.BYTE), eq((byte) 0))).thenReturn((byte) 1);

        assertTrue(new CompanionEntity(plugin, new CompanionRegistry(plugin)).isDowned(llama));
    }

    @Test
    void readsDownedFlagFalseByDefault() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.downed(plugin);
        Llama llama = mock(Llama.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(llama.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getOrDefault(eq(key), eq(PersistentDataType.BYTE), eq((byte) 0))).thenReturn((byte) 0);

        assertFalse(new CompanionEntity(plugin, new CompanionRegistry(plugin)).isDowned(llama));
    }
}
