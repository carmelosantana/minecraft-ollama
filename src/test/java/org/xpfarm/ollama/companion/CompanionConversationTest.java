package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.api.models.ChatRequest;

final class CompanionConversationTest {

    private InventorySnapshot snapshot() {
        return new InventorySnapshot(List.of("TORCH"), List.of(), Map.of(), "AIR", "SHIELD",
                true, true, true, 100, true, 20, 20, "PLAINS", "world", 1000L);
    }

    @Test
    void buildsSystemPromptAsLeadingSystemMessage() {
        ChatRequest req = CompanionConversation.buildRequest(
                "llama3.2", List.of(ChatMessage.user("hi")), "what should I build?",
                snapshot(), "You are a friendly llama.");

        // The system prompt must be the FIRST message with role system — never a top-level field.
        assertEquals("system", req.getMessages().get(0).getRole());
        assertTrue(req.getMessages().get(0).getContent().contains("friendly llama"));
        // Context is folded into the system message (own-state only).
        assertTrue(req.getMessages().get(0).getContent().contains("PLAINS"));
        // The new user turn is last.
        ChatMessage last = req.getMessages().get(req.getMessages().size() - 1);
        assertEquals("user", last.getRole());
        assertEquals("what should I build?", last.getContent());
    }

    @Test
    void degradedMessageIsInCharacter() {
        assertFalse(CompanionConversation.degradedMessage().isBlank());
    }
}
