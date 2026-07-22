package org.xpfarm.ollama.api.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the bug that motivated release 0.3.0.
 *
 * <p>{@code /api/chat} has no top-level {@code system} field and never has. Gin binds with
 * {@code ShouldBindJSON} and {@code DisallowUnknownFields} appears nowhere in {@code routes.go},
 * so Go's {@code encoding/json} drops the key and the request returns {@code 200 OK} with the
 * system prompt silently discarded. This is the class of bug that looks healthy in every log.
 */
final class ChatRequestSerializationTest {

    private static final Gson GSON = new Gson();

    private static JsonObject serialize(ChatRequest request) {
        return JsonParser.parseString(GSON.toJson(request)).getAsJsonObject();
    }

    /** Acceptance check 1. */
    @Test
    void systemPromptBecomesARoleSystemMessageAndNeverATopLevelField() {
        ChatRequest request = new ChatRequest();
        request.setModel("llama3.2");
        request.setMessages(List.of(ChatMessage.user("How do I build a house?")));
        request.setSystemPrompt("You are a helpful Minecraft assistant.");

        JsonObject json = serialize(request);

        assertFalse(json.has("system"),
                "a top-level 'system' field is silently dropped by Ollama -- the prompt would be "
                        + "discarded and the request would still return 200");

        var messages = json.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("You are a helpful Minecraft assistant.",
                messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    }

    /** No accidental reintroduction via a leftover setter. */
    @Test
    void chatRequestExposesNoSystemAccessorAtAll() {
        for (var method : ChatRequest.class.getMethods()) {
            assertFalse(method.getName().equals("setSystem") || method.getName().equals("getSystem"),
                    "ChatRequest." + method.getName() + " reintroduces the top-level system field");
        }
        for (var field : ChatRequest.class.getDeclaredFields()) {
            assertFalse(field.getName().equals("system"),
                    "ChatRequest still declares a 'system' field; Gson will serialize it");
        }
    }

    @Test
    void applyingASystemPromptTwiceReplacesRatherThanStacks() {
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));
        request.setSystemPrompt("first");
        request.setSystemPrompt("second");

        var messages = serialize(request).getAsJsonArray("messages");
        assertEquals(2, messages.size(), "the system message stacked instead of being replaced");
        assertEquals("second", messages.get(0).getAsJsonObject().get("content").getAsString());
    }

    /**
     * The chat session hands its own live message list to every request. Mutating it in place
     * would append a system message to the player's stored history on every single turn.
     */
    @Test
    void setMessagesDefensivelyCopiesSoSessionHistoryIsNotMutated() {
        List<ChatMessage> sessionHistory = new ArrayList<>();
        sessionHistory.add(ChatMessage.user("hi"));

        ChatRequest request = new ChatRequest();
        request.setMessages(sessionHistory);
        request.setSystemPrompt("You are a llama.");

        assertEquals(1, sessionHistory.size(),
                "the caller's list grew a system message -- chat history is now corrupted");
        assertEquals("user", sessionHistory.get(0).getRole());
    }

    /** Acceptance check 3. */
    @Test
    void thinkIsSerializedWhenSetAndOmittedWhenUnknown() {
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));

        request.setThink(false);
        JsonObject withThink = serialize(request);
        assertTrue(withThink.has("think"), "think must be explicit: omitting it means TRUE "
                + "on a thinking-capable model since Ollama v0.12.4");
        assertFalse(withThink.get("think").getAsBoolean());

        request.setThink(null);
        assertFalse(serialize(request).has("think"),
                "an unknown capability must omit think entirely; sending it to a model without "
                        + "the capability is a hard 400");
    }

    /** Acceptance check 5. */
    @Test
    void formatSerializesAsAJsonSchemaObjectNotAString() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));
        request.setFormat(schema);

        JsonObject json = serialize(request);
        assertTrue(json.get("format").isJsonObject(),
                "format typed as String can only ever send \"json\"; a JSON Schema is impossible");
        assertEquals("object", json.getAsJsonObject("format").get("type").getAsString());
    }

    @Test
    void aThinkingResponseParsesAndTheThinkingTextIsNotMistakenForContent() {
        ChatResponse response = GSON.fromJson(
                "{\"message\":{\"role\":\"assistant\",\"thinking\":\"hmm\",\"content\":\"Hi!\"},"
                        + "\"done\":true,\"done_reason\":\"stop\"}",
                ChatResponse.class);

        assertEquals("Hi!", response.getContent());
        assertEquals("hmm", response.getMessage().getThinking());
        assertEquals("stop", response.getDone_reason());
    }

    @Test
    void doneReasonDistinguishesATruncatedAnswerFromACompleteOne() {
        ChatResponse truncated = GSON.fromJson(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},"
                        + "\"done\":true,\"done_reason\":\"length\"}",
                ChatResponse.class);

        assertTrue(truncated.isDone());
        assertEquals("length", truncated.getDone_reason(),
                "without done_reason a reply truncated at num_predict is indistinguishable "
                        + "from a complete one");
    }

    @Test
    void nullMessagesAreToleratedWhenASystemPromptIsApplied() {
        ChatRequest request = new ChatRequest();
        request.setSystemPrompt("You are a llama.");

        var messages = serialize(request).getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void messagesAccessorReturnsTheStoredCopyNotTheCallersList() {
        List<ChatMessage> original = new ArrayList<>(List.of(ChatMessage.user("hi")));
        ChatRequest request = new ChatRequest();
        request.setMessages(original);

        assertFalse(original == request.getMessages(), "setMessages did not defensively copy");
        assertSame(original.get(0), request.getMessages().get(0), "elements need not be cloned");
    }
}
