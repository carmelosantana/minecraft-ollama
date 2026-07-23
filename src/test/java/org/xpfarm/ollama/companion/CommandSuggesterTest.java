package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CommandSuggesterTest {

    @Test
    void extractsSlashCommandLines() {
        CommandSuggester.Suggestion s = CommandSuggester.parse(
                "Try this to give yourself a torch:\n/give @s torch 16\nThen place it down.");
        assertEquals(1, s.commands().size());
        assertEquals("/give @s torch 16", s.commands().get(0));
        assertTrue(s.prose().contains("Try this"));
        assertTrue(s.prose().contains("place it down"));
    }

    @Test
    void noCommandsMeansEmptyList() {
        CommandSuggester.Suggestion s = CommandSuggester.parse("Just dig straight down. (Don't.)");
        assertTrue(s.commands().isEmpty());
        assertEquals("Just dig straight down. (Don't.)", s.prose().trim());
    }
}
