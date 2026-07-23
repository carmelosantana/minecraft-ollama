package org.xpfarm.ollama.companion;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.PluginManager;
import org.xpfarm.ollama.OllamaPlugin;

/**
 * Owns the companion subsystem. Enabled by {@code companion.enabled} independently of the Ollama
 * {@code enabled} master switch — crafting, following, downing, and nudges do not need Ollama; only
 * conversation does, and it degrades when the API is absent.
 */
public final class CompanionManager {

    private static final Set<String> KNOWN_RULES = Set.of("shield", "food", "tool_durability", "torches");

    private final OllamaPlugin plugin;
    private FollowTask followTask;
    private NudgeTask nudgeTask;
    private CompanionConversation conversation;

    public CompanionManager(OllamaPlugin plugin) {
        this.plugin = plugin;
    }

    public static Set<String> parseRules(List<String> configured) {
        Set<String> out = new LinkedHashSet<>();
        for (String rule : configured) {
            if (KNOWN_RULES.contains(rule)) {
                out.add(rule);
            }
        }
        return out;
    }

    public CompanionConversation getConversation() {
        return conversation;
    }

    public void enable() {
        var config = plugin.getConfig();
        int followInterval = config.getInt("companion.follow_interval", 10);
        int teleportDistance = config.getInt("companion.teleport_distance", 24);
        boolean invulnerable = config.getBoolean("companion.invulnerable", true);
        long nudgeCooldown = config.getLong("companion.nudges.cooldown", 300);
        boolean nudgesEnabled = config.getBoolean("companion.nudges.enabled", true);
        Set<String> rules = parseRules(config.getStringList("companion.nudges.rules"));

        CompanionItem item = new CompanionItem(plugin);
        CompanionRegistry registry = new CompanionRegistry(plugin);
        CompanionEntity entity = new CompanionEntity(plugin, registry);
        CompanionRecipe recipe = new CompanionRecipe(plugin, item);
        recipe.register(plugin.getServer());

        this.conversation = new CompanionConversation(plugin, loadPersonality());
        CompanionDialog dialog = new CompanionDialog(conversation);

        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(new CompanionPlaceListener(plugin, item, entity, registry), plugin);
        pm.registerEvents(new CompanionInteractionListener(plugin, registry, dialog), plugin);
        pm.registerEvents(new CompanionPortalListener(plugin, registry), plugin);
        pm.registerEvents(new DownedStateListener(plugin, registry, entity, item, invulnerable), plugin);

        LlamaCommand command = new LlamaCommand(plugin, conversation, registry, item);
        plugin.getCommand("llama").setExecutor(command);
        plugin.getCommand("llama").setTabCompleter(command);

        this.followTask = new FollowTask(plugin, registry, entity, teleportDistance);
        this.followTask.start(followInterval);

        if (nudgesEnabled) {
            this.nudgeTask = new NudgeTask(plugin, registry, new InventoryAdvisor(rules), nudgeCooldown);
            // Check roughly every 5 seconds; the per-player cooldown does the real gating.
            this.nudgeTask.start(100);
        }

        plugin.getLogger().info("Llama companion enabled"
                + (plugin.getOllamaAPI() == null ? " (conversation dormant — Ollama disabled)" : ""));
    }

    public void disable() {
        if (followTask != null) {
            followTask.cancel();
        }
        if (nudgeTask != null) {
            nudgeTask.cancel();
        }
    }

    private String loadPersonality() {
        try (InputStream in = plugin.getResource("llama-companion.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load llama-companion.md: " + e.getMessage());
        }
        return "You are a friendly llama companion.";
    }
}
