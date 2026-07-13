package org.xpfarm.ollama;

import org.xpfarm.ollama.api.OllamaAPI;
import org.xpfarm.ollama.commands.OllamaCommand;
import org.xpfarm.ollama.events.OllamaEventListener;
import org.xpfarm.ollama.logging.PlayerLogManager;
import org.xpfarm.ollama.chat.ChatSessionManager;
import org.xpfarm.ollama.prompts.SystemPromptManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Main plugin class for the Ollama Integration Framework
 * 
 * This plugin provides a bridge between Minecraft and the Ollama API,
 * allowing other plugins and players to interact with language models
 * directly from within the game.
 * 
 * @author Carmelo Santana
 * @version 0.1.2
 */
public class OllamaPlugin extends JavaPlugin {
    
    private static OllamaPlugin instance;
    private OllamaAPI ollamaAPI;
    private PlayerLogManager logManager;
    private ChatSessionManager chatManager;
    private SystemPromptManager promptManager;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Save default configuration
        saveDefaultConfig();

        if (!getConfig().getBoolean("enabled", false)) {
            getLogger().info("Ollama integration is disabled; no API client or listeners were started.");
            return;
        }
        
        // Initialize components
        initializeComponents();
        
        // Register commands
        registerCommands();
        
        // Register events
        registerEvents();
        
        // Log startup message
        getLogger().info("Ollama Plugin v" + getDescription().getVersion() + " enabled!");
        logStartupInfo();
    }
    
    @Override
    public void onDisable() {
        // Clean up resources
        if (chatManager != null) {
            chatManager.cleanup();
        }
        
        if (ollamaAPI != null) {
            ollamaAPI.shutdown();
        }
        
        getLogger().info("Ollama Plugin disabled!");
        instance = null;
    }
    
    /**
     * Initialize all plugin components
     */
    private void initializeComponents() {
        try {
            // Initialize API
            ollamaAPI = new OllamaAPI(this);
            
            // Initialize managers
            logManager = new PlayerLogManager(this);
            chatManager = new ChatSessionManager(this);
            promptManager = new SystemPromptManager(this);
            
            getLogger().info("All components initialized successfully");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize plugin components", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    
    /**
     * Register plugin commands
     */
    private void registerCommands() {
        OllamaCommand command = new OllamaCommand(this);
        getCommand("ollama").setExecutor(command);
        getCommand("ollama").setTabCompleter(command);
    }
    
    /**
     * Register event listeners
     */
    private void registerEvents() {
        getServer().getPluginManager().registerEvents(new OllamaEventListener(this), this);
    }
    
    /**
     * Log startup information
     */
    private void logStartupInfo() {
        getLogger().info("=== Ollama Plugin Configuration ===");
        getLogger().info("API Endpoint: " + getConfig().getString("api.endpoint", "http://localhost:11434"));
        getLogger().info("Default Model: " + getConfig().getString("api.model", "llama3.2"));
        getLogger().info("Command Execution: " + (getConfig().getBoolean("commands.enable_execution", false) ? "Enabled" : "Disabled"));
        getLogger().info("Debug Mode: " + (getConfig().getBoolean("debug.enabled", false) ? "Enabled" : "Disabled"));
        getLogger().info("===================================");
        
        // Test API connection
        testAPIConnection();
    }
    
    /**
     * Test connection to Ollama API
     */
    private void testAPIConnection() {
        ollamaAPI.testConnection((success, message) -> {
            if (success) {
                getLogger().info("✅ Successfully connected to Ollama API");
            } else {
                getLogger().warning("Ollama API is currently unavailable: " + message
                        + ". Minecraft will continue normally; use /ollama test after the service recovers.");
            }
        });
    }
    
    /**
     * Get the plugin instance
     * 
     * @return The plugin instance
     */
    public static OllamaPlugin getInstance() {
        return instance;
    }
    
    /**
     * Get the Ollama API instance
     * 
     * @return The Ollama API instance
     */
    public OllamaAPI getOllamaAPI() {
        return ollamaAPI;
    }
    
    /**
     * Get the player log manager
     * 
     * @return The player log manager
     */
    public PlayerLogManager getLogManager() {
        return logManager;
    }
    
    /**
     * Get the chat session manager
     * 
     * @return The chat session manager
     */
    public ChatSessionManager getChatManager() {
        return chatManager;
    }
    
    /**
     * Get the system prompt manager
     * 
     * @return The system prompt manager
     */
    public SystemPromptManager getPromptManager() {
        return promptManager;
    }
    
    /**
     * Reload the plugin configuration
     */
    public void reloadPluginConfig() {
        reloadConfig();
        
        // Reinitialize components with new config
        if (ollamaAPI != null) {
            ollamaAPI.reloadConfig();
        }
        
        if (logManager != null) {
            logManager.reloadConfig();
        }
        
        if (chatManager != null) {
            chatManager.reloadConfig();
        }
        
        getLogger().info("Configuration reloaded successfully");
    }
    
    /**
     * Check if debug mode is enabled
     * 
     * @return True if debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return getConfig().getBoolean("debug.enabled", false);
    }
    
    /**
     * Log debug message if debug mode is enabled
     * 
     * @param message The debug message
     */
    public void debugLog(String message) {
        if (isDebugEnabled()) {
            getLogger().info("[DEBUG] " + message);
        }
    }
    
    /**
     * Send a formatted message to console
     * 
     * @param message The message to send
     * @param color The color of the message
     */
    public void sendConsoleMessage(String message, NamedTextColor color) {
        getServer().getConsoleSender().sendMessage(
            Component.text("[Ollama] " + message, color)
        );
    }
}
