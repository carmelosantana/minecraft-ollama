package org.xpfarm.ollama.api.models;

import java.util.List;
import java.util.Map;

/**
 * Request model for Ollama chat API
 */
public class ChatRequest {
    private String model;
    private List<ChatMessage> messages;
    private String system;
    private boolean stream = false;
    private String format;
    private Map<String, Object> options;
    private String keep_alive;
    
    // Getters and setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
    
    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }
    
    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    
    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }
    
    public String getKeep_alive() { return keep_alive; }
    public void setKeep_alive(String keep_alive) { this.keep_alive = keep_alive; }
}
