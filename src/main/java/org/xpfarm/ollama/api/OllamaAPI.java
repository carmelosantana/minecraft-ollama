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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Bukkit-facing Ollama client.
 *
 * <p>All HTTP concerns — timeouts, the concurrency gate, retries, status dispatch — live in
 * {@link OllamaHttp}, which has no Bukkit reference and is therefore testable. This class is the
 * wiring: config, events, the async-to-main-thread hop, and {@code think} gating.
 *
 * @author Carmelo Santana
 */
public class OllamaAPI {

    private final OllamaPlugin plugin;
    private final Gson gson;
    private final ExecutorService executorService;
    private final OllamaHttp http;
    private final ModelCapabilities capabilities;

    private String endpoint;
    private String defaultModel;
    private int timeout;
    private int maxRetries;
    private double defaultTemperature;

    public OllamaAPI(OllamaPlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();

        // Default 1, matching OLLAMA_NUM_PARALLEL. Per Finding 3 a warm model does not reject
        // surplus requests, it blocks them indefinitely, so a higher client-side default would
        // stall requests rather than refuse them. Operators who raised OLLAMA_NUM_PARALLEL raise
        // this to match; see config.yml. Changing it needs a restart, not a /ollama reload.
        int permits = plugin.getConfig().getInt("performance.max_concurrent_requests", 1);

        // Headroom above the gate so an ungated metadata probe or a refused request still gets a
        // thread promptly instead of queueing behind in-flight generations.
        this.executorService = Executors.newFixedThreadPool(Math.max(1, permits) + 2);

        this.http = new OllamaHttp(permits, plugin.getLogger());
        this.capabilities = new ModelCapabilities(this.http, plugin.getLogger());

        loadConfig();
    }

    private void loadConfig() {
        this.endpoint = plugin.getConfig().getString("api.endpoint", "http://localhost:11434");
        this.defaultModel = plugin.getConfig().getString("api.model", "llama3.2");
        this.timeout = plugin.getConfig().getInt("api.timeout", 30);
        this.maxRetries = plugin.getConfig().getInt("api.max_retries", 3);
        this.defaultTemperature = plugin.getConfig().getDouble("api.temperature", 0.7);
        // Both values were read and then never used before 0.3.0. This line is what makes them real.
        this.http.configure(this.endpoint, this.timeout, this.maxRetries);
    }

    public void reloadConfig() {
        loadConfig();
        // api.model may have changed, and a stale capability answer would gate 'think' wrongly.
        this.capabilities.clear();
        plugin.debugLog("API configuration reloaded");
    }
    
