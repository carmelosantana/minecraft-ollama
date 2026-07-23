package org.xpfarm.ollama.companion;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.plugin.Plugin;

/**
 * Implements downed-not-dead: a companion collapses and returns its summon item at lethal damage,
 * rather than dying permanently. {@code setInvulnerable(true)} alone would not do this — it blocks
 * neither the void nor {@code /kill} — so damage is intercepted here instead.
 */
public final class DownedStateListener implements Listener {

    public enum Outcome { IGNORE, BLOCK, DOWN, VOID_RESCUE, ALLOW_KILL }

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final CompanionEntity entity;
    private final CompanionItem item;
    private final boolean invulnerable;

    public DownedStateListener(Plugin plugin, CompanionRegistry registry, CompanionEntity entity,
            CompanionItem item, boolean invulnerable) {
        this.plugin = plugin;
        this.registry = registry;
        this.entity = entity;
        this.item = item;
        this.invulnerable = invulnerable;
    }

    public static Outcome classify(DamageCause cause, double finalDamage, double health,
            boolean invulnerable) {
        if (cause == DamageCause.KILL) {
            return Outcome.ALLOW_KILL;
        }
        if (cause == DamageCause.VOID) {
            return Outcome.VOID_RESCUE;
        }
        if (finalDamage >= health) {
            return Outcome.DOWN;
        }
        return invulnerable ? Outcome.BLOCK : Outcome.IGNORE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Llama llama)) {
            return;
        }
        UUID ownerId = registry.ownerOf(llama);
        if (ownerId == null) {
            return; // not a companion
        }
        Outcome outcome = classify(event.getCause(), event.getFinalDamage(), llama.getHealth(),
                invulnerable);
        switch (outcome) {
            case ALLOW_KILL -> { /* let it die */ }
            case BLOCK -> event.setCancelled(true);
            case IGNORE -> { /* normal damage */ }
            case VOID_RESCUE -> {
                event.setCancelled(true);
                Player owner = Bukkit.getPlayer(ownerId);
                if (owner != null) {
                    llama.teleportAsync(owner.getLocation());
                }
            }
            case DOWN -> {
                event.setCancelled(true);
                down(llama, ownerId);
            }
        }
    }

    private void down(Llama llama, UUID ownerId) {
        entity.setDowned(llama, true);
        llama.setAware(false);
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null) {
            registry.unbind(owner);
            owner.getInventory().addItem(item.create(1));
            owner.sendMessage(Component.text("Your llama was downed — its charm returns to you.",
                    NamedTextColor.RED));
        }
        llama.remove();
    }
}
