package org.xpfarm.ollama.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ollama HTTP transport. Deliberately free of any Bukkit reference so that timeouts, the
 * concurrency gate, retries, and status dispatch are unit-testable against a loopback server.
 *
 * <p>Built on {@link java.net.http.HttpClient}, which ships with the JDK. This replaced Apache
 * HttpClient 4.5.14 in 0.3.0 — a removed shaded dependency rather than an added one, and the
 * terminal 4.x release besides.
 *
 * <h2>Two invariants</h2>
 *
 * <p><strong>Every request carries {@code api.timeout}.</strong> The only way to build a request
 * here is {@link #newRequest(String)}, which always sets it. Before 0.3.0 the value was read from
 * config and then never applied, so a stalled Ollama pinned an executor thread forever.
 *
 * <p><strong>Metadata calls are ungated.</strong> {@code /api/version} and {@code /api/show} do
 * not occupy an inference slot on the server, and — more importantly — a probe triggered from
 * inside a gated generation would deadlock instantly at the default of one permit. Only
 * {@code /api/chat} and {@code /api/generate} go through {@link #postGated}.
 */
public final class OllamaHttp {

    /** Base for the linear backoff between retries. Kept small: the player is waiting. */
    private static final long RETRY_BACKOFF_MS = 250L;

    private final Semaphore gate;
    private final Logger logger;

    private volatile HttpClient httpClient;
    private volatile String endpoint = "http://localhost:11434";
    private volatile int timeoutSeconds = 30;
    private volatile int maxRetries = 3;

    /**
     * @param permits concurrent generations allowed. Defaults to 1 because
     *     {@code OLLAMA_NUM_PARALLEL} defaults to 1 and, per Finding 3, a warm model does not
     *     reject surplus requests — it blocks them on an internal semaphore indefinitely, so
     *     overload arrives as unbounded latency and never as an error. Fixed for the lifetime of
     *     this object; changing {@code performance.max_concurrent_requests} needs a restart.
     */
    public OllamaHttp(int permits, Logger logger) {
        this.gate = new Semaphore(Math.max(1, permits));
        this.logger = logger;
        this.httpClient = buildClient(this.timeoutSeconds);
    }

    /** Applies current config. Rebuilds the client only when the connect timeout actually moves. */
    public void configure(String endpoint, int timeoutSeconds, int maxRetries) {
        this.endpoint = endpoint;
        this.maxRetries = Math.max(0, maxRetries);
        int resolved = Math.max(1, timeoutSeconds);
        if (resolved != this.timeoutSeconds || this.httpClient == null) {
            HttpClient previous = this.httpClient;
            this.httpClient = buildClient(resolved);
            this.timeoutSeconds = resolved;
            if (previous != null) {
                // shutdownNow(), never close(): close() blocks until in-flight exchanges finish,
                // and reloadConfig() runs on the main thread. A /ollama reload during a slow
                // generation would freeze the server for up to api.timeout seconds.
                previous.shutdownNow();
            }
        }
    }

    private static HttpClient buildClient(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * The single request factory. Every path goes through here, which is what makes "the timeout
     * is always applied" a structural property rather than a convention someone can forget.
     *
     * <p>{@code api.endpoint} is operator-supplied text and is not validated at load time, so it
     * can be anything. {@link URI#create} rejects a value containing a space, and
     * {@link HttpRequest.Builder#uri} rejects one whose scheme is missing or non-HTTP
     * ({@code localhost:11434}) — both as the <em>unchecked</em> {@link IllegalArgumentException}.
     * Left alone that escapes every public method here as an unchecked throwable, so a caller
     * catching only {@link OllamaHttpException} on an executor thread swallows it and the player is
     * told nothing at all. It is translated to {@link StatusPolicy.Action#MALFORMED_REQUEST}, which
     * is exactly what it is: a request we cannot even form.
     *
     * <p>A single trailing slash is stripped from the endpoint, because
     * {@code http://host:11434/} + {@code /api/chat} is {@code //api/chat}.
     */
    private HttpRequest.Builder newRequest(String path) throws OllamaHttpException {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json");
        } catch (IllegalArgumentException e) {
            throw new OllamaHttpException(StatusPolicy.Action.MALFORMED_REQUEST,
                    StatusPolicy.playerMessage(StatusPolicy.Action.MALFORMED_REQUEST, 400),
                    "api.endpoint is not a usable HTTP URL: '" + endpoint + "' (" + e.getMessage()
                            + ")");
        }
    }

    /** Ungated GET. Used by the {@code /api/version} connection test. */
    public String get(String path) throws OllamaHttpException {
        return execute(newRequest(path).GET().build());
    }

    /** Ungated POST. Used by the {@code /api/show} capability probe. */
    public String post(String path, String jsonBody) throws OllamaHttpException {
        return execute(postRequest(path, jsonBody));
    }

    /**
     * POST through the concurrency gate. Refuses immediately rather than queueing: a queued
     * request would stall on Ollama's own semaphore until our timeout fired, which the player
     * experiences as a 30-second hang and the log records as a timeout indistinguishable from a
     * dead endpoint.
     */
    public String postGated(String path, String jsonBody) throws OllamaHttpException {
        if (!gate.tryAcquire()) {
            throw new OllamaHttpException(StatusPolicy.Action.BACKPRESSURE,
                    StatusPolicy.playerMessage(StatusPolicy.Action.BACKPRESSURE, 0),
                    "concurrency gate full (" + gate.availablePermits() + " permits free)");
        }
        try {
            return execute(postRequest(path, jsonBody));
        } finally {
            gate.release();
        }
    }

    private HttpRequest postRequest(String path, String jsonBody) throws OllamaHttpException {
        return newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    /**
     * Sends the request, retrying within both the per-action retry budget <em>and</em> a total
     * elapsed-time budget.
     *
     * <p>{@code api.timeout} is a per-attempt timeout — that is what {@link HttpRequest#timeout}
     * means — so on its own it bounds nothing overall. At the defaults (timeout 30s,
     * max_retries 3) a endpoint that 503s every time would spend 4 x 30s = 120 seconds inside a
     * single {@link #postGated} call <em>while holding the concurrency gate</em>, refusing every
     * other player for two minutes. The deadline computed here is checked before each retry, so the
     * worst case is roughly 2x {@code api.timeout} (the last attempt may start just under the
     * deadline and then run its own full timeout) instead of {@code (maxRetries + 1)}x it.
     *
     * <p>Passing the deadline is not a distinct outcome: the loop simply stops retrying and throws
     * the failure it already has, exactly as if the retry budget had run out.
     */
    private String execute(HttpRequest request) throws OllamaHttpException {
        final long deadlineNanos =
                System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        int attempt = 0;
        while (true) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                // Finding 3: on a warm model this is what overload looks like. Never retry it --
                // adding load to a stalled server is the one thing guaranteed not to help.
                throw new OllamaHttpException(StatusPolicy.Action.BACKPRESSURE,
                        StatusPolicy.playerMessage(StatusPolicy.Action.BACKPRESSURE, 0),
                        "timed out after " + timeoutSeconds + "s: " + request.uri());
            } catch (IOException e) {
                throw new OllamaHttpException(StatusPolicy.Action.BACKPRESSURE,
                        StatusPolicy.playerMessage(StatusPolicy.Action.BACKPRESSURE, 0),
                        "could not reach Ollama at " + endpoint + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OllamaHttpException(StatusPolicy.Action.CANCELLED,
                        StatusPolicy.playerMessage(StatusPolicy.Action.CANCELLED, 499),
                        "interrupted while awaiting Ollama");
            }

            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }

            StatusPolicy.Action action = StatusPolicy.forStatus(status);
            if (attempt < StatusPolicy.retryBudget(action, maxRetries)
                    && System.nanoTime() < deadlineNanos) {
                attempt++;
                logger.log(Level.WARNING, "Ollama returned HTTP {0}; retry {1} of {2}",
                        new Object[] {status, attempt,
                                StatusPolicy.retryBudget(action, maxRetries)});
                backoff(attempt);
                continue;
            }
            throw new OllamaHttpException(action, StatusPolicy.playerMessage(action, status),
                    "HTTP " + status + " from " + request.uri() + ": " + truncate(response.body()));
        }
    }

    private void backoff(int attempt) throws OllamaHttpException {
        try {
            Thread.sleep(RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaHttpException(StatusPolicy.Action.CANCELLED,
                    StatusPolicy.playerMessage(StatusPolicy.Action.CANCELLED, 499),
                    "interrupted while backing off");
        }
    }

    /** Error bodies go to the server log, not to a player, and are bounded. */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }

    /**
     * Uses {@code shutdownNow()} rather than {@code close()} on purpose: {@code close()} waits for
     * in-flight exchanges, and this runs from {@code onDisable()}. A pending generation would
     * otherwise stall Paper's shutdown for up to {@code api.timeout} seconds.
     */
    public void close() {
        HttpClient current = this.httpClient;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
