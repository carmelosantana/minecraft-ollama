package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class InventoryAdvisorTest {

    private InventorySnapshot snap(boolean carriesShield, boolean shieldEquipped, boolean hasFood,
            int toolPct, boolean hasTorches) {
        return new InventorySnapshot(List.of(), List.of(), Map.of(), "AIR", "AIR",
                carriesShield, shieldEquipped, hasFood, toolPct, hasTorches, 20, 20, "PLAINS",
                "world", 1000L);
    }

    @Test
    void nudgesAnUnequippedShield() {
        List<String> out = new InventoryAdvisor(Set.of("shield"))
                .advise(snap(true, false, true, 100, true));
        assertEquals(1, out.size());
        assertTrue(out.get(0).toLowerCase().contains("shield"));
    }

    @Test
    void silentWhenShieldEquipped() {
        assertTrue(new InventoryAdvisor(Set.of("shield"))
                .advise(snap(true, true, true, 100, true)).isEmpty());
    }

    @Test
    void respectsDisabledRules() {
        // food is missing, but only the shield rule is enabled → no food nudge
        assertTrue(new InventoryAdvisor(Set.of("shield"))
                .advise(snap(false, false, false, 100, true)).isEmpty());
    }

    @Test
    void firesMultipleEnabledRules() {
        List<String> out = new InventoryAdvisor(Set.of("food", "torches"))
                .advise(snap(false, false, false, 100, false));
        assertEquals(2, out.size());
    }

    @Test
    void toolDurabilityFiresAtThreshold() {
        assertEquals(1, new InventoryAdvisor(Set.of("tool_durability"))
                .advise(snap(false, false, true, 10, true)).size());
        assertTrue(new InventoryAdvisor(Set.of("tool_durability"))
                .advise(snap(false, false, true, 11, true)).isEmpty());
    }
}
