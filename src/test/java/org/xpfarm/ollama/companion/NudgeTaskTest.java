package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NudgeTaskTest {

    @Test
    void offCooldownWhenNeverSpoken() {
        NudgeTask task = new NudgeTask(null, null, null, 300);
        assertTrue(task.offCooldown(UUID.randomUUID(), 1_000_000L));
    }

    @Test
    void onCooldownUntilIntervalElapses() {
        NudgeTask task = new NudgeTask(null, null, null, 300);
        UUID id = UUID.randomUUID();
        task.markSpoken(id, 1_000_000L);
        assertFalse(task.offCooldown(id, 1_000_000L + 299_000L)); // 299s later, still cooling
        assertTrue(task.offCooldown(id, 1_000_000L + 300_000L));  // 300s later, ready
    }
}
