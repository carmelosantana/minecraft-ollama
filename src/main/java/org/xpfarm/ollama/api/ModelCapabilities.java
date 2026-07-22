package org.xpfarm.ollama.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Caches per-model {@code /api/show} capability answers.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Since Ollama v0.12.4, omitting {@code think} on a thinking-capable model means
 * {@code think: true}. Against qwen3, gpt-oss, or deepseek-r1 every request therefore silently
 * pays a reasoning pass whose output lands in {@code message.thinking} and is discarded — pure
 * invisible latency. But sending {@code think} to a model <em>without</em> the capability is a
 * hard 400, so the field cannot simply always be set. It has to be gated on a probe.
 *
 * <h2>Why the cache is per model rather than a single enable-time answer</h2>
 *
 * <p>The model is not fixed. {@code api.model} can change under {@code /ollama reload},
 * {@code integration.expose_api} lets any other plugin submit a request with its own model, and
 * 0.4.0 will add a second caller. A single answer captured at enable would be silently wrong for
 * every model but one. The enable-time preload warms the entry for the configured model, so the
 * common path still pays no probe latency.
 *
 * <h2>Unknown is not "no"</h2>
 *
 * <p>A failed probe returns {@code null} and is not cached. Null means the {@code think} field is
 * omitted entirely, which is exactly the pre-0.3.0 wire format — so an Ollama that is up enough to
 * generate but flaky on {@code /api/show} degrades to old behavior instead of to a 400 storm.
 */
public final class ModelCapabilities {

    private static final String THINKING = "thinking";

    private final OllamaHttp http;
    private final Logger logger;
    private final Map<String, Boolean> thinkingByModel = new ConcurrentHashMap<>();

    public ModelCapabilities(OllamaHttp http, Logger logger) {
        this.http = http;
        this.logger = logger;
    }

    /**
     * @return {@code TRUE} if the model advertises the thinking capability, {@code FALSE} if it
     *     does not, {@code null} if the probe could not be completed.
     */
    public Boolean supportsThinking(String model) {
        if (model == null || model.isEmpty()) {
            return null;
        }
        Boolean cached = thinkingByModel.get(model);
        if (cached != null) {
            return cached;
        }
        Boolean probed = probe(model);
        if (probed != null) {
            thinkingByModel.put(model, probed);
        }
        return probed;
    }

    /** Drops a cached answer so the next call re-probes. Called on config reload. */
    public void forget(String model) {
        if (model != null) {
            thinkingByModel.remove(model);
        }
    }

    /** Drops every cached answer. */
    public void clear() {
        thinkingByModel.clear();
    }

    private Boolean probe(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        try {
            // Ungated on purpose: /api/show occupies no inference slot, and a gated probe called
            // from inside a gated generation would deadlock at the default of one permit.
            String response = http.post("/api/show", body.toString());
            return hasThinkingCapability(response);
        } catch (OllamaHttpException e) {
            logger.log(Level.FINE,
                    "Could not probe capabilities for model {0}: {1}. Sending no 'think' field, "
                            + "which is the pre-0.3.0 behavior.",
                    new Object[] {model, e.getMessage()});
            return null;
        }
    }

    private static Boolean hasThinkingCapability(String responseBody) {
        JsonElement parsed = JsonParser.parseString(responseBody);
        if (!parsed.isJsonObject()) {
            return Boolean.FALSE;
        }
        JsonElement capabilities = parsed.getAsJsonObject().get("capabilities");
        if (capabilities == null || !capabilities.isJsonArray()) {
            return Boolean.FALSE;
        }
        JsonArray array = capabilities.getAsJsonArray();
        for (JsonElement entry : array) {
            if (entry.isJsonPrimitive() && THINKING.equals(entry.getAsString())) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }
}
