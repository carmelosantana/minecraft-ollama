package org.xpfarm.ollama.companion;

import java.util.List;
import java.util.Map;

/**
 * An immutable, Bukkit-free view of the asking player's own state. By construction it carries no
 * information about any other player and no chat history — the approved context scope is enforced by
 * this type's shape, not by downstream discipline.
 */
public record InventorySnapshot(
        List<String> hotbar,
        List<String> storage,
        Map<String, String> armor,
        String mainHand,
        String offHand,
        boolean carriesShield,
        boolean shieldEquipped,
        boolean hasFood,
        int lowestToolDurabilityPct,
        boolean hasTorches,
        int health,
        int hunger,
        String biome,
        String dimension,
        long timeOfDay) {
}
