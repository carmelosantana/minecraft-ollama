package org.xpfarm.ollama.api;

/**
 * A failed Ollama exchange, carrying the {@link StatusPolicy.Action} the caller should take and a
 * message that is safe to show a player — no endpoint, no response body, no stack trace.
 */
public class OllamaHttpException extends Exception {

    private static final long serialVersionUID = 1L;

    private final StatusPolicy.Action action;
    private final String playerMessage;

    public OllamaHttpException(StatusPolicy.Action action, String playerMessage, String detail) {
        super(detail);
        this.action = action;
        this.playerMessage = playerMessage;
    }

    public StatusPolicy.Action getAction() { return action; }

    public String getPlayerMessage() { return playerMessage; }
}
