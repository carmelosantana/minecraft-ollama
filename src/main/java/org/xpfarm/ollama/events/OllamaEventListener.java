package org.xpfarm.ollama.events;

import org.xpfarm.ollama.OllamaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Event listener for plugin events
 */
public class OllamaEventListener implements Listener {
    
    private final OllamaPlugin plugin;
    
    public OllamaEventListener(OllamaPlugin plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        plugin.getLogManager().logEvent(event.getPlayer(), "chat", event.getMessage());
    }
    
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        plugin.getLogManager().logEvent(event.getPlayer(), "command", event.getMessage());
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getLogManager().logEvent(event.getPlayer(), "player_join", "Player joined");
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getLogManager().logEvent(event.getPlayer(), "player_quit", "Player left");
    }
    
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        plugin.getLogManager().logEvent(event.getPlayer(), "block_break", 
                "Broke " + event.getBlock().getType().name() + " at " + formatLocation(event.getBlock().getLocation()));
    }
    
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        plugin.getLogManager().logEvent(event.getPlayer(), "block_place", 
                "Placed " + event.getBlock().getType().name() + " at " + formatLocation(event.getBlock().getLocation()));
    }
    
    private String formatLocation(org.bukkit.Location location) {
        return String.format("(%d, %d, %d)", location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
