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
     * Player-facing text must never carry an endpoint URL, a response body, or a stack trace.
     * Nothing but code reading pinned that property before this test existed.
     *
     * <p>The check is on the URL <em>scheme</em> ({@code http://}) rather than the bare substring
     * {@code http}: four of the six messages deliberately end in {@code (HTTP 400)} and friends,
     * and a bare status code is not a leak — it names no host, no path, and no response body. A
     * bare-{@code http} assertion would fail against {@link StatusPolicy} as written, and the fix
     * would have to be to strip the status codes, which is a behaviour change to a class this task
     * is not allowed to alter. The host, path, and stack-trace assertions below cover what the
     * property is actually about.
     */
    @Test
    void noPlayerMessageLeaksAnEndpointOrAStackTrace() {
        for (StatusPolicy.Action action : StatusPolicy.Action.values()) {
            String message = StatusPolicy.playerMessage(action, 500);
            String lower = message.toLowerCase(Locale.ROOT);
            assertTrue(!lower.contains("http://") && !lower.contains("https://"),
                    action + " player message leaks a URL scheme: " + message);
            assertTrue(!message.contains("://"),
                    action + " player message leaks a URL: " + message);
            assertTrue(!message.contains("Exception"),
                    action + " player message leaks a stack trace: " + message);
            assertTrue(!lower.contains("localhost") && !lower.contains("127.0.0.1"),
                    action + " player message leaks the endpoint host: " + message);
            assertTrue(!lower.contains("/api/"),
                    action + " player message leaks an endpoint path: " + message);
        }
    }
}
