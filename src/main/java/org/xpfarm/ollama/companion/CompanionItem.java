package org.xpfarm.ollama.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Builds and identifies the companion summon item.
 *
 * <p>Identity is the persistent-data tag, never the material. Only the output of the recipe
 * carries this tag, which is why the recipe still resolves server-side for Bedrock players even
 * though Geyser strips NBT from recipe-book previews.
 */
public final class CompanionItem {

    private final NamespacedKey key;

    public CompanionItem(Plugin plugin) {
        this.key = CompanionKeys.item(plugin);
    }

    public ItemStack create(int amount) {
        ItemStack stack = new ItemStack(Material.LEAD, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Llama Companion", NamedTextColor.AQUA)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                Component.text("Place to summon your companion.", NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isSummonItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(key, PersistentDataType.BYTE);
    }
}
