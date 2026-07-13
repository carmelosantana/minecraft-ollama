package org.xpfarm.ollama.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired when a response is received from the Ollama API
 */
public class OllamaResponseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    
    private final Player player;
    private final Object response;
    
    public OllamaResponseEvent(Player player, Object response) {
        this.player = player;
        this.response = response;
    }
    
    public Player getPlayer() {
        return player;
    }
    
    public Object getResponse() {
        return response;
    }
    
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
    
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
