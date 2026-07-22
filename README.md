# 🧠 Ollama Plugin for Minecraft

A Minecraft Paper plugin that provides an API wrapper for [Ollama](https://ollama.com), enabling players and other plugins to interact with large language models directly from within the game.

## Server

The public xpfarm.org Minecraft server is `play.xpfarm.org` — Java Edition and, via Geyser and
Floodgate, Bedrock Edition. Whether this plugin is enabled there is a deployment question, not a
property of this repository: it ships `enabled: false` and does nothing until an operator turns it
on and points it at a reachable Ollama endpoint.

Project home: <https://xpfarm.org>

## Features

### 🎮 Player Commands
- `/ollama say <prompt>` - Generate text using Ollama
- `/ollama code <prompt>` - Generate code with syntax highlighting
- `/ollama run <prompt>` - Generate and execute Minecraft commands
- `/ollama chat` - Start an interactive chat session
- `/ollama version` - Show plugin version and model info

### 🔧 Admin Commands
- `/ollama status` - Show plugin and API status
- `/ollama reload` - Reload configuration
- `/ollama test` - Test API connection
- `/ollama debug <on|off>` - Toggle debug mode

### 🛠️ Developer API
- Simple Java API for text generation
- Chat completion support
- Event-driven architecture
- Context-aware responses using player activity logs
- Async processing with callbacks

## Quick Start

### Prerequisites
- Java 25+
- Minecraft Paper 26.1.2+ (this is Minecraft Java **26.1** — Mojang moved to `YY.D[.H]` versioning
  in 2026 and 26.1 succeeded the 1.21 line)
- **ViaVersion — required, not optional.** Geyser emulates a Java 26.2 client against a 26.1.2
  server, and ViaVersion is what bridges that gap. It is declared as `softdepend` for load ordering
  only; Bedrock players cannot connect without it installed.
- [Ollama](https://ollama.com) running and reachable, if you want generation

### Installation

1. **Install Ollama**:
   ```bash
   # Install Ollama
   curl -fsSL https://ollama.com/install.sh | sh
   
   # Start Ollama server
   ollama serve
   
   # Pull a model
   ollama pull llama3.2
   ```

2. **Build the plugin**:
   ```bash
   git clone https://github.com/carmelosantana/minecraft-ollama.git
   cd minecraft-ollama
   make build
   ```

3. **Set up development server**:
   ```bash
   make setup
   make start
   ```

## Configuration

The plugin is configured via `config.yml`:

```yaml
# Ollama API Settings
api:
  endpoint: "http://localhost:11434"
  model: "llama3.2"
  timeout: 30
  temperature: 0.7

# Chat Settings
chat:
  max_history: 10
  session_timeout: 30

# Command Execution
commands:
  enable_execution: false
  blocked_commands:
    - "stop"
    - "op"
    - "ban"

# Performance
performance:
  max_concurrent_requests: 1
  rate_limit: 10

# Debug
debug:
  enabled: false
```

### Concurrency

`performance.max_concurrent_requests` defaults to `1`, matching Ollama's own `OLLAMA_NUM_PARALLEL`
default. Raise both together or neither: an already-loaded Ollama model does not reject surplus
requests, it blocks them on an internal semaphore indefinitely, so a higher client-side limit
converts "refused immediately" into "stalled until `api.timeout` fires". Requests beyond the limit
get an immediate message instead of a hang. Changing this value takes effect on server restart, not
on `/ollama reload`.

## Usage Examples

### Player Commands

```
/ollama say Write a creative story about a dragon
/ollama code Create a function to calculate fibonacci numbers
/ollama run Give me a diamond sword
/ollama chat
```

### Developer API

```java
// Simple text generation
OllamaAPI.generate("Hello, world!", response -> {
    if (!response.hasError()) {
        player.sendMessage(response.getResponse());
    }
});

// Chat completion
List<ChatMessage> messages = List.of(
    ChatMessage.user("How do I build a redstone clock?")
);

OllamaAPI.chat(messages, response -> {
    String reply = response.getContent();
    player.sendMessage(reply);
});

// Context-aware generation
OllamaAPI.generateWithContext("Help me with what I'm doing", player, response -> {
    // Uses recent player activity as context
    player.sendMessage(response.getResponse());
});
```

### Event Handling

```java
@EventHandler
public void onOllamaResponse(OllamaResponseEvent event) {
    Player player = event.getPlayer();
    GenerateResponse response = (GenerateResponse) event.getResponse();
    
    // Custom handling of Ollama responses
    if (response.getTokensPerSecond() > 50) {
        player.sendMessage("That was a fast response!");
    }
}
```

## Development

### Building

```bash
# Build plugin
make build

# Run tests
make test

# Development cycle (build + install + restart)
make dev

# Format code
make format
```

### Docker Testing

```bash
# Build and test in Docker
make docker-test

# View logs
docker-compose logs -f
```

### Debugging

```bash
# Interactive debug script
make debug

# Check status
make status

# View logs
make logs
```

## API Reference

### Core Classes

#### `OllamaAPI`
Main API class for interacting with Ollama.

**Methods:**
- `generate(String prompt, Consumer<GenerateResponse> callback)`
- `chat(List<ChatMessage> messages, Consumer<ChatResponse> callback)`
- `generateWithContext(String prompt, Player player, Consumer<GenerateResponse> callback)`
- `testConnection(BiConsumer<Boolean, String> callback)`

#### `ChatMessage`
Represents a chat message in a conversation.

**Static Methods:**
- `ChatMessage.user(String content)`
- `ChatMessage.assistant(String content)`
- `ChatMessage.system(String content)`

#### `GenerateResponse`
Response from text generation.

**Methods:**
- `getResponse()` - Get generated text
- `hasError()` - Check for errors
- `getTokensPerSecond()` - Get performance metrics

### Events

#### `OllamaRequestEvent`
Fired when a request is made to Ollama. Can be cancelled.

#### `OllamaResponseEvent`
Fired when a response is received from Ollama.

### Permissions

- `ollama.use` - Basic plugin usage (default: true)
- `ollama.generate` - Text generation (default: true)
- `ollama.code` - Code generation (default: true)
- `ollama.chat` - Chat interactions (default: true)
- `ollama.run` - Command execution (default: op)
- `ollama.admin` - Admin commands (default: op)
- `ollama.debug` - Debug commands (default: op)

## Integration Examples

### Other Plugin Integration

```java
// Get the Ollama API instance
OllamaPlugin ollamaPlugin = (OllamaPlugin) Bukkit.getPluginManager().getPlugin("Ollama");
OllamaAPI api = ollamaPlugin.getOllamaAPI();

// Use in your plugin
api.generate("Generate a quest for my RPG plugin", response -> {
    // Handle the generated quest
});
```

### Custom Context Provider

```java
@EventHandler
public void onOllamaRequest(OllamaRequestEvent event) {
    if (event.getRequest() instanceof GenerateRequest) {
        GenerateRequest request = (GenerateRequest) event.getRequest();
        
        // Add custom context
        String customContext = "Player is in region: " + getPlayerRegion(event.getPlayer());
        request.setSystem(customContext);
    }
}
```

## Performance Considerations

- **Rate Limiting**: Configurable per-player rate limits
- **Caching**: Optional response caching
- **Async Processing**: All API calls are asynchronous
- **Connection Pooling**: Reuses HTTP connections
- **Resource Management**: Automatic cleanup of expired sessions

## Troubleshooting

### Common Issues

1. **Plugin not loading**:
   - Check Java version (requires 21+)
   - Verify Paper version (requires 26.1.2+)
   - Check server logs for errors

2. **Ollama not responding**:
   - Ensure Ollama is running: `ollama serve`
   - Check endpoint configuration
   - Verify firewall settings

3. **Slow responses**:
   - Check system resources
   - Try a smaller model
   - Adjust temperature settings

### Debug Mode

Enable debug mode for detailed logging:

```bash
/ollama debug on
```

Or in config.yml:
```yaml
debug:
  enabled: true
  log_requests: true
  verbose_errors: true
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

Please follow the [development guidelines](CONTRIBUTING.md).

## License

This project is licensed under the [GNU Affero General Public License v3.0 or later](LICENSE).

## Support

- **Issues**: [GitHub Issues](https://github.com/carmelosantana/minecraft-ollama/issues)
- **Documentation**: [Wiki](https://github.com/carmelosantana/minecraft-ollama/wiki)
- **Maintainer**: [Carmelo Santana](https://github.com/carmelosantana)

## Credits

- **Ollama**: [ollama.com](https://ollama.com)
- **Paper**: [papermc.io](https://papermc.io)
- **Maintainer**: [Carmelo Santana](https://github.com/carmelosantana)

---

*Made with ❤️ by Carmelo Santana*
