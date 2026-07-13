package org.xpfarm.ollama.prompts;

import org.xpfarm.ollama.OllamaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages system prompts for different Ollama actions
 * 
 * @author Carmelo Santana
 */
public class SystemPromptManager {
    
    private final OllamaPlugin plugin;
    private final Map<String, String> promptTemplates;
    
    public SystemPromptManager(OllamaPlugin plugin) {
        this.plugin = plugin;
        this.promptTemplates = new ConcurrentHashMap<>();
        loadPromptTemplates();
    }
    
    /**
     * Load system prompt templates from resources
     */
    private void loadPromptTemplates() {
        String[] promptFiles = {"ollama-say.md", "ollama-chat.md", "ollama-code.md", "ollama-run.md"};
        
        for (String fileName : promptFiles) {
            try {
                String promptName = fileName.replace("ollama-", "").replace(".md", "");
                String content = loadResourceFile(fileName);
                if (content != null) {
                    promptTemplates.put(promptName, content);
                    plugin.debugLog("Loaded system prompt for: " + promptName);
                } else {
                    plugin.getLogger().warning("Failed to load system prompt: " + fileName);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading prompt template " + fileName + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Load a resource file as string
     * 
     * @param fileName The resource file name
     * @return The file content as string, or null if not found
     */
    private String loadResourceFile(String fileName) {
        try (InputStream inputStream = plugin.getResource(fileName)) {
            if (inputStream == null) {
                return null;
            }
            
            byte[] bytes = inputStream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Error reading resource file " + fileName + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get system prompt for a specific action with context variables filled
     * 
     * @param action The action name (say, chat, code, run)
     * @param player The player making the request (can be null)
     * @return The formatted system prompt
     */
    public String getSystemPrompt(String action, Player player) {
        String template = promptTemplates.get(action);
        if (template == null) {
            plugin.debugLog("No system prompt template found for action: " + action);
            return null;
        }
        
        return fillContextVariables(template, player);
    }
    
    /**
     * Get raw system prompt template for a specific action
     * 
     * @param action The action name
     * @return The raw template, or null if not found
     */
    public String getRawPromptTemplate(String action) {
        return promptTemplates.get(action);
    }
    
    /**
     * Fill context variables in a prompt template
     * 
     * @param template The prompt template
     * @param player The player (can be null)
     * @return The template with variables filled
     */
    private String fillContextVariables(String template, Player player) {
        String result = template;
        
        // Get player context variables
        String logs = getPlayerLogs(player);
        String playerName = player != null ? player.getName() : "Console";
        String playerActions = getPlayerActions(player);
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        
        // Replace placeholders
        result = result.replace("<INSERT_LOGS_HERE>", logs);
        result = result.replace("<PLAYER_NAME>", playerName);
        result = result.replace("<PLAYER_ACTIONS_OR_STATE>", playerActions);
        result = result.replace("<INTERACTIONS_OR_COMMANDS>", playerActions);
        result = result.replace("<ONLINE>", String.valueOf(onlinePlayers));
        result = result.replace("<MAX_PLAYERS>", String.valueOf(maxPlayers));
        result = result.replace("<ONLINE>/<MAX_PLAYERS>", onlinePlayers + "/" + maxPlayers);
        
        // Additional context variables for run action
        if (player != null) {
            String playerState = getPlayerState(player);
            result = result.replace("<LOCATION, HEALTH, INVENTORY SUMMARY, CURRENT TASK>", playerState);
            
            String visiblePlayers = getVisiblePlayers(player);
            result = result.replace("<IF ANY>", visiblePlayers);
        } else {
            result = result.replace("<LOCATION, HEALTH, INVENTORY SUMMARY, CURRENT TASK>", "Console user");
            result = result.replace("<IF ANY>", "None (console)");
        }
        
        // Session state for chat
        String sessionState = getSessionState(player);
        result = result.replace("<SUMMARIZED_PLAYER_ACTIONS_OR_TOPIC>", sessionState);
        
        return result;
    }
    
    /**
     * Get player logs for context
     * 
     * @param player The player
     * @return Formatted logs string
     */
    private String getPlayerLogs(Player player) {
        if (player == null) {
            return "No recent activity (console user)";
        }
        
        String logs = plugin.getLogManager().getPlayerContext(player);
        return logs.isEmpty() ? "No recent activity logged" : logs;
    }
    
    /**
     * Get player actions summary
     * 
     * @param player The player
     * @return Player actions string
     */
    private String getPlayerActions(Player player) {
        if (player == null) {
            return "Console operations";
        }
        
        // Get recent actions from log manager
        String context = plugin.getLogManager().getPlayerContext(player);
        if (context.isEmpty()) {
            return "No recent actions";
        }
        
        // Extract just the action types from the context
        String[] lines = context.split("\n");
        StringBuilder actions = new StringBuilder();
        for (String line : lines) {
            if (line.contains(":") && !line.startsWith("Recent activity")) {
                String action = line.substring(line.indexOf(":") + 1).trim();
                if (actions.length() > 0) {
                    actions.append(", ");
                }
                actions.append(action);
            }
        }
        
        return actions.length() > 0 ? actions.toString() : "No recent actions";
    }
    
    /**
     * Get player state information
     * 
     * @param player The player
     * @return Player state string
     */
    private String getPlayerState(Player player) {
        if (player == null) {
            return "Console user";
        }
        
        StringBuilder state = new StringBuilder();
        
        // Location
        state.append("Location: ").append(player.getWorld().getName())
             .append(" (").append((int)player.getLocation().getX())
             .append(", ").append((int)player.getLocation().getY())
             .append(", ").append((int)player.getLocation().getZ()).append("), ");
        
        // Health
        state.append("Health: ").append((int)player.getHealth()).append("/20, ");
        
        // Inventory summary (simplified)
        int itemCount = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i) != null) {
                itemCount++;
            }
        }
        state.append("Inventory: ").append(itemCount).append("/36 slots used, ");
        
        // Current task (based on recent logs)
        String recentActions = getPlayerActions(player);
        state.append("Recent task: ").append(recentActions);
        
        return state.toString();
    }
    
    /**
     * Get visible or interacting players
     * 
     * @param player The player
     * @return Visible players string
     */
    private String getVisiblePlayers(Player player) {
        if (player == null) {
            return "None (console)";
        }
        
        StringBuilder visible = new StringBuilder();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && 
                other.getWorld().equals(player.getWorld()) && 
                other.getLocation().distance(player.getLocation()) <= 50) {
                if (visible.length() > 0) {
                    visible.append(", ");
                }
                visible.append(other.getName());
            }
        }
        
        return visible.length() > 0 ? visible.toString() : "None nearby";
    }
    
    /**
     * Get session state for chat
     * 
     * @param player The player
     * @return Session state string
     */
    private String getSessionState(Player player) {
        if (player == null) {
            return "Console session";
        }
        
        // Try to get chat session info
        if (plugin.getChatManager().hasActiveSession(player)) {
            return "Active chat session with AI assistant";
        } else {
            String recentActions = getPlayerActions(player);
            return recentActions.isEmpty() ? "New session" : recentActions;
        }
    }
    
    /**
     * Check if a system prompt exists for the given action
     * 
     * @param action The action name
     * @return True if prompt exists
     */
    public boolean hasPrompt(String action) {
        return promptTemplates.containsKey(action);
    }
    
    /**
     * Get all available prompt actions
     * 
     * @return Set of action names
     */
    public String[] getAvailablePrompts() {
        return promptTemplates.keySet().toArray(new String[0]);
    }
}
