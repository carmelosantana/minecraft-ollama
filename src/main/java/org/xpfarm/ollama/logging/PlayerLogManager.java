package org.xpfarm.ollama.logging;

import org.xpfarm.ollama.OllamaPlugin;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player activity logs for context generation
 */
public class PlayerLogManager {
    
    private final OllamaPlugin plugin;
    private final Map<UUID, List<LogEntry>> playerLogs;
    private final DateTimeFormatter timeFormatter;
    
    private int maxLogEntries;
    private Set<String> loggedEventTypes;
    private boolean includeTimestamps;
    
    public PlayerLogManager(OllamaPlugin plugin) {
        this.plugin = plugin;
        this.playerLogs = new ConcurrentHashMap<>();
        this.timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        
        loadConfig();
    }
    
    private void loadConfig() {
        this.maxLogEntries = plugin.getConfig().getInt("logging.max_log_entries", 10);
        this.loggedEventTypes = new HashSet<>(plugin.getConfig().getStringList("logging.log_types"));
        this.includeTimestamps = plugin.getConfig().getBoolean("logging.include_timestamps", true);
    }
    
    public void reloadConfig() {
        loadConfig();
        plugin.debugLog("Log manager configuration reloaded");
    }
    
    /**
     * Log a player event
     * 
     * @param player The player
     * @param eventType The type of event
     * @param message The event message
     */
    public void logEvent(Player player, String eventType, String message) {
        if (!loggedEventTypes.contains(eventType)) {
            return;
        }
        
        UUID playerId = player.getUniqueId();
        List<LogEntry> logs = playerLogs.computeIfAbsent(playerId, k -> new ArrayList<>());
        
        LogEntry entry = new LogEntry(eventType, message, LocalDateTime.now());
        logs.add(entry);
        
        // Keep only the most recent entries
        if (logs.size() > maxLogEntries) {
            logs.remove(0);
        }
        
        plugin.debugLog("Logged event for " + player.getName() + ": " + eventType + " - " + message);
    }
    
    /**
     * Get formatted context for a player
     * 
     * @param player The player
     * @return Formatted context string
     */
    public String getPlayerContext(Player player) {
        List<LogEntry> logs = playerLogs.get(player.getUniqueId());
        if (logs == null || logs.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder();
        context.append("Recent activity for ").append(player.getName()).append(":\n");
        
        for (LogEntry entry : logs) {
            if (includeTimestamps) {
                context.append("[").append(entry.getTimestamp().format(timeFormatter)).append("] ");
            }
            context.append(entry.getEventType()).append(": ").append(entry.getMessage()).append("\n");
        }
        
        return context.toString();
    }
    
    /**
     * Get raw log entries for a player
     * 
     * @param player The player
     * @return List of log entries
     */
    public List<LogEntry> getPlayerLogs(Player player) {
        return playerLogs.getOrDefault(player.getUniqueId(), new ArrayList<>());
    }
    
    /**
     * Clear logs for a player
     * 
     * @param player The player
     */
    public void clearPlayerLogs(Player player) {
        playerLogs.remove(player.getUniqueId());
        plugin.debugLog("Cleared logs for " + player.getName());
    }
    
    /**
     * Clear all logs
     */
    public void clearAllLogs() {
        playerLogs.clear();
        plugin.debugLog("Cleared all player logs");
    }
    
    /**
     * Get the number of logged players
     * 
     * @return Number of players with logs
     */
    public int getLoggedPlayerCount() {
        return playerLogs.size();
    }
    
    /**
     * Get total number of log entries across all players
     * 
     * @return Total log entries
     */
    public int getTotalLogEntries() {
        return playerLogs.values().stream().mapToInt(List::size).sum();
    }
    
    /**
     * Log entry class
     */
    public static class LogEntry {
        private final String eventType;
        private final String message;
        private final LocalDateTime timestamp;
        
        public LogEntry(String eventType, String message, LocalDateTime timestamp) {
            this.eventType = eventType;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        public String getEventType() {
            return eventType;
        }
        
        public String getMessage() {
            return message;
        }
        
        public LocalDateTime getTimestamp() {
            return timestamp;
        }
    }
}
