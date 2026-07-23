package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class CompanionRecipeTest {

    @Test
    void recipeKeyIsStable() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Ollama");
        // Paper's NamespacedKey(Plugin, String) derives the namespace from
        // Plugin.namespace(), not getName(); without this stub key() would NPE.
        when(plugin.namespace()).thenReturn("ollama");
        CompanionRecipe recipe = new CompanionRecipe(plugin, new CompanionItem(plugin));
        assertEquals("ollama:companion_recipe", recipe.key().toString());
    }
}
