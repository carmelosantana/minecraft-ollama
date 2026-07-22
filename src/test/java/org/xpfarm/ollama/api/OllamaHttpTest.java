package org.xpfarm.ollama.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the transport against a real loopback HTTP server.
 *
 * <p>{@code com.sun.net.httpserver} is a JDK built-in, so this adds no dependency. The transport
 * is deliberately Bukkit-free precisely so these paths — timeout, gate, retry, status dispatch —
 * can be tested at all; before 0.3.0 none of them could be reached without a running server.
 */
final class OllamaHttpTest {

    private HttpServer server;
    private String endpoint;
    private OllamaHttp http;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (http != null) {
            http.close();
        }
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private OllamaHttp transport(int permits, int timeoutSeconds, int maxRetries) {
        OllamaHttp created = new OllamaHttp(permits, Logger.getAnonymousLogger());
        created.configure(endpoint, timeoutSeconds, maxRetries);
        return created;
    }

    @Test
    void aSuccessfulPostReturnsTheBody() throws Exception {
        respond("/api/chat", 200, "{\"done\":true}");
        http = transport(1, 5, 0);

        assertEquals("{\"done\":true}", http.postGated("/api/chat", "{}"));
    }

    /** Acceptance check 2. */
    @Test
    void timeoutAbortsRatherThanPinningTheThread() {
        server.createContext("/api/chat", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        http = transport(1, 1, 0);

        long started = System.nanoTime();
        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < 5_000,
                "api.timeout did not fire; the call took " + elapsedMs + "ms against a 1s timeout");
        assertEquals(StatusPolicy.Action.BACKPRESSURE, thrown.getAction());
    }

    /** A timed-out request must release the gate, or the plugin wedges after one stall. */
    @Test
    void aTimedOutRequestReleasesTheGate() {
        server.createContext("/api/slow", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        respond("/api/chat", 200, "ok");
        http = transport(1, 1, 0);

        assertThrows(OllamaHttpException.class, () -> http.postGated("/api/slow", "{}"));
        assertEquals("ok", assertDoesNotThrowValue(() -> http.postGated("/api/chat", "{}")));
    }

    private static <T> T assertDoesNotThrowValue(
            org.junit.jupiter.api.function.ThrowingSupplier<T> supplier) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(supplier);
    }

    /** Acceptance check 7. */
    @Test
    void secondConcurrentRequestIsRejectedNotQueued() throws Exception {
        CountDownLatch firstIsInFlight = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        server.createContext("/api/chat", exchange -> {
            firstIsInFlight.countDown();
            try {
                releaseFirst.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http = transport(1, 30, 0);

        Thread first = new Thread(() -> {
            try {
                http.postGated("/api/chat", "{}");
            } catch (OllamaHttpException ignored) {
                // not the subject of this test
            }
        });
        first.start();
        assertTrue(firstIsInFlight.await(5, TimeUnit.SECONDS), "the first request never started");

        long started = System.nanoTime();
        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(StatusPolicy.Action.BACKPRESSURE, thrown.getAction());
        assertTrue(elapsedMs < 2_000,
                "the 2nd request queued for " + elapsedMs + "ms instead of being refused; a warm "
                        + "Ollama never 503s, so a queued request stalls until our timeout fires");

        releaseFirst.countDown();
        first.join(10_000);
    }

    @Test
    void theGateIsReleasedAfterASuccessfulRequest() throws Exception {
        respond("/api/chat", 200, "ok");
        http = transport(1, 5, 0);

        for (int i = 0; i < 3; i++) {
            assertEquals("ok", http.postGated("/api/chat", "{}"));
        }
    }

    @Test
    void metadataCallsAreUngatedSoTheyCannotDeadlockAgainstOneInFlightGeneration() throws Exception {
        CountDownLatch generationInFlight = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        server.createContext("/api/chat", exchange -> {
            generationInFlight.countDown();
            try {
                releaseGeneration.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("ok".getBytes(StandardCharsets.UTF_8));
            }
        });
        respond("/api/show", 200, "{\"capabilities\":[\"thinking\"]}");
        http = transport(1, 30, 0);

        Thread generation = new Thread(() -> {
            try {
                http.postGated("/api/chat", "{}");
            } catch (OllamaHttpException ignored) {
                // not the subject
            }
        });
        generation.start();
        assertTrue(generationInFlight.await(5, TimeUnit.SECONDS));

        assertEquals("{\"capabilities\":[\"thinking\"]}", http.post("/api/show", "{}"),
                "the capability probe blocked on the generation gate -- at permits=1 that is a "
                        + "self-deadlock whenever a probe is triggered by a generation");

        releaseGeneration.countDown();
        generation.join(10_000);
    }

    @Test
    void aNonTwoHundredIsThrownRatherThanParsedAndDelivered() {
        respond("/api/chat", 404, "{\"error\":\"model 'nope' not found\"}");
        http = transport(1, 5, 0);

        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(StatusPolicy.Action.MODEL_MISSING, thrown.getAction());
        assertTrue(thrown.getPlayerMessage().contains("not installed"));
    }

    @Test
    void aFiveHundredIsRetriedExactlyOnceRegardlessOfMaxRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        http = transport(1, 5, 5);

        assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(2, attempts.get(),
                "expected 1 initial attempt + 1 retry; api.max_retries is a ceiling, and 500 caps "
                        + "at one retry because Ollama self-heals OOM by evicting models");
    }

    @Test
    void aFiveHundredThatRecoversOnRetryReturnsTheBody() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] bytes = "recovered".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http = transport(1, 5, 3);

        assertEquals("recovered", http.postGated("/api/chat", "{}"));
        assertEquals(2, attempts.get());
    }

