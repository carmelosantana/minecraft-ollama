package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LlamaCommandTest {

    @Test
    void routesKnownSubcommands() {
        assertEquals(LlamaCommand.Sub.ASK, LlamaCommand.route(new String[] {"ask", "hi"}));
        assertEquals(LlamaCommand.Sub.RECIPE, LlamaCommand.route(new String[] {"recipe"}));
        assertEquals(LlamaCommand.Sub.DISMISS, LlamaCommand.route(new String[] {"dismiss"}));
        assertEquals(LlamaCommand.Sub.GIVE, LlamaCommand.route(new String[] {"give", "Steve"}));
    }

    @Test
    void emptyArgsIsHelp() {
        assertEquals(LlamaCommand.Sub.HELP, LlamaCommand.route(new String[] {}));
    }

    @Test
    void unknownIsUnknown() {
        assertEquals(LlamaCommand.Sub.UNKNOWN, LlamaCommand.route(new String[] {"frobnicate"}));
    }
}
