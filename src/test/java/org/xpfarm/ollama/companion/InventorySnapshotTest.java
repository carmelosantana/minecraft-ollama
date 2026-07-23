package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InventorySnapshotTest {

    private InventorySnapshot snapshot(boolean carriesShield, boolean shieldEquipped) {
        return new InventorySnapshot(List.of(), List.of(), Map.of(), "AIR", "AIR",
                carriesShield, shieldEquipped, true, 100, true, 20, 20, "PLAINS", "world", 1000L);
    }

    @Test
    void distinguishesCarriedFromEquippedShield() {
        assertTrue(snapshot(true, false).carriesShield());
        assertFalse(snapshot(true, false).shieldEquipped());
    }
}