    @Test
    void aFourHundredIsNeverRetried() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        http = transport(1, 5, 5);

        assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(1, attempts.get(), "a malformed request retried; it will fail identically");
    }

    @Test
    void aRefusedConnectionSurfacesAsBackpressureRatherThanHanging() {
        http = new OllamaHttp(1, Logger.getAnonymousLogger());
        // Port 1 on loopback refuses immediately.
        http.configure("http://127.0.0.1:1", 5, 0);

        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(StatusPolicy.Action.BACKPRESSURE, thrown.getAction());
    }

    @Test
    void aGetCarriesTheTimeoutToo() {
        server.createContext("/api/version", exchange -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        http = transport(1, 1, 0);

        long started = System.nanoTime();
        assertThrows(OllamaHttpException.class, () -> http.get("/api/version"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < 5_000,
                "GET ignored api.timeout; every request path must carry it, not just POST");
    }

    /**
     * The property: player-facing text must never leak an endpoint URL, a response body, or a
     * stack trace. Nothing but code reading pinned it before this test existed.
     *
     * <p>A bare uppercase HTTP status number is <strong>not</strong> a leak. {@code (HTTP 404)}
     * names no host, no path and no response body; it is a support token that lets an operator
     * match what a player reports in chat against a line in the server log. The status suffixes in
     * {@link StatusPolicy} are deliberate and stay.
     *
     * <p>The literal, case-<em>sensitive</em> {@code contains("http")} assertion below is therefore
     * satisfiable, and passes today against all six messages: every one of them spells the protocol
     * {@code HTTP} in uppercase, so a lowercase {@code http} in player text could only have come
     * from a URL. It is kept alongside the {@code http://} check because it also catches a
     * lowercase-scheme leak with no {@code //} — {@code "http:"}, or a bare {@code http} host
     * label — that checking only {@code http://} would miss.
     *
     * <p>Checked in two places: against {@link StatusPolicy#playerMessage} for every action here,
     * and against the {@code getPlayerMessage()} of exceptions {@link OllamaHttp} actually throws
     * in {@link #noExceptionThrownByTheTransportLeaksAnEndpointOrAStackTrace()}, so that
     * {@code OllamaHttp} passing an internal detail string through as the player message would fail
     * too. The detail message ({@code getMessage()}) is exempt by design: it carries the URI and
     * the truncated response body to the <em>server log</em>, which is the entire point of keeping
     * the two strings separate.
     */
    @Test
    void noPlayerMessageLeaksAnEndpointOrAStackTrace() {
        for (StatusPolicy.Action action : StatusPolicy.Action.values()) {
            assertLeaksNothing(action + " player message",
                    StatusPolicy.playerMessage(action, 500));
        }
    }

    /**
     * The companion to {@link #noPlayerMessageLeaksAnEndpointOrAStackTrace()}: asserting on
     * {@link StatusPolicy} alone would not catch {@link OllamaHttp} handing an internal detail
     * string to the exception's player-message slot. Every failure path the transport can produce
     * against a reachable server is checked here as it is actually thrown.
     */
    @Test
    void noExceptionThrownByTheTransportLeaksAnEndpointOrAStackTrace() {
        respond("/api/chat", 404,
                "{\"error\":\"model 'nope' not found at http://127.0.0.1:11434/api/chat\"}");
        respond("/api/show", 400, "{\"error\":\"unexpected EOF\"}");
        http = transport(1, 5, 0);

        OllamaHttpException missing =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertLeaksNothing("thrown MODEL_MISSING player message", missing.getPlayerMessage());

        OllamaHttpException malformed =
                assertThrows(OllamaHttpException.class, () -> http.post("/api/show", "{}"));
        assertLeaksNothing("thrown MALFORMED_REQUEST player message", malformed.getPlayerMessage());

        // The other half of the contract: the detail string is for the server log and is expected
        // to carry the URI. If this ever stops holding, the operator has lost their diagnostics.
        assertTrue(missing.getMessage().contains("/api/chat"),
                "the log detail dropped the URI: " + missing.getMessage());
    }

    /** Applies the no-leak rules to one player-facing string. */
    private static void assertLeaksNothing(String label, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        assertTrue(!message.contains("http"),
                label + " leaks a lowercase URL scheme: " + message);
        assertTrue(!lower.contains("http://") && !lower.contains("https://"),
                label + " leaks a URL scheme: " + message);
        assertTrue(!message.contains("://"), label + " leaks a URL: " + message);
        assertTrue(!message.contains("Exception"), label + " leaks a stack trace: " + message);
        assertTrue(!lower.contains("localhost") && !lower.contains("127.0.0.1"),
                label + " leaks the endpoint host: " + message);
        assertTrue(!lower.contains("/api/"), label + " leaks an endpoint path: " + message);
    }

    /**
     * {@code api.endpoint} is operator-typed and unvalidated, and both {@code URI.create} and
     * {@code HttpRequest.Builder.uri} reject a bad one with the <em>unchecked</em>
     * {@link IllegalArgumentException}. Unmapped, that escapes past every {@code catch
     * (OllamaHttpException)} the plugin has; on an executor thread it is swallowed by the default
     * handler and the player is simply never answered.
     */
    @Test
    void aMalformedEndpointThrowsOllamaHttpExceptionRatherThanIllegalArgumentException() {
        // No scheme at all, a non-HTTP scheme, and an illegal character.
        for (String malformed : new String[] {"127.0.0.1:11434", "ftp://127.0.0.1:11434",
                "http://127.0.0.1:11 434"}) {
            OllamaHttp transport = new OllamaHttp(1, Logger.getAnonymousLogger());
            try {
                transport.configure(malformed, 5, 0);

                // All three public entry points, because the throw site is shared by all of them.
                for (org.junit.jupiter.api.function.Executable call : new
                        org.junit.jupiter.api.function.Executable[] {
                            () -> transport.postGated("/api/chat", "{}"),
                            () -> transport.post("/api/show", "{}"),
                            () -> transport.get("/api/version")}) {
                    OllamaHttpException thrown = assertThrows(OllamaHttpException.class, call,
                            "endpoint '" + malformed + "' escaped as an unchecked throwable");
                    assertEquals(StatusPolicy.Action.MALFORMED_REQUEST, thrown.getAction(),
                            "endpoint '" + malformed + "' was not reported as malformed");
                    assertLeaksNothing("malformed-endpoint player message",
                            thrown.getPlayerMessage());
                }
            } finally {
                transport.close();
            }
        }
    }

    /** A malformed endpoint must not leave the concurrency gate held. */
    @Test
    void aMalformedEndpointStillReleasesTheGate() {
        http = new OllamaHttp(1, Logger.getAnonymousLogger());
        http.configure("127.0.0.1:11434", 5, 0);
        assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));

        respond("/api/chat", 200, "ok");
        http.configure(endpoint, 5, 0);
        assertEquals("ok", assertDoesNotThrowValue(() -> http.postGated("/api/chat", "{}")));
    }

    /** {@code http://host:11434/} + {@code /api/chat} would otherwise request {@code //api/chat}. */
    @Test
    void aTrailingSlashOnTheEndpointIsStrippedRatherThanDoublingTheSlash() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        http = new OllamaHttp(1, Logger.getAnonymousLogger());
        http.configure(endpoint + "/", 5, 0);

        assertEquals("ok", http.postGated("/api/chat", "{}"));
        assertEquals("/api/chat", requestedPath.get(),
                "a trailing slash in api.endpoint produced a doubled slash");
    }

    /**
     * {@code api.timeout} is per attempt, so the retry budget multiplies it. At the defaults
     * (30s, 3 retries) a persistently-503ing endpoint would hold the gate for up to 120 seconds and
     * refuse every other player for the duration. A deadline computed once per call bounds it.
     */
    @Test
    void aRepeatedlyFiveOhThreeingEndpointStopsOnTheDeadlineNotTheFullRetryBudget() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            attempts.incrementAndGet();
            try {
                Thread.sleep(900);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        // A 5-retry budget would be 6 attempts and roughly 9s of wall clock, unbounded by timeout.
        http = transport(1, 2, 5);

        long started = System.nanoTime();
        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(StatusPolicy.Action.QUEUE_FULL, thrown.getAction());
        assertTrue(attempts.get() <= 3,
                "the deadline did not stop the retry loop: " + attempts.get()
                        + " attempts against a 2s deadline and 900ms-per-attempt responses");
        assertTrue(elapsedMs < 6_000,
                "the call held the gate for " + elapsedMs + "ms; the deadline should cap it near "
                        + "2x api.timeout, not (max_retries + 1)x it");
    }

    /**
     * The deadline must not quietly become the retry policy. With a generous {@code api.timeout} a
     * 503 still spends its whole configured budget, exactly as before.
     */
    @Test
    void aFiveOhThreeStillUsesTheFullBudgetWhenTheDeadlineIsGenerous() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        http = transport(1, 30, 2);

        OllamaHttpException thrown =
                assertThrows(OllamaHttpException.class, () -> http.postGated("/api/chat", "{}"));
        assertEquals(StatusPolicy.Action.QUEUE_FULL, thrown.getAction());
        assertEquals(3, attempts.get(),
                "expected 1 initial attempt + the full 2-retry budget; 503 is genuine queue "
                        + "pressure on the load path and is the one action that gets all of it");
    }
}
