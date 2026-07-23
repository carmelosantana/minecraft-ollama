package org.xpfarm.ollama.companion;

import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/** Turns a right-click with a summon item into a spawned, bound companion. One per player. */
public final class CompanionPlaceListener implements Listener {

    private final Plugin plugin;
    private final CompanionItem item;
    private final CompanionEntity entity;
    private final CompanionRegistry registry;

    public CompanionPlaceListener(Plugin plugin, CompanionItem item, CompanionEntity entity,
            CompanionRegistry registry) {
        this.plugin = plugin;
        this.item = item;
        this.entity = entity;
        this.registry = registry;
    }

    /** Pure decision: does the player have a companion UUID that still resolves to an entity? */
    public boolean hasLiveCompanion(Player player, Function<UUID, Entity> resolver) {
        UUID id = registry.companionOf(player);
        return id != null && resolver.apply(id) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        // Guard the double-fire: only act on the main hand.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (!item.isSummonItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (hasLiveCompanion(player, Bukkit::getEntity)) {
            player.sendMessage(Component.text("You already have a companion nearby.", NamedTextColor.YELLOW));
            return;
        }

        Location at = event.getClickedBlock() != null
                ? event.getClickedBlock().getLocation().add(0.5, 1, 0.5)
                : player.getLocation();
        entity.summon(player, at);

        var held = player.getInventory().getItemInMainHand();
        held.setAmount(held.getAmount() - 1);
        player.sendMessage(Component.text("Your llama companion appears.", NamedTextColor.AQUA));
    }
}
