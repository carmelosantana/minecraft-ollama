package org.xpfarm.ollama.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FollowTaskTest {

    @Test
    void teleportsBeyondThreshold() {
        assertTrue(FollowTask.shouldTeleport(24.1, 24));
        assertTrue(FollowTask.shouldTeleport(100.0, 24));
    }

    @Test
    void walksWithinThreshold() {
        assertFalse(FollowTask.shouldTeleport(5.0, 24));
        assertFalse(FollowTask.shouldTeleport(24.0, 24));
    }
}
