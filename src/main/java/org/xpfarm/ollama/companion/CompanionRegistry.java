package org.xpfarm.ollama.companion;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Bidirectional ownership between a player and their companion, stored in persistent data on both
 * sides. Resolution goes through {@link org.bukkit.Bukkit#getEntity(UUID)} at the call site — an
 * O(1) lookup that does not load chunks. A null resolution means the chunk is unloaded, not that
 * the companion is gone; the binding is dropped only on explicit removal.
 */
public final class CompanionRegistry {

    private final NamespacedKey ownerKey;
    private final NamespacedKey companionKey;

    public CompanionRegistry(Plugin plugin) {
        this.ownerKey = CompanionKeys.owner(plugin);
        this.companionKey = CompanionKeys.companion(plugin);
    }

    public void bind(Player owner, LivingEntity companion) {
        owner.getPersistentDataContainer()
                .set(companionKey, PersistentDataType.STRING, companion.getUniqueId().toString());
        companion.getPersistentDataContainer()
                .set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
    }

    public UUID companionOf(Player owner) {
        return parse(owner.getPersistentDataContainer().get(companionKey, PersistentDataType.STRING));
    }

    public UUID ownerOf(LivingEntity companion) {
        return parse(companion.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    public boolean isOwner(Player player, LivingEntity companion) {
        UUID owner = ownerOf(companion);
        return owner != null && owner.equals(player.getUniqueId());
    }

    public void unbind(Player owner) {
        owner.getPersistentDataContainer().remove(companionKey);
    }

    private static UUID parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
