package org.xpfarm.ollama.api;

/**
 * Maps an Ollama HTTP status to what the client should do about it.
 *
 * <p>Dispatch is on the status code and never on the response body. Ollama emits two different
 * 404 body formats — {@code '%s'} and {@code %q} — depending on which internal path failed, so
 * any text match here would be correct against one of them and silently wrong against the other.
 *
 * <p>Before 0.3.0 a non-200 was logged and then the body was parsed and delivered to the caller
 * anyway, so an error response reached the player as if it were an answer.
 */
public final class StatusPolicy {

    /** What the client does about a given outcome. One constant per documented row. */
    public enum Action {
        /** 400 — our request is malformed, or a capability is missing. Log loudly, never retry. */
        MALFORMED_REQUEST,
        /** 404 — the model is not installed. Tell the player, never retry. */
        MODEL_MISSING,
        /** 499 — we cancelled it. Ignore. */
        CANCELLED,
        /** 500 — possibly OOM. Ollama self-heals by evicting models, so back off and retry once. */
        SERVER_ERROR,
        /** 503 — queue full; only reachable on the model-load path. Back off and retry. */
        QUEUE_FULL,
        /** Timeout with no status: the semaphore stall of Finding 3. Shed load, never retry. */
        BACKPRESSURE,
    }

    private StatusPolicy() {}

    /**
     * Maps a <strong>non-200</strong> status to the action the client should take.
     *
     * <p>Only meaningful for statuses that represent a failure. There is no success constant in
     * {@link Action}, so {@code forStatus(200)} returns {@link Action#SERVER_ERROR} via the default
     * branch — a success would be misreported as a server fault. Callers must therefore check for
     * 200 themselves and only consult this method once they know the exchange failed.
     *
     * @param status an HTTP status code other than 200
     * @return the action for that status; unmapped codes fall back to {@link Action#SERVER_ERROR}
     */
    public static Action forStatus(int status) {
        return switch (status) {
            case 400 -> Action.MALFORMED_REQUEST;
            case 404 -> Action.MODEL_MISSING;
            case 499 -> Action.CANCELLED;
            case 503 -> Action.QUEUE_FULL;
            // Anything else non-200 is treated as a server-side fault. 500 is the documented
            // case; 502 and friends behave the same way from our side.
            default -> Action.SERVER_ERROR;
        };
    }

    /**
     * How many retries this outcome may use.
     *
     * <p>{@code api.max_retries} is a ceiling, not a target: the per-action cap decides how much
     * of that budget is actually sensible. A 500 may be an out-of-memory Ollama, which self-heals
     * by evicting a model — the second retry is not a different experiment, it just multiplies the
     * latency the player waits before being told it failed. A 503 is queue pressure on the load
     * path and is genuinely transient, so it gets the whole budget. {@code max_retries: 0}
     * disables retries everywhere.
     */
    public static int retryBudget(Action action, int configuredMaxRetries) {
        int ceiling = Math.max(0, configuredMaxRetries);
        return switch (action) {
            case SERVER_ERROR -> Math.min(1, ceiling);
            case QUEUE_FULL -> ceiling;
            case MALFORMED_REQUEST, MODEL_MISSING, CANCELLED, BACKPRESSURE -> 0;
        };
    }

    /** A message safe to show a player: no endpoint, no body, no stack trace. */
    public static String playerMessage(Action action, int status) {
        return switch (action) {
            case MALFORMED_REQUEST -> "The AI request was rejected as malformed (HTTP 400).";
            case MODEL_MISSING -> "That model is not installed on the Ollama server (HTTP 404).";
            case CANCELLED -> "The AI request was cancelled.";
            case SERVER_ERROR -> "The Ollama server hit an error (HTTP " + status + ").";
            case QUEUE_FULL -> "The Ollama server is overloaded (HTTP 503). Try again shortly.";
            case BACKPRESSURE -> "The AI is busy right now. Try again shortly.";
        };
    }
}
