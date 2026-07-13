package org.xpfarm.ollama.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired when a request is made to the Ollama API
 */
public class OllamaRequestEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    
    private final Player player;
    private final Object request;
    private boolean cancelled = false;
    
    public OllamaRequestEvent(Player player, Object request) {
        this.player = player;
        this.request = request;
    }
    
    public Player getPlayer() {
        return player;
    }
    
    public Object getRequest() {
        return request;
    }
    
    public boolean isCancelled() {
        return cancelled;
    }
    
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
    
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
    
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
