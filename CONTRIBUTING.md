# Contributing to Ollama Plugin

Thank you for your interest in contributing to the Ollama Plugin! This guide will help you get started with development and contribution.

## Development Setup

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker and Docker Compose
- Git
- Ollama (for testing)

### Environment Setup

1. **Fork and clone the repository**:
   ```bash
   git clone https://github.com/yourusername/minecraft-ollama.git
   cd minecraft-ollama
   ```

2. **Install dependencies**:
   ```bash
   # Install Ollama
   curl -fsSL https://ollama.com/install.sh | sh
   
   # Start Ollama and pull default model
   ollama serve &
   ollama pull llama3.2
   ```

3. **Build the project**:
   ```bash
   make build
   ```

4. **Set up development server**:
   ```bash
   make setup
   make start
   ```

## Development Guidelines

### Code Style

- Follow standard Java conventions
- Use descriptive variable and method names
- Add JavaDoc comments for public methods
- Keep methods focused and concise
- Use consistent formatting (4 spaces for indentation)

### Architecture

- **Plugin Structure**: Follow the existing package structure
- **API Design**: Keep the public API simple and intuitive
- **Event System**: Use Bukkit events for plugin communication
- **Async Processing**: All API calls should be asynchronous
- **Error Handling**: Provide meaningful error messages

### Testing

#### Unit Tests
```bash
# Run all tests
make test

# Run specific test class
mvn test -Dtest=OllamaAPITest
```

#### Integration Tests
```bash
# Test with Docker
make docker-test

# Manual testing
make debug
```

#### In-Game Testing
```bash
# Start development server
make dev

# Test commands in-game
/ollama test
/ollama version
/ollama say Hello, world!
```

### Code Quality

#### Linting
```bash
# Check shell scripts
make lint

# Format Java code
make format
```

#### Performance
- Use async processing for API calls
- Implement proper caching where appropriate
- Monitor memory usage
- Optimize for high-concurrency scenarios

### Documentation

- Update README.md for new features
- Add JavaDoc for public API methods
- Update configuration examples
- Include usage examples

### Commit Guidelines

#### Commit Messages
Use conventional commit format:
```
<type>(<scope>): <description>

<body>

<footer>
```

Types:
- `feat`: New features
- `fix`: Bug fixes
- `docs`: Documentation updates
- `style`: Code style changes
- `refactor`: Code refactoring
- `test`: Test additions/modifications
- `chore`: Maintenance tasks

Examples:
```
feat(api): add chat completion support
fix(commands): resolve permission check issue
docs(readme): update installation instructions
```

#### Branch Naming
- `feature/description` - New features
- `bugfix/description` - Bug fixes
- `hotfix/description` - Critical fixes
- `docs/description` - Documentation updates

## Contribution Process

### 1. Create an Issue
Before starting work, create an issue describing:
- The problem or feature request
- Expected behavior
- Proposed solution (if applicable)

### 2. Fork and Branch
```bash
# Fork the repository on GitHub
git clone https://github.com/yourusername/minecraft-ollama.git
cd minecraft-ollama

# Create feature branch
git checkout -b feature/my-new-feature
```

### 3. Develop and Test
```bash
# Make changes
# Add tests
# Ensure all tests pass
make test

# Test manually
make dev
```

### 4. Submit Pull Request
1. Push changes to your fork
2. Create pull request with:
   - Clear description of changes
   - Link to related issues
   - Screenshots (if UI changes)
   - Test results

### 5. Code Review
- Address reviewer feedback
- Update documentation if needed
- Ensure CI passes

## API Development

### Adding New Endpoints

1. **Create Request/Response Models**:
   ```java
   // In api/models/
   public class NewRequest {
       private String parameter;
       // getters/setters
   }
   ```

2. **Add API Method**:
   ```java
   // In OllamaAPI.java
   public void newMethod(NewRequest request, Consumer<NewResponse> callback) {
       // Implementation
   }
   ```

3. **Add Command Handler**:
   ```java
   // In commands/OllamaCommand.java
   private void handleNewCommand(CommandSender sender, String[] args) {
       // Implementation
   }
   ```

4. **Update Configuration**:
   ```yaml
   # In config.yml
   new_feature:
     enabled: true
     settings: value
   ```

### Plugin Integration

For developers integrating with the plugin:

```java
// Check if plugin is available
Plugin ollamaPlugin = Bukkit.getPluginManager().getPlugin("Ollama");
if (ollamaPlugin != null && ollamaPlugin.isEnabled()) {
    OllamaAPI api = ((OllamaPlugin) ollamaPlugin).getOllamaAPI();
    
    // Use the API
    api.generate("Hello from my plugin!", response -> {
        // Handle response
    });
}
```

## Common Development Tasks

### Adding a New Command

1. **Update plugin.yml**:
   ```yaml
   permissions:
     ollama.newcommand:
       description: New command permission
       default: true
   ```

2. **Add to Command Handler**:
   ```java
   private void handleNewCommand(CommandSender sender, String[] args) {
       if (!sender.hasPermission("ollama.newcommand")) {
           sender.sendMessage("No permission");
           return;
       }
       // Implementation
   }
   ```

3. **Update Tab Completion**:
   ```java
   private final List<String> subcommands = Arrays.asList(
       "help", "version", "newcommand" // Add here
   );
   ```

### Adding Configuration Options

1. **Update config.yml**:
   ```yaml
   new_section:
     option1: default_value
     option2: true
   ```

2. **Load in Plugin**:
   ```java
   private void loadConfig() {
       this.newOption = getConfig().getString("new_section.option1", "default");
   }
   ```

### Adding Event Handlers

1. **Create Event Class**:
   ```java
   public class NewEvent extends Event {
       private static final HandlerList HANDLERS = new HandlerList();
       // Implementation
   }
   ```

2. **Fire Event**:
   ```java
   NewEvent event = new NewEvent(data);
   Bukkit.getPluginManager().callEvent(event);
   ```

## Debugging

### Local Debugging

1. **Enable Debug Mode**:
   ```bash
   /ollama debug on
   ```

2. **View Logs**:
   ```bash
   make logs
   ```

3. **Interactive Debug**:
   ```bash
   make debug
   ```

### Docker Debugging

```bash
# Start with logs
docker-compose up

# Execute commands in container
docker-compose exec minecraft bash

# View specific service logs
docker-compose logs ollama
```

## Release Process

### Version Bumping

1. **Update version in pom.xml**:
   ```xml
   <version>1.0.2</version>
   ```

2. **Update plugin.yml**:
   ```yaml
   version: 1.0.2
   ```

3. **Create release**:
   ```bash
   make release
   ```

### Documentation Updates

- Update README.md with new features
- Add migration guide for breaking changes
- Update API documentation
- Create release notes

## Getting Help

- **Discord**: Join our Discord server
- **GitHub Issues**: Create an issue for bugs or questions
- **Wiki**: Check the project wiki
- **Code Review**: Ask for feedback in pull requests

## Code of Conduct

- Be respectful and constructive
- Follow the project's coding standards
- Help newcomers get started
- Report issues responsibly

Thank you for contributing to the Ollama Plugin! 🎉
