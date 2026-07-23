package org.xpfarm.ollama.companion;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Re-issues a pathfinder move to each companion's owner, teleporting to catch up past a threshold. */
public final class FollowTask {

    private static final double FOLLOW_SPEED = 1.3D;

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final CompanionEntity entity;
    private final int teleportDistance;
    private BukkitRunnable task;

    public FollowTask(Plugin plugin, CompanionRegistry registry, CompanionEntity entity,
            int teleportDistance) {
        this.plugin = plugin;
        this.registry = registry;
        this.entity = entity;
        this.teleportDistance = teleportDistance;
    }

    public static boolean shouldTeleport(double distance, int teleportDistance) {
        return distance > teleportDistance;
    }

    public void start(int intervalTicks) {
        cancel();
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        this.task.runTaskTimer(plugin, intervalTicks, Math.max(1, intervalTicks));
    }

    private void tick() {
        for (Player owner : Bukkit.getOnlinePlayers()) {
            UUID id = registry.companionOf(owner);
            if (id == null) {
                continue;
            }
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Llama llama) || llama.isDead()) {
                continue;
            }
            if (entity.isDowned(llama)) {
                continue;
            }
            if (!llama.getWorld().equals(owner.getWorld())) {
                llama.teleportAsync(owner.getLocation());
                continue;
            }
            double distance = llama.getLocation().distance(owner.getLocation());
            if (shouldTeleport(distance, teleportDistance)) {
                llama.teleportAsync(owner.getLocation());
            } else if (distance > 3.0) {
                llama.getPathfinder().moveTo(owner.getLocation(), FOLLOW_SPEED);
            }
        }
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
