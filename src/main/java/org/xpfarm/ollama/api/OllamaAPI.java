package org.xpfarm.ollama.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.models.GenerateRequest;
import org.xpfarm.ollama.api.models.GenerateResponse;
import org.xpfarm.ollama.api.models.ChatRequest;
import org.xpfarm.ollama.api.models.ChatResponse;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.events.OllamaRequestEvent;
import org.xpfarm.ollama.events.OllamaResponseEvent;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Main API class for interacting with Ollama
 * 
 * This class provides methods for generating text, chat completions,
 * and managing the connection to the Ollama server.
 * 
 * @author Carmelo Santana
 */
public class OllamaAPI {
    
    private final OllamaPlugin plugin;
    private final Gson gson;
    private final ExecutorService executorService;
    private final CloseableHttpClient httpClient;
    
    private String endpoint;
    private String defaultModel;
    private int timeout;
    private int maxRetries;
    private double defaultTemperature;
    
    /**
     * Create a new OllamaAPI instance
     * 
     * @param plugin The plugin instance
     */
    public OllamaAPI(OllamaPlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();
        this.executorService = Executors.newFixedThreadPool(
            plugin.getConfig().getInt("performance.max_concurrent_requests", 5)
        );
        this.httpClient = HttpClients.createDefault();
        
        loadConfig();
    }
    
    /**
     * Load configuration from config.yml
     */
    private void loadConfig() {
        this.endpoint = plugin.getConfig().getString("api.endpoint", "http://localhost:11434");
        this.defaultModel = plugin.getConfig().getString("api.model", "llama3.2");
        this.timeout = plugin.getConfig().getInt("api.timeout", 30);
        this.maxRetries = plugin.getConfig().getInt("api.max_retries", 3);
        this.defaultTemperature = plugin.getConfig().getDouble("api.temperature", 0.7);
    }
    
    /**
     * Reload configuration
     */
    public void reloadConfig() {
        loadConfig();
        plugin.debugLog("API configuration reloaded");
    }
    
