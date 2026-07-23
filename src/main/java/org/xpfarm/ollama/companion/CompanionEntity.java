package org.xpfarm.ollama.companion;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Spawns and configures a companion llama, and reads/writes its downed flag.
 *
 * <p>The two persistence properties set here are different and both required: {@code setPersistent}
 * saves the entity to disk, while {@code setRemoveWhenFarAway(false)} is the one that actually stops
 * vanilla despawn. Vanilla goals are stripped so {@link FollowTask}'s pathfinder is not fought every
 * tick — tamed llamas do not follow their owner on their own.
 */
public final class CompanionEntity {

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final NamespacedKey downedKey;

    public CompanionEntity(Plugin plugin, CompanionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.downedKey = CompanionKeys.downed(plugin);
    }

    public Llama summon(Player owner, Location at) {
        Llama llama = at.getWorld().spawn(at, Llama.class, spawned -> {
            spawned.setPersistent(true);
            spawned.setRemoveWhenFarAway(false);
            spawned.setTamed(true);
            spawned.setOwner(owner);
            spawned.customName(net.kyori.adventure.text.Component.text(owner.getName() + "'s Llama"));
            spawned.setCustomNameVisible(true);
        });
        Bukkit.getMobGoals().removeAllGoals(llama);
        registry.bind(owner, llama);
        return llama;
    }

    public boolean isDowned(Llama llama) {
        return llama.getPersistentDataContainer()
                .getOrDefault(downedKey, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    public void setDowned(Llama llama, boolean downed) {
        if (downed) {
            llama.getPersistentDataContainer().set(downedKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            llama.getPersistentDataContainer().remove(downedKey);
        }
    }
}
