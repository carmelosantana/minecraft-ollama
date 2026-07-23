package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, LLM-free reminders. Instant, free, and incapable of hallucinating — which is why
 * unprompted advice is rules, not model output (a warm Ollama serves one request at a time, so
 * per-player LLM nudges would not survive a populated server).
 */
public final class InventoryAdvisor {

    private final Set<String> enabled;

    public InventoryAdvisor(Set<String> enabledRules) {
        this.enabled = enabledRules;
    }

    public List<String> advise(InventorySnapshot s) {
        List<String> out = new ArrayList<>();
        if (enabled.contains("shield") && s.carriesShield() && !s.shieldEquipped()) {
            out.add("You've got a shield but it's not in your off-hand.");
        }
        if (enabled.contains("food") && !s.hasFood()) {
            out.add("You're out of food — might want to grab some before you get hungry.");
        }
        if (enabled.contains("tool_durability") && s.lowestToolDurabilityPct() <= 10) {
            out.add("One of your tools is about to break.");
        }
        if (enabled.contains("torches") && !s.hasTorches()) {
            out.add("No torches on you — it's dark out there.");
        }
        return out;
    }
}
