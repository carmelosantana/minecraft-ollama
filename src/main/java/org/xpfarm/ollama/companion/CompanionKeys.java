package org.xpfarm.ollama.companion;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Central registry of the plugin's persistent-data keys for the companion feature.
 *
 * <p>One source of truth so the item tag, the entity's owner link, and the player's companion
 * link cannot drift apart across the classes that read and write them.
 */
public final class CompanionKeys {

    private CompanionKeys() {}

    /** Marks an {@code ItemStack} as a companion summon item. */
    public static NamespacedKey item(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_item");
    }

    /** Stored on the entity; value is the owner's UUID as a string. */
    public static NamespacedKey owner(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_owner");
    }

    /** Stored on the player; value is the bound companion's UUID as a string. */
    public static NamespacedKey companion(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_uuid");
    }

    /** Stored on the entity; byte flag marking the companion as downed. */
    public static NamespacedKey downed(Plugin plugin) {
        return new NamespacedKey(plugin, "companion_downed");
    }
}
