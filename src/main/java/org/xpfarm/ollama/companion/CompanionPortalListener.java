package org.xpfarm.ollama.companion;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.Plugin;

/** Teleports a companion to its owner after the owner changes world, since mobs do not follow through portals. */
public final class CompanionPortalListener implements Listener {

    private final Plugin plugin;
    private final CompanionRegistry registry;

    public CompanionPortalListener(Plugin plugin, CompanionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player owner = event.getPlayer();
        UUID id = registry.companionOf(owner);
        if (id == null) {
            return;
        }
        // One tick later: the player's destination is loaded and their location is final.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Llama llama && !llama.isDead()) {
                llama.teleportAsync(owner.getLocation());
            }
        });
    }
}
