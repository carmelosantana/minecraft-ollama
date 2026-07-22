package org.xpfarm.ollama.api.models;

import com.google.gson.JsonElement;
import java.util.Map;

/**
 * Request model for Ollama generate API
 *
 * <p>Unlike ChatRequest, /api/generate genuinely accepts a top-level system field, so this one
 * keeps it.
 */
public class GenerateRequest {
    private String model;
    private String prompt;
    private String suffix;
    private String system;
    private String template;
    private boolean stream = false;
    private boolean raw = false;
    /** Boxed: null omits the key. See ChatRequest#think for why that matters. */
    private Boolean think;
    /** JsonElement, not String: as a String this could only ever send "json". */
    private JsonElement format;
    private Map<String, Object> options;
    private String keep_alive;
    
    // Getters and setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    
    public String getSuffix() { return suffix; }
    public void setSuffix(String suffix) { this.suffix = suffix; }
    
    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }
    
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    
    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    
    public boolean isRaw() { return raw; }
    public void setRaw(boolean raw) { this.raw = raw; }
    
    public Boolean getThink() { return think; }
    public void setThink(Boolean think) { this.think = think; }

    public JsonElement getFormat() { return format; }
    public void setFormat(JsonElement format) { this.format = format; }
    
    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }
    
    public String getKeep_alive() { return keep_alive; }
    public void setKeep_alive(String keep_alive) { this.keep_alive = keep_alive; }
}
