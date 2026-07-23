package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionItemTest {

    private Plugin plugin() {
        Plugin p = mock(Plugin.class);
        // Paper's NamespacedKey(Plugin, String) derives the namespace from
        // Plugin.namespace() (Adventure's Namespaced), not getName(). The mock
        // returns null unless stubbed, so the constructor would NPE without this.
        when(p.getName()).thenReturn("Ollama");
        when(p.namespace()).thenReturn("ollama");
        return p;
    }

    @Test
    void identifiesATaggedStack() {
        Plugin plugin = plugin();
        NamespacedKey key = CompanionKeys.item(plugin);

        ItemStack stack = mock(ItemStack.class);
        when(stack.hasItemMeta()).thenReturn(true);
        ItemMeta meta = mock(ItemMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(eq(key), any())).thenReturn(true);

        assertTrue(new CompanionItem(plugin).isSummonItem(stack));
    }

    @Test
    void rejectsAnUntaggedStack() {
        Plugin plugin = plugin();
        ItemStack stack = mock(ItemStack.class);
        when(stack.hasItemMeta()).thenReturn(false);
        assertFalse(new CompanionItem(plugin).isSummonItem(stack));
    }

    @Test
    void rejectsNull() {
        assertFalse(new CompanionItem(plugin()).isSummonItem(null));
    }
}
