package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a model reply into prose and proposed {@code /commands}. It never runs a command — the
 * player reads the suggestion and chooses to run it themselves. A hallucinated command is therefore
 * wrong text on screen, not an executed action.
 */
public final class CommandSuggester {

    private CommandSuggester() {}

    public record Suggestion(String prose, List<String> commands) {}

    public static Suggestion parse(String reply) {
        List<String> commands = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        for (String line : reply.split("\n", -1)) {
            if (line.strip().startsWith("/")) {
                commands.add(line.strip());
            } else {
                if (prose.length() > 0) {
                    prose.append("\n");
                }
                prose.append(line);
            }
        }
        return new Suggestion(prose.toString(), commands);
    }
}
