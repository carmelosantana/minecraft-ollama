package org.xpfarm.ollama.companion;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Speaks one deterministic reminder per player per cooldown, only to players with a live companion. */
public final class NudgeTask {

    private final Plugin plugin;
    private final CompanionRegistry registry;
    private final InventoryAdvisor advisor;
    private final long cooldownMillis;
    private final Map<UUID, Long> lastSpoken = new ConcurrentHashMap<>();
    private BukkitRunnable task;

    public NudgeTask(Plugin plugin, CompanionRegistry registry, InventoryAdvisor advisor,
            long cooldownSeconds) {
        this.plugin = plugin;
        this.registry = registry;
        this.advisor = advisor;
        this.cooldownMillis = cooldownSeconds * 1000L;
    }

    public boolean offCooldown(UUID player, long nowMillis) {
        Long last = lastSpoken.get(player);
        return last == null || nowMillis - last >= cooldownMillis;
    }

    public void markSpoken(UUID player, long nowMillis) {
        lastSpoken.put(player, nowMillis);
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
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (registry.companionOf(player) == null || !offCooldown(player.getUniqueId(), now)) {
                continue;
            }
            List<String> advice = advisor.advise(InventorySnapshots.of(player));
            if (!advice.isEmpty()) {
                player.sendMessage(Component.text("🦙 ", NamedTextColor.AQUA)
                        .append(Component.text(advice.get(0), NamedTextColor.WHITE)));
                markSpoken(player.getUniqueId(), now);
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
