package org.xpfarm.ollama.chat;

import org.xpfarm.ollama.OllamaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chat sessions for players
 */
public class ChatSessionManager {
    
    private final OllamaPlugin plugin;
    private final Map<UUID, ChatSession> sessions;
    
    private int maxHistory;
    private int sessionTimeout;
    
    public ChatSessionManager(OllamaPlugin plugin) {
        this.plugin = plugin;
        this.sessions = new ConcurrentHashMap<>();
        
        loadConfig();
        startCleanupTask();
    }
    
    private void loadConfig() {
        this.maxHistory = plugin.getConfig().getInt("chat.max_history", 10);
        this.sessionTimeout = plugin.getConfig().getInt("chat.session_timeout", 30);
    }
    
    public void reloadConfig() {
        loadConfig();
        plugin.debugLog("Chat session manager configuration reloaded");
    }
    
    /**
     * Get or create a chat session for a player
     * 
     * @param player The player
     * @return The chat session
     */
    public ChatSession getOrCreateSession(Player player) {
        UUID playerId = player.getUniqueId();
        ChatSession session = sessions.get(playerId);
        
        if (session == null || !session.isActive() || session.isExpired(sessionTimeout)) {
            session = new ChatSession(playerId.toString());
            sessions.put(playerId, session);
            plugin.debugLog("Created new chat session for " + player.getName());
        }
        
        return session;
    }
    
    /**
     * Get an existing chat session for a player
     * 
     * @param player The player
     * @return The chat session, or null if none exists
     */
    public ChatSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }
    
    /**
     * End a chat session for a player
     * 
     * @param player The player
     */
    public void endSession(Player player) {
        ChatSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.end();
            plugin.debugLog("Ended chat session for " + player.getName());
        }
    }
    
    /**
     * Check if a player has an active chat session
     * 
     * @param player The player
     * @return True if the player has an active session
     */
    public boolean hasActiveSession(Player player) {
        ChatSession session = sessions.get(player.getUniqueId());
        return session != null && session.isActive() && !session.isExpired(sessionTimeout);
    }
    
    /**
     * Get the number of active sessions
     * 
     * @return Number of active sessions
     */
    public int getActiveSessionCount() {
        return (int) sessions.values().stream()
                .filter(session -> session.isActive() && !session.isExpired(sessionTimeout))
                .count();
    }
    
    /**
     * Clear all sessions
     */
    public void clearAllSessions() {
        sessions.values().forEach(ChatSession::end);
        sessions.clear();
        plugin.debugLog("Cleared all chat sessions");
    }
    
    /**
     * Start the cleanup task to remove expired sessions
     */
    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupExpiredSessions();
            }
        }.runTaskTimer(plugin, 20 * 60, 20 * 60); // Run every minute
    }
    
    /**
     * Clean up expired sessions
     */
    private void cleanupExpiredSessions() {
        int removed = 0;
        var iterator = sessions.entrySet().iterator();
        
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ChatSession session = entry.getValue();
            
            if (!session.isActive() || session.isExpired(sessionTimeout)) {
                session.end();
                iterator.remove();
                removed++;
            }
        }
        
        if (removed > 0) {
            plugin.debugLog("Cleaned up " + removed + " expired chat sessions");
        }
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        clearAllSessions();
    }
    
    /**
     * Get the maximum history size
     * 
     * @return Maximum history size
     */
    public int getMaxHistory() {
        return maxHistory;
    }
    
    /**
     * Get the session timeout in minutes
     * 
     * @return Session timeout in minutes
     */
    public int getSessionTimeout() {
        return sessionTimeout;
    }
}
