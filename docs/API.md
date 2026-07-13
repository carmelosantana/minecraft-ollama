# 📚 Ollama Plugin API Documentation

This document provides comprehensive API documentation for developers who want to integrate with or extend the Ollama Plugin.

## Table of Contents

1. [Getting Started](#getting-started)
2. [Core API Classes](#core-api-classes)
3. [Request/Response Models](#requestresponse-models)
4. [Events](#events)
5. [Integration Examples](#integration-examples)
6. [Best Practices](#best-practices)
7. [Error Handling](#error-handling)

## Getting Started

### Adding as Dependency

Add the plugin as a dependency in your `plugin.yml`:

```yaml
depend: [Ollama]
# or
softdepend: [Ollama]
```

### Accessing the API

```java
import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.OllamaAPI;

// Get the plugin instance
OllamaPlugin ollamaPlugin = (OllamaPlugin) Bukkit.getPluginManager().getPlugin("Ollama");

if (ollamaPlugin != null && ollamaPlugin.isEnabled()) {
    OllamaAPI api = ollamaPlugin.getOllamaAPI();
    // Use the API...
}
```

## Core API Classes

### OllamaAPI

The main API class for interacting with Ollama.

#### Methods

##### `generate(String prompt, Consumer<GenerateResponse> callback)`

Generate text from a prompt.

```java
api.generate("Write a story about a dragon", response -> {
    if (!response.hasError()) {
        String text = response.getResponse();
        // Use the generated text
    }
});
```

##### `generate(String prompt, Player player, Consumer<GenerateResponse> callback)`

Generate text with player context.

```java
api.generate("Help me with what I'm doing", player, response -> {
    // Uses recent player activity as context
    player.sendMessage(response.getResponse());
});
```

##### `generateWithRequest(GenerateRequest request, Player player, Consumer<GenerateResponse> callback)`

Generate text with a custom request.

```java
GenerateRequest request = new GenerateRequest();
request.setModel("llama3.2");
request.setPrompt("Generate code for a Minecraft plugin");
request.setSystem("You are a helpful programming assistant");
request.setFormat("```java\n{code}\n```");

api.generateWithRequest(request, player, response -> {
    // Handle response
});
```

##### `chat(List<ChatMessage> messages, Consumer<ChatResponse> callback)`

Generate chat completion.

```java
List<ChatMessage> messages = Arrays.asList(
    ChatMessage.system("You are a Minecraft assistant"),
    ChatMessage.user("How do I make a redstone clock?")
);

api.chat(messages, response -> {
    String reply = response.getContent();
    // Use the reply
});
```

##### `generateWithContext(String prompt, Player player, Consumer<GenerateResponse> callback)`

Generate text using recent player activity as context.

```java
api.generateWithContext("What should I do next?", player, response -> {
    // Response considers recent player actions
    player.sendMessage(response.getResponse());
});
```

##### `testConnection(BiConsumer<Boolean, String> callback)`

Test the connection to Ollama.

```java
api.testConnection((success, message) -> {
    if (success) {
        plugin.getLogger().info("Ollama connected: " + message);
    } else {
        plugin.getLogger().warning("Ollama connection failed: " + message);
    }
});
```

## Request/Response Models

### GenerateRequest

Request model for text generation.

```java
GenerateRequest request = new GenerateRequest();
request.setModel("llama3.2");           // Model to use
request.setPrompt("Your prompt here");   // The prompt
request.setSystem("System message");     // System instructions
request.setTemplate("Template");         // Custom template
request.setFormat("json");              // Output format
request.setStream(false);               // Streaming mode
request.setRaw(false);                  // Raw mode
request.setKeep_alive("5m");            // Keep model in memory

// Advanced options
Map<String, Object> options = new HashMap<>();
options.put("temperature", 0.7);
options.put("top_p", 0.9);
options.put("max_tokens", 1000);
request.setOptions(options);
```

### GenerateResponse

Response from text generation.

```java
GenerateResponse response = /* ... */;

String text = response.getResponse();           // Generated text
boolean isDone = response.isDone();             // Completion status
boolean hasError = response.hasError();         // Error status
String error = response.getError();             // Error message
double tokensPerSec = response.getTokensPerSecond(); // Performance metric

// Timing information
long totalDuration = response.getTotal_duration();
long loadDuration = response.getLoad_duration();
long evalDuration = response.getEval_duration();
int evalCount = response.getEval_count();
```

### ChatMessage

Represents a message in a conversation.

```java
// Factory methods
ChatMessage userMsg = ChatMessage.user("Hello!");
ChatMessage assistantMsg = ChatMessage.assistant("Hi there!");
ChatMessage systemMsg = ChatMessage.system("You are helpful");

// Manual creation
ChatMessage message = new ChatMessage();
message.setRole("user");
message.setContent("How are you?");
```

### ChatRequest

Request model for chat completion.

```java
ChatRequest request = new ChatRequest();
request.setModel("llama3.2");
request.setMessages(messageList);
request.setStream(false);
request.setFormat("json");

Map<String, Object> options = new HashMap<>();
options.put("temperature", 0.8);
request.setOptions(options);
```

### ChatResponse

Response from chat completion.

```java
ChatResponse response = /* ... */;

ChatMessage message = response.getMessage();    // Assistant's message
String content = response.getContent();         // Message content
boolean isDone = response.isDone();             // Completion status
boolean hasError = response.hasError();         // Error status
double tokensPerSec = response.getTokensPerSecond(); // Performance
```

## Events

### OllamaRequestEvent

Fired when a request is made to Ollama.

```java
@EventHandler
public void onOllamaRequest(OllamaRequestEvent event) {
    Player player = event.getPlayer();
    Object request = event.getRequest();
    
    // Modify request or cancel
    if (someCondition) {
        event.setCancelled(true);
    }
    
    // Add custom context for generate requests
    if (request instanceof GenerateRequest) {
        GenerateRequest genRequest = (GenerateRequest) request;
        genRequest.setSystem("Custom system message for " + player.getName());
    }
}
```

### OllamaResponseEvent

Fired when a response is received from Ollama.

```java
@EventHandler
public void onOllamaResponse(OllamaResponseEvent event) {
    Player player = event.getPlayer();
    Object response = event.getResponse();
    
    // Handle different response types
    if (response instanceof GenerateResponse) {
        GenerateResponse genResponse = (GenerateResponse) response;
        
        // Log performance metrics
        if (genResponse.getTokensPerSecond() > 50) {
            plugin.getLogger().info("Fast response for " + player.getName());
        }
    }
}
```

## Integration Examples

### Simple Text Generation Plugin

```java
public class MyTextPlugin extends JavaPlugin {
    private OllamaAPI ollamaAPI;
    
    @Override
    public void onEnable() {
        Plugin ollamaPlugin = getServer().getPluginManager().getPlugin("Ollama");
        if (ollamaPlugin != null) {
            this.ollamaAPI = ((OllamaPlugin) ollamaPlugin).getOllamaAPI();
        }
    }
    
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        
        if (message.startsWith("!ai ")) {
            String prompt = message.substring(4);
            Player player = event.getPlayer();
            
            ollamaAPI.generate(prompt, player, response -> {
                if (!response.hasError()) {
                    player.sendMessage("AI: " + response.getResponse());
                }
            });
        }
    }
}
```

### Quest Generation Plugin

```java
public class QuestGeneratorPlugin extends JavaPlugin {
    private OllamaAPI ollamaAPI;
    
    public void generateQuest(Player player, String theme) {
        String prompt = "Generate a Minecraft quest with theme: " + theme + 
                       ". Include objective, description, and rewards.";
        
        GenerateRequest request = new GenerateRequest();
        request.setPrompt(prompt);
        request.setSystem("You are a creative quest designer for Minecraft");
        request.setFormat("json");
        
        ollamaAPI.generateWithRequest(request, player, response -> {
            if (!response.hasError()) {
                try {
                    // Parse JSON response and create quest
                    JsonObject quest = JsonParser.parseString(response.getResponse()).getAsJsonObject();
                    createQuest(player, quest);
                } catch (Exception e) {
                    player.sendMessage("Failed to generate quest");
                }
            }
        });
    }
}
```

### Context-Aware Assistant

```java
public class AssistantPlugin extends JavaPlugin {
    
    @EventHandler
    public void onOllamaRequest(OllamaRequestEvent event) {
        if (event.getRequest() instanceof GenerateRequest) {
            GenerateRequest request = (GenerateRequest) event.getRequest();
            Player player = event.getPlayer();
            
            // Add custom context
            String context = getPlayerContext(player);
            String enhancedPrompt = request.getPrompt() + "\n\nContext: " + context;
            request.setPrompt(enhancedPrompt);
        }
    }
    
    private String getPlayerContext(Player player) {
        StringBuilder context = new StringBuilder();
        
        // Add location context
        Location loc = player.getLocation();
        context.append("Player is at ").append(loc.getWorld().getName())
               .append(" (").append(loc.getBlockX()).append(", ")
               .append(loc.getBlockY()).append(", ").append(loc.getBlockZ()).append(")");
        
        // Add inventory context
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType() != Material.AIR) {
            context.append(". Holding ").append(mainHand.getType().name());
        }
        
        return context.toString();
    }
}
```

### Chat Bot Plugin

```java
public class ChatBotPlugin extends JavaPlugin {
    private final Map<UUID, List<ChatMessage>> conversations = new HashMap<>();
    
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        
        if (message.startsWith("@bot ")) {
            event.setCancelled(true);
            
            String userMessage = message.substring(5);
            Player player = event.getPlayer();
            
            // Get or create conversation
            List<ChatMessage> conversation = conversations.computeIfAbsent(
                player.getUniqueId(), k -> new ArrayList<>()
            );
            
            // Add user message
            conversation.add(ChatMessage.user(userMessage));
            
            // Limit conversation length
            if (conversation.size() > 20) {
                conversation.subList(0, conversation.size() - 20).clear();
            }
            
            // Get AI response
            ollamaAPI.chat(conversation, response -> {
                if (!response.hasError()) {
                    String reply = response.getContent();
                    conversation.add(ChatMessage.assistant(reply));
                    
                    player.sendMessage("Bot: " + reply);
                }
            });
        }
    }
}
```

## Best Practices

### Performance

1. **Use Async Callbacks**: All API calls are asynchronous
2. **Limit Concurrent Requests**: Respect rate limits
3. **Cache Responses**: Cache frequently requested content
4. **Optimize Prompts**: Use clear, concise prompts

### Error Handling

```java
api.generate(prompt, response -> {
    if (response.hasError()) {
        // Log error
        plugin.getLogger().warning("Generation failed: " + response.getError());
        
        // Provide fallback
        player.sendMessage("Sorry, I couldn't generate a response right now.");
        return;
    }
    
    // Handle successful response
    player.sendMessage(response.getResponse());
});
```

### Context Management

```java
// Add relevant context to improve responses
String contextualPrompt = buildContextualPrompt(player, originalPrompt);

private String buildContextualPrompt(Player player, String prompt) {
    StringBuilder enhanced = new StringBuilder();
    
    // Add player context
    enhanced.append("Player: ").append(player.getName()).append("\n");
    enhanced.append("Location: ").append(formatLocation(player.getLocation())).append("\n");
    enhanced.append("Gamemode: ").append(player.getGameMode()).append("\n\n");
    enhanced.append("Request: ").append(prompt);
    
    return enhanced.toString();
}
```

### Resource Management

```java
// Clean up resources properly
@Override
public void onDisable() {
    // Cancel ongoing tasks
    if (scheduledTask != null) {
        scheduledTask.cancel();
    }
    
    // Clear caches
    responseCache.clear();
}
```

## Error Handling

### Common Error Scenarios

1. **Connection Errors**: Ollama server unavailable
2. **Model Errors**: Requested model not available
3. **Rate Limiting**: Too many concurrent requests
4. **Timeout Errors**: Request took too long
5. **Parse Errors**: Invalid JSON format requested

### Error Response Handling

```java
api.generate(prompt, response -> {
    if (response.hasError()) {
        String error = response.getError();
        
        if (error.contains("connection")) {
            // Connection issue
            player.sendMessage("AI service is temporarily unavailable");
        } else if (error.contains("model")) {
            // Model issue
            player.sendMessage("AI model is not available");
        } else if (error.contains("timeout")) {
            // Timeout
            player.sendMessage("Request timed out, please try again");
        } else {
            // Generic error
            player.sendMessage("An error occurred: " + error);
        }
        
        return;
    }
    
    // Handle successful response
    handleSuccessfulResponse(response);
});
```

### Retry Logic

```java
private void generateWithRetry(String prompt, Player player, int maxRetries) {
    generateWithRetry(prompt, player, maxRetries, 0);
}

private void generateWithRetry(String prompt, Player player, int maxRetries, int attempt) {
    api.generate(prompt, response -> {
        if (response.hasError() && attempt < maxRetries) {
            // Retry after delay
            Bukkit.getScheduler().runTaskLater(this, () -> {
                generateWithRetry(prompt, player, maxRetries, attempt + 1);
            }, 20L * (attempt + 1)); // Exponential backoff
        } else if (response.hasError()) {
            // Max retries reached
            player.sendMessage("Failed to generate response after " + maxRetries + " attempts");
        } else {
            // Success
            player.sendMessage(response.getResponse());
        }
    });
}
```

## Rate Limiting

The plugin includes built-in rate limiting. You can also implement your own:

```java
private final Map<UUID, Long> lastRequestTime = new HashMap<>();
private final long REQUEST_COOLDOWN = 5000; // 5 seconds

private boolean canMakeRequest(Player player) {
    UUID playerId = player.getUniqueId();
    long currentTime = System.currentTimeMillis();
    long lastTime = lastRequestTime.getOrDefault(playerId, 0L);
    
    if (currentTime - lastTime < REQUEST_COOLDOWN) {
        long remainingTime = (REQUEST_COOLDOWN - (currentTime - lastTime)) / 1000;
        player.sendMessage("Please wait " + remainingTime + " seconds before making another request");
        return false;
    }
    
    lastRequestTime.put(playerId, currentTime);
    return true;
}
```

---

For more examples and advanced usage, check the [GitHub repository](https://github.com/carmelosantana/minecraft-ollama) and [wiki](https://github.com/carmelosantana/minecraft-ollama/wiki).
