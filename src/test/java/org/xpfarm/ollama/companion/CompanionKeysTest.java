package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class CompanionKeysTest {

    private Plugin pluginNamed(String name) {
        Plugin p = Mockito.mock(Plugin.class);
        // Paper's NamespacedKey(Plugin, String) derives the namespace from
        // Plugin.namespace() (from Adventure's Namespaced), which JavaPlugin
        // returns already lowercased. Stub getName() too for good measure.
        String namespace = name.toLowerCase(java.util.Locale.ROOT);
        Mockito.when(p.getName()).thenReturn(name);
        Mockito.when(p.namespace()).thenReturn(namespace);
        return p;
    }

    @Test
    void keysAreStableAndDistinct() {
        Plugin plugin = pluginNamed("Ollama");
        assertEquals("ollama:companion_item", CompanionKeys.item(plugin).toString());
        assertEquals("ollama:companion_owner", CompanionKeys.owner(plugin).toString());
        assertEquals("ollama:companion_uuid", CompanionKeys.companion(plugin).toString());
        assertEquals("ollama:companion_downed", CompanionKeys.downed(plugin).toString());
        assertNotEquals(CompanionKeys.owner(plugin), CompanionKeys.companion(plugin));
    }
}
