package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CompanionManagerConfigTest {

    @Test
    void keepsKnownRulesAndDropsUnknown() {
        Set<String> rules = CompanionManager.parseRules(List.of("shield", "food", "bogus"));
        assertTrue(rules.contains("shield"));
        assertTrue(rules.contains("food"));
        assertFalse(rules.contains("bogus"));
        assertEquals(2, rules.size());
    }
}
