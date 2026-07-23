package org.xpfarm.ollama.companion;

import org.bukkit.Bukkit;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * Opens the conversation on right-click. Uses PlayerInteractAtEntityEvent because the parent
 * PlayerInteractEntityEvent is @ApiStatus.Obsolete on 26.x. Guards the hand (a single Bedrock tap
 * fires up to two interact packets) and opens the dialog one tick later so vanilla's mount/container
 * close does not swallow it.
 */
public final class CompanionInteractionListener implements Listener {

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final CompanionDialog dialog;

    public CompanionInteractionListener(Plugin plugin, CompanionRegistry registry, CompanionDialog dialog) {
        this.plugin = plugin;
        this.registry = registry;
        this.dialog = dialog;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // guard the Bedrock double-fire
        }
        if (!(event.getRightClicked() instanceof Llama llama)) {
            return;
        }
        Player player = event.getPlayer();
        if (!registry.isOwner(player, llama)) {
            return; // only the owner talks to it; also lets non-companion llamas behave normally
        }
        event.setCancelled(true); // stop the vanilla mount
        Bukkit.getScheduler().runTask(plugin, () -> dialog.open(player));
    }
}
