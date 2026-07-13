package org.xpfarm.ollama.chat;

import org.xpfarm.ollama.api.models.ChatMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a chat session with Ollama
 */
public class ChatSession {
    
    private final String sessionId;
    private final List<ChatMessage> messages;
    private final LocalDateTime createdAt;
    private LocalDateTime lastActivity;
    private boolean active;
    
    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.messages = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.lastActivity = createdAt;
        this.active = true;
    }
    
    /**
     * Add a message to the session
     * 
     * @param message The message to add
     */
    public void addMessage(ChatMessage message) {
        messages.add(message);
        lastActivity = LocalDateTime.now();
    }
    
    /**
     * Get all messages in the session
     * 
     * @return List of messages
     */
    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }
    
    /**
     * Get the most recent messages up to a limit
     * 
     * @param limit Maximum number of messages
     * @return List of recent messages
     */
    public List<ChatMessage> getRecentMessages(int limit) {
        int size = messages.size();
        if (size <= limit) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(size - limit, size));
    }
    
    /**
     * Clear all messages from the session
     */
    public void clearMessages() {
        messages.clear();
        lastActivity = LocalDateTime.now();
    }
    
    /**
     * Check if the session has expired
     * 
     * @param timeoutMinutes Session timeout in minutes
     * @return True if expired
     */
    public boolean isExpired(int timeoutMinutes) {
        return LocalDateTime.now().isAfter(lastActivity.plusMinutes(timeoutMinutes));
    }
    
    /**
     * End the session
     */
    public void end() {
        active = false;
    }
    
    // Getters
    public String getSessionId() {
        return sessionId;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public LocalDateTime getLastActivity() {
        return lastActivity;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public int getMessageCount() {
        return messages.size();
    }
}
