# Makefile for Ollama Plugin Server Management
# Provides convenient shortcuts for common development tasks

.PHONY: help setup start stop restart reset clean status logs build install test dev docker-build docker-test debug test-commands network attach players

# Default target
help: ## Show this help message
	@echo "🧠 Ollama Plugin - Development Commands"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""
	@echo "Examples:"
	@echo "  make setup      # Initial server setup"
	@echo "  make dev        # Quick development cycle (build + install + restart)"
	@echo "  make test       # Run plugin tests"

# Check if required tools are installed
check-deps: ## Check if required dependencies are installed
	@echo "Checking dependencies..."
	@command -v java >/dev/null 2>&1 || { echo "Java is required but not installed. Install Java 21+"; exit 1; }
	@command -v mvn >/dev/null 2>&1 || { echo "Maven is required but not installed. Install Maven"; exit 1; }
	@command -v docker >/dev/null 2>&1 || { echo "Docker is required but not installed. Install Docker"; exit 1; }
	@command -v ollama >/dev/null 2>&1 || { echo "Warning: Ollama is not installed. Install Ollama for local testing"; }
	@echo "✅ All dependencies are installed"

setup: check-deps ## Set up the development environment
	@echo "Setting up development environment..."
	@chmod +x server-manager.sh
	@./server-manager.sh setup

start: ## Start the Minecraft server
	@./server-manager.sh start

stop: ## Stop the Minecraft server
	@./server-manager.sh stop

restart: ## Restart the server (stop + start)
	@./server-manager.sh restart

reset: ## Reset server (clean world, rebuild plugin, restart)
	@./server-manager.sh reset

clean: ## Remove all server files and directories
	@./server-manager.sh clean

status: ## Show server status
	@./server-manager.sh status

logs: ## Show recent server logs
	@./server-manager.sh logs

build: ## Build the plugin JAR
	@echo "Building plugin..."
	@mvn clean package
	@echo "✅ Plugin built successfully"

install: build ## Install/update plugin to server
	@./server-manager.sh install

test: ## Run plugin tests
	@echo "Running tests..."
	@mvn test
	@echo "✅ Tests completed"

dev: build install restart ## Quick development cycle
	@echo "🚀 Development cycle complete!"

docker-build: build ## Build Docker container with plugin
	@echo "Building Docker container..."
	@docker-compose -f docker-compose.yml build
	@echo "✅ Docker container built"

docker-test: docker-build ## Test plugin in Docker container
	@echo "Testing plugin in Docker container..."
	@./docker-test.sh
	@echo "✅ Docker test completed"

debug: ## Debug plugin functionality in running server
	@echo "=== Debug Commands ==="
	@echo "Running interactive debug script..."
	@./debug-plugin.sh

test-commands: build install ## Build, install and show test commands
	@echo "Plugin installed! Test with these commands:"
	@echo ""
	@echo "=== Player Commands ==="
	@echo "  /ollama say <prompt>     - Generate text using Ollama"
	@echo "  /ollama code <prompt>    - Generate code using Ollama"
	@echo "  /ollama run <prompt>     - Generate and execute command"
	@echo "  /ollama chat             - Start interactive chat session"
	@echo "  /ollama version          - Show plugin version"
	@echo ""
	@echo "=== Admin Commands (op required) ==="
	@echo "  /ollama reload           - Reload plugin configuration"
	@echo "  /ollama status           - Show plugin and API status"
	@echo "  /ollama test             - Send test request to Ollama"
	@echo "  /ollama debug <on|off>   - Enable/disable debug mode"
	@echo ""
	@echo "To test, restart server: make restart"

network: ## Check network connectivity and display server info
	@./server-manager.sh network

attach: ## Attach to the running server console
	@./server-manager.sh attach

players: ## List online players
	@./server-manager.sh players

version: ## Show versions of Java, Maven, and plugin
	@echo "=== Version Information ==="
	@echo "Java: $$(java -version 2>&1 | head -n 1)"
	@echo "Maven: $$(mvn -version 2>&1 | head -n 1)"
	@echo "Plugin: $$(grep '<version>' pom.xml | head -n 1 | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | xargs)"
	@echo "Docker: $$(docker --version)"
	@echo "Ollama: $$(ollama --version 2>/dev/null || echo 'Not installed')"

lint: ## Check code quality with shellcheck
	@echo "Checking shell scripts..."
	@command -v shellcheck >/dev/null 2>&1 || { echo "Installing shellcheck..."; brew install shellcheck; }
	@find . -name "*.sh" -exec shellcheck {} \;
	@echo "✅ Shell scripts checked"

format: ## Format Java code (if spotless is configured)
	@echo "Formatting code..."
	@mvn spotless:apply 2>/dev/null || echo "Spotless not configured, skipping..."

validate: lint test ## Validate code quality and run tests
	@echo "✅ Validation complete"

# Check if Ollama is running
check-ollama: ## Check if Ollama service is running
	@echo "Checking Ollama service..."
	@curl -s http://localhost:11434/api/version >/dev/null 2>&1 && echo "✅ Ollama is running" || echo "❌ Ollama is not running. Start with 'ollama serve'"

# Create a simple release
release: validate build ## Create a release build
	@echo "Creating release..."
	@mkdir -p releases
	@cp target/ollama-*.jar releases/
	@echo "✅ Release created in releases/ directory"

# Start Ollama service (if installed)
start-ollama: ## Start Ollama service locally
	@echo "Starting Ollama service..."
	@command -v ollama >/dev/null 2>&1 || { echo "Ollama not installed. Visit https://ollama.com"; exit 1; }
	@ollama serve &
	@echo "✅ Ollama service started"

# Pull default model
pull-model: ## Pull the default model (llama3.2)
	@echo "Pulling default model..."
	@ollama pull llama3.2
	@echo "✅ Model pulled successfully"
