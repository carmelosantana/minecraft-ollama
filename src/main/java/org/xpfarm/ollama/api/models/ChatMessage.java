package org.xpfarm.ollama.api.models;

/**
 * Chat message model for Ollama chat API
 */
public class ChatMessage {
    private String role;
    private String content;
    /**
     * Populated by thinking-capable models. Parsed so it is never mistaken for {@code content};
     * the plugin does not use it. Never send this field — it is response-only.
     */
    private String thinking;

    public ChatMessage() {}
    
    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
    
    // Getters and setters
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    /**
     * Create a user message
     * 
     * @param content The message content
     * @return A new user message
     */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
    
    /**
     * Create an assistant message
     * 
     * @param content The message content
     * @return A new assistant message
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
    
    /**
     * Create a system message
     * 
     * @param content The message content
     * @return A new system message
     */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
}
