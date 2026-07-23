package org.xpfarm.ollama.companion;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

/**
 * The companion's crafting recipe. Vanilla ingredients only, so the result — and only the result —
 * carries custom data; that is what lets Bedrock players hand-craft it even though custom recipes
 * do not appear in their recipe book.
 */
public final class CompanionRecipe {

    private final Plugin plugin;
    private final CompanionItem item;
    private final NamespacedKey key;

    public CompanionRecipe(Plugin plugin, CompanionItem item) {
        this.plugin = plugin;
        this.item = item;
        this.key = new NamespacedKey(plugin, "companion_recipe");
    }

    public NamespacedKey key() {
        return key;
    }

    public ShapedRecipe build() {
        ShapedRecipe recipe = new ShapedRecipe(key, item.create(1));
        recipe.shape("WLW", "HGH", "WLW");
        recipe.setIngredient('W', Material.WHITE_WOOL);
        recipe.setIngredient('L', Material.LEAD);
        recipe.setIngredient('H', Material.HAY_BLOCK);
        recipe.setIngredient('G', Material.GOLD_INGOT);
        return recipe;
    }

    /** Registers the recipe. Safe to call once at enable; there is no recipe lifecycle event on 26.1. */
    public void register(Server server) {
        // removeRecipe first so a /reload does not throw on a duplicate key.
        server.removeRecipe(key);
        server.addRecipe(build());
        plugin.getLogger().info("Registered companion recipe " + key);
    }
}
