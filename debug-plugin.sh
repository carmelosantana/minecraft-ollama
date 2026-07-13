#!/bin/bash

# Debug Script for Ollama Plugin
# Interactive script to test plugin functionality

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

# Check if server is running
if ! screen -list | grep -q "minecraft"; then
    print_error "Minecraft server is not running. Start it with 'make start' first."
    exit 1
fi

print_status "Ollama Plugin Debug Script"
print_status "=========================="

# Test Ollama API directly
print_status "Testing Ollama API connection..."
if curl -s http://localhost:11434/api/version > /dev/null 2>&1; then
    print_success "Ollama API is accessible"
    
    # Get version info
    version_info=$(curl -s http://localhost:11434/api/version)
    echo "Ollama Version: $(echo "$version_info" | grep -o '"version":"[^"]*"' | cut -d'"' -f4)"
else
    print_error "Ollama API is not accessible"
    print_warning "Make sure Ollama is running with 'ollama serve'"
    exit 1
fi

# Test simple generation
print_status "Testing text generation..."
response=$(curl -s -X POST http://localhost:11434/api/generate -d '{
    "model": "llama3.2",
    "prompt": "Say hello in a friendly way",
    "stream": false
}' 2>/dev/null)

if echo "$response" | grep -q "response"; then
    print_success "Text generation is working"
    generated_text=$(echo "$response" | grep -o '"response":"[^"]*"' | cut -d'"' -f4)
    echo "Generated: $generated_text"
else
    print_error "Text generation failed"
    echo "Response: $response"
fi

# Interactive menu
while true; do
    echo ""
    print_status "Debug Options:"
    echo "1. Send test commands to server"
    echo "2. Check plugin status"
    echo "3. View recent server logs"
    echo "4. Test Ollama models"
    echo "5. Send custom Ollama request"
    echo "6. Exit"
    
    read -p "Select option (1-6): " choice
    
    case $choice in
        1)
            print_status "Sending test commands to server..."
            echo "Sending: /ollama version"
            screen -S minecraft -p 0 -X stuff "ollama version$(printf \\r)"
            sleep 1
            echo "Sending: /ollama status"
            screen -S minecraft -p 0 -X stuff "ollama status$(printf \\r)"
            sleep 1
            echo "Sending: /ollama test"
            screen -S minecraft -p 0 -X stuff "ollama test$(printf \\r)"
            print_success "Commands sent. Check server logs for output."
            ;;
        2)
            print_status "Checking plugin status..."
            if [ -f "server/logs/latest.log" ]; then
                if grep -q "Ollama.*enabled" server/logs/latest.log; then
                    print_success "Plugin is enabled"
                else
                    print_warning "Plugin may not be loaded"
                fi
                
                echo "Recent plugin log entries:"
                grep -i "ollama" server/logs/latest.log | tail -5
            else
                print_error "Server log file not found"
            fi
            ;;
        3)
            print_status "Recent server logs:"
            if [ -f "server/logs/latest.log" ]; then
                tail -20 server/logs/latest.log
            else
                print_error "Server log file not found"
            fi
            ;;
        4)
            print_status "Testing Ollama models..."
            models=$(curl -s http://localhost:11434/api/tags 2>/dev/null)
            if echo "$models" | grep -q "models"; then
                print_success "Available models:"
                echo "$models" | grep -o '"name":"[^"]*"' | cut -d'"' -f4
            else
                print_error "Failed to get model list"
            fi
            ;;
        5)
            print_status "Custom Ollama request"
            read -p "Enter your prompt: " prompt
            if [ -n "$prompt" ]; then
                print_status "Sending request..."
                response=$(curl -s -X POST http://localhost:11434/api/generate -d "{
                    \"model\": \"llama3.2\",
                    \"prompt\": \"$prompt\",
                    \"stream\": false
                }" 2>/dev/null)
                
                if echo "$response" | grep -q "response"; then
                    generated_text=$(echo "$response" | grep -o '"response":"[^"]*"' | cut -d'"' -f4)
                    print_success "Response:"
                    echo "$generated_text"
                else
                    print_error "Request failed"
                    echo "Response: $response"
                fi
            fi
            ;;
        6)
            print_status "Exiting debug script"
            exit 0
            ;;
        *)
            print_error "Invalid option. Please select 1-6."
            ;;
    esac
done
