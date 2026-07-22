package org.xpfarm.ollama.api.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * {@code /api/generate} <em>does</em> accept a top-level {@code system}, unlike {@code /api/chat}.
 * The asymmetry between the two endpoints is the trap that made the chat bug invisible, so it is
 * pinned here deliberately rather than left to be "cleaned up" later.
 */
final class GenerateRequestSerializationTest {

    private static final Gson GSON = new Gson();

    private static JsonObject serialize(GenerateRequest request) {
        return JsonParser.parseString(GSON.toJson(request)).getAsJsonObject();
    }

    @Test
    void generateKeepsItsTopLevelSystemFieldBecauseThatEndpointSupportsIt() {
        GenerateRequest request = new GenerateRequest();
        request.setModel("llama3.2");
        request.setPrompt("hello");
        request.setSystem("You are a helpful Minecraft assistant.");

        JsonObject json = serialize(request);
        assertTrue(json.has("system"),
                "/api/generate accepts top-level system; removing it here would break /ollama say");
        assertEquals("You are a helpful Minecraft assistant.", json.get("system").getAsString());
    }

    /** Acceptance check 3. */
    @Test
    void thinkIsSerializedWhenSetAndOmittedWhenUnknown() {
        GenerateRequest request = new GenerateRequest();
        request.setPrompt("hello");

        request.setThink(false);
        JsonObject withThink = serialize(request);
        assertTrue(withThink.has("think"));
        assertFalse(withThink.get("think").getAsBoolean());

        request.setThink(null);
        assertFalse(serialize(request).has("think"));
    }

    /** Acceptance check 5. */
    @Test
    void formatSerializesAsAJsonSchemaObjectNotAString() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        GenerateRequest request = new GenerateRequest();
        request.setPrompt("hello");
        request.setFormat(schema);

        assertTrue(serialize(request).get("format").isJsonObject());
    }

    @Test
    void doneReasonAndThinkingAreParsed() {
        GenerateResponse response = GSON.fromJson(
                "{\"response\":\"Hi!\",\"thinking\":\"hmm\",\"done\":true,\"done_reason\":\"stop\"}",
                GenerateResponse.class);

        assertEquals("Hi!", response.getResponse());
        assertEquals("hmm", response.getThinking());
        assertEquals("stop", response.getDone_reason());
    }
}
