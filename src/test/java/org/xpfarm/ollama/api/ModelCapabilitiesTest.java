package org.xpfarm.ollama.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance check 4: a model without the thinking capability is never sent {@code think}. */
final class ModelCapabilitiesTest {

    private HttpServer server;
    private OllamaHttp http;
    private final AtomicInteger probes = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        http = new OllamaHttp(1, Logger.getAnonymousLogger());
        http.configure("http://127.0.0.1:" + server.getAddress().getPort(), 5, 0);
    }

    @AfterEach
    void stopServer() {
        http.close();
        server.stop(0);
    }

    private void showReturns(int status, String body) {
        server.createContext("/api/show", exchange -> {
            probes.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    void aThinkingCapableModelIsReportedCapable() {
        showReturns(200, "{\"capabilities\":[\"completion\",\"thinking\"]}");
        assertEquals(Boolean.TRUE,
                new ModelCapabilities(http, Logger.getAnonymousLogger()).supportsThinking("qwen3"));
    }

    @Test
    void aModelWithoutTheCapabilityIsReportedNotCapable() {
        showReturns(200, "{\"capabilities\":[\"completion\"]}");
        assertEquals(Boolean.FALSE,
                new ModelCapabilities(http, Logger.getAnonymousLogger())
                        .supportsThinking("llama3.2"));
    }

    @Test
    void aResponseWithNoCapabilitiesArrayIsReportedNotCapable() {
        showReturns(200, "{\"details\":{}}");
        assertEquals(Boolean.FALSE,
                new ModelCapabilities(http, Logger.getAnonymousLogger())
                        .supportsThinking("llama3.2"));
    }

    @Test
    void theResultIsCachedPerModelSoTheProbeRunsOnce() {
        showReturns(200, "{\"capabilities\":[\"thinking\"]}");
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        capabilities.supportsThinking("qwen3");
        capabilities.supportsThinking("qwen3");
        capabilities.supportsThinking("qwen3");

        assertEquals(1, probes.get(), "the capability probe is not cached");
    }

    @Test
    void differentModelsAreProbedIndependently() {
        server.createContext("/api/show", exchange -> {
            probes.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = body.contains("qwen3")
                    ? "{\"capabilities\":[\"thinking\"]}"
                    : "{\"capabilities\":[\"completion\"]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        assertEquals(Boolean.TRUE, capabilities.supportsThinking("qwen3"));
        assertEquals(Boolean.FALSE, capabilities.supportsThinking("llama3.2"));
        assertEquals(2, probes.get(),
                "a per-model cache is required: api.model can change under /ollama reload and any "
                        + "API consumer may set its own model");
    }

    @Test
    void aFailedProbeReportsUnknownAndIsNotCachedAsAnAnswer() {
        showReturns(500, "boom");
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        assertNull(capabilities.supportsThinking("qwen3"),
                "a failed probe must be 'unknown', which omits think entirely -- guessing false "
                        + "risks the hard 400 of sending think to a non-capable model");
        capabilities.supportsThinking("qwen3");
        assertTrue(probes.get() >= 2, "a failed probe was cached as a negative answer");
    }

    @Test
    void aTwoHundredWithAPlaintextBodyReportsUnknownRatherThanThrowing() {
        showReturns(200, "Ollama is running");
        assertNull(
                new ModelCapabilities(http, Logger.getAnonymousLogger()).supportsThinking("qwen3"),
                "a 200 with a non-JSON body must be 'unknown' (omit think), not an unchecked "
                        + "JsonSyntaxException thrown onto the main thread");
    }

    @Test
    void aTwoHundredWithAnHtmlBodyReportsUnknownRatherThanThrowing() {
        showReturns(200, "<!DOCTYPE html><html><body><h1>502 Bad Gateway</h1></body></html>");
        assertNull(
                new ModelCapabilities(http, Logger.getAnonymousLogger()).supportsThinking("qwen3"),
                "a 200 whose body is an HTML error page must degrade to 'unknown', not throw");
    }

    @Test
    void aTwoHundredWithANonJsonBodyIsNotCachedAsAnAnswer() {
        showReturns(200, "Ollama is running");
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        assertNull(capabilities.supportsThinking("qwen3"));
        capabilities.supportsThinking("qwen3");
        assertTrue(probes.get() >= 2,
                "an unparseable-body probe was cached instead of being re-probed");
    }

    @Test
    void aProbeAgainstAnUnreachableEndpointReportsUnknownRatherThanThrowing() {
        OllamaHttp unreachable = new OllamaHttp(1, Logger.getAnonymousLogger());
        unreachable.configure("http://127.0.0.1:1", 2, 0);
        try {
            assertNull(new ModelCapabilities(unreachable, Logger.getAnonymousLogger())
                    .supportsThinking("qwen3"));
        } finally {
            unreachable.close();
        }
    }

    @Test
    void forgetDropsTheCachedAnswerSoAReloadCanReprobe() {
        showReturns(200, "{\"capabilities\":[\"thinking\"]}");
        ModelCapabilities capabilities = new ModelCapabilities(http, Logger.getAnonymousLogger());

        capabilities.supportsThinking("qwen3");
        capabilities.forget("qwen3");
        capabilities.supportsThinking("qwen3");

        assertEquals(2, probes.get());
    }
}
