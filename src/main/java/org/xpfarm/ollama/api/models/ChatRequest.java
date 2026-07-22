package org.xpfarm.ollama.api.models;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request model for the Ollama {@code /api/chat} endpoint.
 *
 * <p><strong>There is deliberately no top-level {@code system} field.</strong> {@code /api/chat}
 * has never had one, at any Ollama version. The server binds request JSON with Gin's
 * {@code ShouldBindJSON} and {@code DisallowUnknownFields} appears nowhere in {@code routes.go},
 * so an unknown {@code system} key is dropped by Go's {@code encoding/json} and the request still
 * returns {@code 200 OK} — with the system prompt discarded and nothing anywhere saying so.
 * A system prompt belongs in {@link #setSystemPrompt(String)}, as a {@code role: "system"} message.
 *
 * <p>{@link GenerateRequest} <em>does</em> keep a top-level {@code system}, because
 * {@code /api/generate} genuinely accepts one. Do not "make these consistent".
 */
public class ChatRequest {
    private String model;
    private List<ChatMessage> messages;
    private boolean stream = false;
    /**
     * Boxed so {@code null} omits the key entirely. Since Ollama v0.12.4, omitting {@code think}
     * on a thinking-capable model means {@code think: true} — but sending the field to a model
     * without the capability is a hard 400. Null therefore means "capability unknown, say nothing".
     */
    private Boolean think;
    /** {@link JsonElement}, not {@link String}: as a String this could only ever send "json". */
    private JsonElement format;
    private Map<String, Object> options;
    private String keep_alive;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<ChatMessage> getMessages() { return messages; }

    /**
     * Stores a defensive copy. The chat session hands its own live history list here, and
     * {@link #setSystemPrompt(String)} mutates the stored list — without the copy, every turn
     * would append another system message to the player's saved history.
     */
    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages == null ? null : new ArrayList<>(messages);
    }

    /**
     * Places {@code systemPrompt} as the leading {@code role: "system"} message, replacing an
     * existing leading system message rather than stacking a second one.
     *
     * <p>Call this <em>after</em> {@link #setMessages(List)}.
     */
    public void setSystemPrompt(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return;
        }
        if (messages == null) {
            messages = new ArrayList<>();
        }
        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            messages.set(0, ChatMessage.system(systemPrompt));
        } else {
            messages.add(0, ChatMessage.system(systemPrompt));
        }
    }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }

    public Boolean getThink() { return think; }
    public void setThink(Boolean think) { this.think = think; }

    public JsonElement getFormat() { return format; }
    public void setFormat(JsonElement format) { this.format = format; }

    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }

    public String getKeep_alive() { return keep_alive; }
    public void setKeep_alive(String keep_alive) { this.keep_alive = keep_alive; }
}