    /**
     * Test connection to Ollama API
     * 
     * @param callback Callback with success status and message
     */
    public void testConnection(BiConsumer<Boolean, String> callback) {
        CompletableFuture.runAsync(() -> {
            String message;
            boolean ok;
            try {
                JsonObject json = JsonParser.parseString(http.get("/api/version")).getAsJsonObject();
                ok = true;
                message = "Connected to Ollama v" + json.get("version").getAsString();
            } catch (OllamaHttpException e) {
                ok = false;
                message = e.getPlayerMessage();
            } catch (RuntimeException e) {
                ok = false;
                message = "Unexpected response from Ollama: " + e.getMessage();
            }
            final boolean success = ok;
            final String result = message;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(success, result));
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
    public void generateWithRequest(GenerateRequest request, Player player,
            Consumer<GenerateResponse> callback) {
        OllamaRequestEvent requestEvent = new OllamaRequestEvent(player, request);
        Bukkit.getPluginManager().callEvent(requestEvent);
        if (requestEvent.isCancelled()) {
            plugin.debugLog("Request cancelled by event");
            return;
        }

        if (request.getModel() == null) {
            request.setModel(defaultModel);
        }
        applyThinkGating(request.getModel(), request::setThink);

        CompletableFuture.runAsync(() -> {
            GenerateResponse result;
            try {
                String json = gson.toJson(request);
                plugin.debugLog("Sending generate request: " + json);
                String body = http.postGated("/api/generate", json);
                plugin.debugLog("Received response: " + body);
                result = gson.fromJson(body, GenerateResponse.class);
            } catch (OllamaHttpException e) {
                logFailure("generate", e);
                result = new GenerateResponse();
                result.setError(e.getPlayerMessage());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Error generating text", e);
                result = new GenerateResponse();
                result.setError("Failed to generate text: " + e.getMessage());
            }
            final GenerateResponse delivered = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.getPluginManager().callEvent(new OllamaResponseEvent(player, delivered));
                callback.accept(delivered);
            });
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
        OllamaRequestEvent requestEvent = new OllamaRequestEvent(player, request);
        Bukkit.getPluginManager().callEvent(requestEvent);
        if (requestEvent.isCancelled()) {
            plugin.debugLog("Chat request cancelled by event");
            return;
        }

        if (request.getModel() == null) {
            request.setModel(defaultModel);
        }
        applyThinkGating(request.getModel(), request::setThink);

        CompletableFuture.runAsync(() -> {
            ChatResponse result;
            try {
                String json = gson.toJson(request);
                plugin.debugLog("Sending chat request: " + json);
                String body = http.postGated("/api/chat", json);
                plugin.debugLog("Received chat response: " + body);
                result = gson.fromJson(body, ChatResponse.class);
            } catch (OllamaHttpException e) {
                logFailure("chat", e);
                result = new ChatResponse();
                result.setError(e.getPlayerMessage());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Error generating chat", e);
                result = new ChatResponse();
                result.setError("Failed to generate chat: " + e.getMessage());
            }
            final ChatResponse delivered = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.getPluginManager().callEvent(new OllamaResponseEvent(player, delivered));
                callback.accept(delivered);
            });
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
     * Sets {@code think} only when the model is known to be thinking-capable.
     *
     * <p>Capable → {@code false} explicitly, because since Ollama v0.12.4 omitting the field means
     * {@code true} and we do not want to pay for reasoning tokens we discard. Not capable, or
     * unknown → the field is left null and Gson omits it, because sending {@code think} to a model
     * without the capability is a hard 400.
     */
    private void applyThinkGating(String model, Consumer<Boolean> setThink) {
        setThink.accept(Boolean.TRUE.equals(capabilities.supportsThinking(model)) ? Boolean.FALSE
                : null);
    }

    private void logFailure(String label, OllamaHttpException e) {
        switch (e.getAction()) {
            case MALFORMED_REQUEST ->
                    plugin.getLogger().log(Level.SEVERE,
                            "Ollama rejected the {0} request as malformed. This is a client bug, "
                                    + "not an operator problem: {1}",
                            new Object[] {label, e.getMessage()});
            case MODEL_MISSING ->
                    plugin.getLogger().log(Level.WARNING,
                            "Model not installed on the Ollama server ({0}): {1}",
                            new Object[] {label, e.getMessage()});
            case CANCELLED -> plugin.debugLog(label + " request cancelled: " + e.getMessage());
            default -> plugin.getLogger().log(Level.WARNING,
                    "Ollama {0} request failed: {1}", new Object[] {label, e.getMessage()});
        }
    }

    /**
     * Loads the configured model and warms its capability answer, so a player's first message
     * does not pay the cold-load cost. Never throws and never blocks the main thread; an
     * unreachable endpoint here is a logged line and nothing more.
     */
    public void preload() {
        CompletableFuture.runAsync(() -> {
            capabilities.supportsThinking(defaultModel);
            GenerateRequest request = new GenerateRequest();
            request.setModel(defaultModel);
            request.setPrompt("");
            request.setStream(false);
            try {
                http.postGated("/api/generate", gson.toJson(request));
                plugin.getLogger().info("Preloaded Ollama model " + defaultModel);
            } catch (OllamaHttpException e) {
                plugin.getLogger().info("Could not preload model " + defaultModel + ": "
                        + e.getPlayerMessage() + " The first request will pay the load cost.");
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Preload failed", e);
            }
        }, executorService);
    }

    /**
     * Shutdown the API and clean up resources
     */
    public void shutdown() {
        executorService.shutdownNow();
        http.close();
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