    /**
     * Test connection to Ollama API
     * 
     * @param callback Callback with success status and message
     */
    public void testConnection(BiConsumer<Boolean, String> callback) {
        CompletableFuture.runAsync(() -> {
            try {
                // Use GET request for version endpoint
                HttpGet get = new HttpGet(endpoint + "/api/version");
                get.setHeader("Accept", "application/json");
                
                CloseableHttpResponse response = httpClient.execute(get);
                int statusCode = response.getStatusLine().getStatusCode();
                
                if (statusCode == 200) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                    String version = json.get("version").getAsString();
                    
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        callback.accept(true, "Connected to Ollama v" + version)
                    );
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        callback.accept(false, "HTTP " + statusCode)
                    );
                }
                
                response.close();
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> 
                    callback.accept(false, e.getMessage())
                );
            }
        }, executorService);
    }
    
    /**
     * Generate text using Ollama
     * 
     * @param prompt The prompt to generate from
     * @param callback Callback with the generated response
     */
    public void generate(String prompt, Consumer<GenerateResponse> callback) {
        generate(prompt, null, callback);
    }
    
    /**
     * Generate text using Ollama
     * 
     * @param prompt The prompt to generate from
     * @param player The player making the request (for context)
     * @param callback Callback with the generated response
     */
    public void generate(String prompt, Player player, Consumer<GenerateResponse> callback) {
        GenerateRequest request = new GenerateRequest();
        request.setModel(defaultModel);
        request.setPrompt(prompt);
        request.setStream(false);
        request.setOptions(Map.of("temperature", defaultTemperature));
        
        generateWithRequest(request, player, callback);
    }
    
    /**
     * Generate text with a custom request
     * 
     * @param request The generate request
     * @param player The player making the request (for context)
     * @param callback Callback with the generated response
     */
    public void generateWithRequest(GenerateRequest request, Player player, Consumer<GenerateResponse> callback) {
        // Fire request event
        OllamaRequestEvent requestEvent = new OllamaRequestEvent(player, request);
        Bukkit.getPluginManager().callEvent(requestEvent);
        
        if (requestEvent.isCancelled()) {
            plugin.debugLog("Request cancelled by event");
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                String jsonRequest = gson.toJson(request);
                plugin.debugLog("Sending generate request: " + jsonRequest);
                
                HttpPost post = new HttpPost(endpoint + "/api/generate");
                post.setHeader("Content-Type", "application/json");
                post.setEntity(new StringEntity(jsonRequest));
                
                CloseableHttpResponse response = httpClient.execute(post);
                String responseBody = EntityUtils.toString(response.getEntity());
                
                plugin.debugLog("Received response: " + responseBody);
                
                // Check response status
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    plugin.getLogger().warning("API returned status code: " + statusCode);
                    plugin.getLogger().warning("Response body: " + responseBody);
                }
                
                GenerateResponse generateResponse = gson.fromJson(responseBody, GenerateResponse.class);
                
                // Fire response event
                Bukkit.getScheduler().runTask(plugin, () -> {
                    OllamaResponseEvent responseEvent = new OllamaResponseEvent(player, generateResponse);
                    Bukkit.getPluginManager().callEvent(responseEvent);
                    
                    callback.accept(generateResponse);
                });
                
                response.close();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error generating text", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    GenerateResponse errorResponse = new GenerateResponse();
                    errorResponse.setError("Failed to generate text: " + e.getMessage());
                    callback.accept(errorResponse);
                });
            }
        }, executorService);
    }
    
    /**
     * Generate chat completion using Ollama
     * 
     * @param messages The conversation messages
     * @param callback Callback with the chat response
     */
    public void chat(List<ChatMessage> messages, Consumer<ChatResponse> callback) {
        chat(messages, null, callback);
    }
    
    /**
     * Generate chat completion using Ollama
     * 
     * @param messages The conversation messages
     * @param player The player making the request (for context)
     * @param callback Callback with the chat response
     */
    public void chat(List<ChatMessage> messages, Player player, Consumer<ChatResponse> callback) {
        ChatRequest request = new ChatRequest();
        request.setModel(defaultModel);
        request.setMessages(messages);
        request.setStream(false);
        request.setOptions(Map.of("temperature", defaultTemperature));
        
        chatWithRequest(request, player, callback);
    }
    
    /**
     * Generate chat completion with a custom request
     * 
     * @param request The chat request
     * @param player The player making the request (for context)
     * @param callback Callback with the chat response
     */
    public void chatWithRequest(ChatRequest request, Player player, Consumer<ChatResponse> callback) {
        // Fire request event
        OllamaRequestEvent requestEvent = new OllamaRequestEvent(player, request);
        Bukkit.getPluginManager().callEvent(requestEvent);
        
        if (requestEvent.isCancelled()) {
            plugin.debugLog("Chat request cancelled by event");
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                String jsonRequest = gson.toJson(request);
                plugin.debugLog("Sending chat request: " + jsonRequest);
                
                HttpPost post = new HttpPost(endpoint + "/api/chat");
                post.setHeader("Content-Type", "application/json");
                post.setEntity(new StringEntity(jsonRequest));
                
                CloseableHttpResponse response = httpClient.execute(post);
                String responseBody = EntityUtils.toString(response.getEntity());
                
                plugin.debugLog("Received chat response: " + responseBody);
                
                // Check response status
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    plugin.getLogger().warning("Chat API returned status code: " + statusCode);
                    plugin.getLogger().warning("Response body: " + responseBody);
                }
                
                ChatResponse chatResponse = gson.fromJson(responseBody, ChatResponse.class);
                
                // Fire response event
                Bukkit.getScheduler().runTask(plugin, () -> {
                    OllamaResponseEvent responseEvent = new OllamaResponseEvent(player, chatResponse);
                    Bukkit.getPluginManager().callEvent(responseEvent);
                    
                    callback.accept(chatResponse);
                });
                
                response.close();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error generating chat", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ChatResponse errorResponse = new ChatResponse();
                    errorResponse.setError("Failed to generate chat: " + e.getMessage());
                    callback.accept(errorResponse);
                });
            }
        }, executorService);
    }
    
    /**
     * Generate text using context-aware system prompts
     * 
     * @param action The action type (say, code, run)
     * @param prompt The user prompt
     * @param player The player making the request
     * @param callback Callback with the generated response
     */
    public void generateWithSystemPrompt(String action, String prompt, Player player, Consumer<GenerateResponse> callback) {
        GenerateRequest request = new GenerateRequest();
        request.setModel(defaultModel);
        request.setPrompt(prompt);
        request.setStream(false);
        request.setOptions(Map.of("temperature", defaultTemperature));
        
        // Get system prompt from prompt manager
        String systemPrompt = plugin.getPromptManager().getSystemPrompt(action, player);
        if (systemPrompt != null) {
            request.setSystem(systemPrompt);
            plugin.debugLog("Using system prompt for action: " + action);
        }
        
        generateWithRequest(request, player, callback);
    }
    
    /**
     * Generate chat completion using context-aware system prompts
     * 
     * @param messages The conversation messages
     * @param player The player making the request
     * @param callback Callback with the chat response
     */
    public void chatWithSystemPrompt(List<ChatMessage> messages, Player player, Consumer<ChatResponse> callback) {
        ChatRequest request = new ChatRequest();
        request.setModel(defaultModel);
        request.setMessages(messages);
        request.setStream(false);
        request.setOptions(Map.of("temperature", defaultTemperature));
        
        // Get system prompt from prompt manager
        String systemPrompt = plugin.getPromptManager().getSystemPrompt("chat", player);
        if (systemPrompt != null) {
            request.setSystemPrompt(systemPrompt);
            plugin.debugLog("Using system prompt for chat");
        }
        
        chatWithRequest(request, player, callback);
    }

    /**
     * Generate text with context from player logs
     * 
     * @param prompt The prompt to generate from
     * @param player The player to get context for
     * @param callback Callback with the generated response
     */
    public void generateWithContext(String prompt, Player player, Consumer<GenerateResponse> callback) {
        if (player == null) {
            generate(prompt, callback);
            return;
        }
        
        // Get player context from log manager
        String context = plugin.getLogManager().getPlayerContext(player);
        
        String contextualPrompt = context.isEmpty() ? prompt : 
            "Context from recent game activity:\n" + context + "\n\nUser request: " + prompt;
        
        generate(contextualPrompt, player, callback);
    }
    
    /**
     * Shutdown the API and clean up resources
     */
    public void shutdown() {
        try {
            executorService.shutdown();
            httpClient.close();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error shutting down API", e);
        }
    }
    
    /**
     * Get the default model name
     * 
     * @return The default model name
     */
    public String getDefaultModel() {
        return defaultModel;
    }
    
    /**
     * Get the API endpoint
     * 
     * @return The API endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }
}
