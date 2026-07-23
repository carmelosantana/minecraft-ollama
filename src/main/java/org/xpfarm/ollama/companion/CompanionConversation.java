package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.api.models.ChatRequest;
import org.xpfarm.ollama.api.models.ChatResponse;

/** Orchestrates a companion conversation turn, degrading to an in-character line when Ollama is off. */
public final class CompanionConversation {

    private static final int MAX_HISTORY = 10;

    private final OllamaPlugin plugin;
    private final String personality;
    // The companion's OWN history, separate from /ollama chat, so personas do not blend.
    private final Map<UUID, List<ChatMessage>> history = new ConcurrentHashMap<>();

    public CompanionConversation(OllamaPlugin plugin, String personality) {
        this.plugin = plugin;
        this.personality = personality;
    }

    public boolean canConverse() {
        return plugin.getOllamaAPI() != null;
    }

    public static String degradedMessage() {
        return "*the llama blinks slowly* ...I can't think clearly right now. Try me again later.";
    }

    /** Pure assembly: history + new user turn, with the personality and own-state context as a system message. */
    public static ChatRequest buildRequest(String model, List<ChatMessage> history, String userText,
            InventorySnapshot snapshot, String personality) {
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(ChatMessage.user(userText));
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setMessages(messages);
        request.setStream(false);
        String system = personality + "\n\n" + CompanionContext.describe(snapshot);
        request.setSystemPrompt(system); // leading role:system message — never a top-level field
        return request;
    }

    public void ask(Player player, String text) {
        if (!canConverse()) {
            player.sendMessage(Component.text(degradedMessage(), NamedTextColor.GRAY));
            return;
        }
        InventorySnapshot snapshot = InventorySnapshots.of(player); // main thread
        List<ChatMessage> hist = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        ChatRequest request = buildRequest(plugin.getOllamaAPI().getDefaultModel(),
                hist, text, snapshot, personality);

        plugin.getOllamaAPI().chatWithRequest(request, player, response -> deliver(player, text, response));
    }

    private void deliver(Player player, String userText, ChatResponse response) {
        if (response == null || response.getError() != null || response.getMessage() == null) {
            player.sendMessage(Component.text(degradedMessage(), NamedTextColor.GRAY));
            return;
        }
        String reply = response.getMessage().getContent();
        // Persist the turn (bounded).
        List<ChatMessage> hist = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        hist.add(ChatMessage.user(userText));
        hist.add(ChatMessage.assistant(reply));
        while (hist.size() > MAX_HISTORY) {
            hist.remove(0);
        }

        CommandSuggester.Suggestion s = CommandSuggester.parse(reply);
        player.sendMessage(Component.text("🦙 ", NamedTextColor.AQUA)
                .append(Component.text(s.prose(), NamedTextColor.WHITE)));
        for (String cmd : s.commands()) {
            player.sendMessage(Component.text("  ▶ " + cmd, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand(cmd))); // prefill, never run
        }
    }

    public void forget(Player player) {
        history.remove(player.getUniqueId());
    }
}
