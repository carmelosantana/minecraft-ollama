package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CompanionContextTest {

    @Test
    void describesOwnStateAndNeverOtherPlayers() {
        InventorySnapshot s = new InventorySnapshot(
                List.of("DIAMOND_SWORD", "TORCH"), List.of("IRON_INGOT"),
                Map.of("head", "IRON_HELMET"), "DIAMOND_SWORD", "SHIELD",
                true, true, true, 80, true, 18, 17, "PLAINS", "world", 6000L);
        String out = CompanionContext.describe(s);
        assertTrue(out.contains("DIAMOND_SWORD"));
        assertTrue(out.contains("PLAINS"));
        assertTrue(out.contains("18")); // health
        // No leakage channel exists in the type, but assert the prose never invents a "nearby" line.
        assertFalse(out.toLowerCase().contains("nearby player"));
    }
}
