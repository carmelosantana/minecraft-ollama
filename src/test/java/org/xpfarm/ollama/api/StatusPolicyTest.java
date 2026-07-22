package org.xpfarm.ollama.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.xpfarm.ollama.api.StatusPolicy.Action;

/** Acceptance check 6: each documented status maps to its own distinct action. */
final class StatusPolicyTest {

    @Test
    void eachDocumentedStatusMapsToItsDocumentedAction() {
        assertEquals(Action.MALFORMED_REQUEST, StatusPolicy.forStatus(400));
        assertEquals(Action.MODEL_MISSING, StatusPolicy.forStatus(404));
        assertEquals(Action.CANCELLED, StatusPolicy.forStatus(499));
        assertEquals(Action.SERVER_ERROR, StatusPolicy.forStatus(500));
        assertEquals(Action.QUEUE_FULL, StatusPolicy.forStatus(503));
    }

    @Test
    void theSixDocumentedOutcomesAreAllDistinct() {
        Action[] actions = {
            StatusPolicy.forStatus(400), StatusPolicy.forStatus(404), StatusPolicy.forStatus(499),
            StatusPolicy.forStatus(500), StatusPolicy.forStatus(503), Action.BACKPRESSURE,
        };
        for (int i = 0; i < actions.length; i++) {
            for (int j = i + 1; j < actions.length; j++) {
                assertNotEquals(actions[i], actions[j],
                        "actions " + i + " and " + j + " collapsed to the same outcome");
            }
        }
    }

    @Test
    void serverErrorRetriesAtMostOnceEvenWhenMaxRetriesIsHigher() {
        assertEquals(1, StatusPolicy.retryBudget(Action.SERVER_ERROR, 3),
                "500 may be OOM; Ollama self-heals by evicting a model, so a 2nd retry is not a "
                        + "different experiment");
        assertEquals(1, StatusPolicy.retryBudget(Action.SERVER_ERROR, 99));
    }

    @Test
    void queueFullUsesTheFullConfiguredBudget() {
        assertEquals(3, StatusPolicy.retryBudget(Action.QUEUE_FULL, 3));
        assertEquals(1, StatusPolicy.retryBudget(Action.QUEUE_FULL, 1));
    }

    @Test
    void maxRetriesZeroDisablesEveryRetry() {
        for (Action action : Action.values()) {
            assertEquals(0, StatusPolicy.retryBudget(action, 0),
                    action + " retried despite api.max_retries: 0");
        }
    }

    @Test
    void nonRetryableActionsAreNeverRetried() {
        assertEquals(0, StatusPolicy.retryBudget(Action.MALFORMED_REQUEST, 3));
        assertEquals(0, StatusPolicy.retryBudget(Action.MODEL_MISSING, 3));
        assertEquals(0, StatusPolicy.retryBudget(Action.CANCELLED, 3));
        assertEquals(0, StatusPolicy.retryBudget(Action.BACKPRESSURE, 3),
                "a semaphore stall must shed load, not add more of it");
    }

    @Test
    void unmappedStatusesFallBackToServerError() {
        assertEquals(Action.SERVER_ERROR, StatusPolicy.forStatus(502));
        assertEquals(Action.SERVER_ERROR, StatusPolicy.forStatus(418));
    }
}
