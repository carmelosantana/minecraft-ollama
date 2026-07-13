#!/bin/bash

# Docker Test Script for Ollama Plugin
# Tests the plugin functionality in a Docker container

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    print_error "Docker is not installed. Please install Docker first."
    exit 1
fi

# Check if Docker Compose is installed
if ! command -v docker-compose &> /dev/null; then
    print_error "Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

# Build the plugin first
print_status "Building plugin..."
mvn clean package -q
if [ $? -ne 0 ]; then
    print_error "Failed to build plugin"
    exit 1
fi
print_success "Plugin built successfully"

# Stop any existing containers
print_status "Stopping existing containers..."
docker-compose down -v > /dev/null 2>&1 || true

# Build and start containers
print_status "Starting Docker containers..."
docker-compose up -d

# Wait for services to be ready
print_status "Waiting for services to start..."
sleep 10

# Check if Ollama is ready
print_status "Waiting for Ollama to be ready..."
for i in {1..30}; do
    if curl -s http://localhost:11434/api/version > /dev/null 2>&1; then
        print_success "Ollama is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        print_error "Ollama failed to start"
        docker-compose logs ollama
        exit 1
    fi
    echo -n "."
    sleep 2
done

# Test Ollama API
print_status "Testing Ollama API..."
response=$(curl -s -X POST http://localhost:11434/api/generate -d '{
    "model": "llama3.2",
    "prompt": "Hello, this is a test!",
    "stream": false
}')

if echo "$response" | grep -q "response"; then
    print_success "Ollama API is working"
else
    print_error "Ollama API test failed"
    echo "Response: $response"
    exit 1
fi

# Wait for Minecraft server to be ready
print_status "Waiting for Minecraft server to start..."
for i in {1..60}; do
    if docker-compose logs minecraft 2>&1 | grep -q "Done"; then
        print_success "Minecraft server is ready"
        break
    fi
    if [ $i -eq 60 ]; then
        print_error "Minecraft server failed to start"
        docker-compose logs minecraft
        exit 1
    fi
    echo -n "."
    sleep 5
done

# Check if plugin is loaded
print_status "Checking if plugin is loaded..."
if docker-compose logs minecraft 2>&1 | grep -q "Ollama.*enabled"; then
    print_success "Ollama plugin is loaded"
else
    print_warning "Plugin may not be loaded properly"
    docker-compose logs minecraft | grep -i ollama || echo "No plugin logs found"
fi

# Test plugin commands (this would require connecting to the server)
print_status "Plugin installation test complete"
print_status "To test plugin commands, connect to the server at localhost:25565"
print_status "Use the following test commands:"
echo "  /ollama version"
echo "  /ollama status"
echo "  /ollama test"
echo "  /ollama say Hello from Docker!"

# Show service status
print_status "Service Status:"
docker-compose ps

print_success "Docker test completed successfully!"
print_status "To view logs: docker-compose logs -f"
print_status "To stop services: docker-compose down"
print_status "To connect to Minecraft: localhost:25565"
print_status "To access Ollama API: http://localhost:11434"
