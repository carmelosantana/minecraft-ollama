package org.xpfarm.ollama.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.models.GenerateRequest;

/**
 * Regression guard for the Important 0.3.0 review finding: the {@code think}-capability probe must
 * run on the executor thread, never on the caller (Minecraft main/server) thread.
 *
 * <p>{@code applyThinkGating} calls {@link ModelCapabilities#supportsThinking(String)}, which on a
 * cache miss makes a synchronous {@code /api/show} HTTP call that blocks for up to
 * {@code api.timeout}. If that runs before the {@code CompletableFuture.runAsync} dispatch — as it
 * did before this fix — a slow or black-holed endpoint freezes the server thread for the whole
 * timeout. This is the exact main-thread network I/O the async architecture exists to avoid.
 *
 * <p>The test stands up a loopback server whose {@code /api/show} <em>blocks</em> until released,
 * then asserts the public {@code generateWithRequest} entry point returns to its caller in well
 * under the request timeout while the probe is demonstrably still in flight on another thread. If
 * the probe were moved back onto the caller thread, the entry point would block on {@code /api/show}
 * until the request timeout fired (~{@code TIMEOUT_SECONDS}), and the sub-second assertion below
 * would fail.
 */
final class OllamaAPIThinkGatingThreadTest {

    /** api.timeout for the test. The buggy (caller-thread) path blocks roughly this long. */
    private static final int TIMEOUT_SECONDS = 3;

    /** The caller thread must return far quicker than the probe's request timeout. */
    private static final long CALLER_RETURN_BUDGET_MS = 1_000L;

    private HttpServer server;
    private OllamaPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getInt("performance.max_concurrent_requests", 1)).thenReturn(1);
        when(config.getString("api.endpoint", "http://localhost:11434")).thenReturn(endpoint);
        when(config.getString("api.model", "llama3.2")).thenReturn("qwen3");
        when(config.getInt("api.timeout", 30)).thenReturn(TIMEOUT_SECONDS);
        when(config.getInt("api.max_retries", 3)).thenReturn(0);
        when(config.getDouble(eq("api.temperature"), anyDouble())).thenReturn(0.7);

        plugin = mock(OllamaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        installCrossThreadBukkitServer();
    }

    /**
     * {@code generateWithRequest} fires an event synchronously and delivers its callback via
     * {@code Bukkit.getScheduler().runTask} on the executor thread. A {@code try(MockedStatic)} is
     * thread-local and would not cover the executor thread, so the Bukkit singleton is installed
     * once per JVM instead — that makes the static delegators work on every thread. The scheduler
     * runs its task inline so the callback is observable.
     */
    private static void installCrossThreadBukkitServer() throws Exception {
        if (Bukkit.getServer() != null) {
            return;
        }
        Server bukkitServer = mock(Server.class);
        when(bukkitServer.getPluginManager()).thenReturn(mock(PluginManager.class));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });
        when(bukkitServer.getScheduler()).thenReturn(scheduler);
        when(bukkitServer.getLogger()).thenReturn(Logger.getAnonymousLogger());
        // Set the static field directly rather than Bukkit.setServer(): the Paper API's setServer()
        // logs a version banner via ServerBuildInfo, which throws with no real server build present.
        // The field write is all the static delegators (getPluginManager/getScheduler) actually
        // read, and unlike a thread-local MockedStatic it is visible on the executor thread too.
        java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, bukkitServer);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void theCapabilityProbeRunsOnTheExecutorThreadNotTheCallerThread() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);

        // /api/show BLOCKS until released. On a cache miss this is what applyThinkGating awaits.
        server.createContext("/api/show", exchange -> {
            probeStarted.countDown();
            try {
                releaseProbe.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"capabilities\":[\"thinking\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/generate", exchange -> {
            byte[] body = "{\"response\":\"ok\",\"done\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        OllamaAPI api = new OllamaAPI(plugin);
        try {
            GenerateRequest request = new GenerateRequest();
            request.setModel("qwen3");
            request.setPrompt("hello");
            request.setStream(false);

            CountDownLatch callbackDelivered = new CountDownLatch(1);

            long startNanos = System.nanoTime();
            api.generateWithRequest(request, null, response -> callbackDelivered.countDown());
            long callerElapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            // 1) The caller returned promptly even though the probe is blocking. If the probe ran on
            //    this thread it would have blocked ~TIMEOUT_SECONDS before returning.
            assertTrue(callerElapsedMs < CALLER_RETURN_BUDGET_MS,
                    "generateWithRequest blocked the caller for " + callerElapsedMs + "ms against a "
                            + TIMEOUT_SECONDS + "s /api/show timeout; the capability probe is "
                            + "running on the caller thread instead of the executor thread");

            // 2) The probe genuinely started on another thread while the caller had already returned,
            //    and the callback has NOT been delivered yet (it is stuck behind the blocked probe).
            assertTrue(probeStarted.await(5, TimeUnit.SECONDS),
                    "the capability probe never ran; the async body was not dispatched");
            assertEquals(1, callbackDelivered.getCount(),
                    "the callback was delivered before the blocked probe completed");

            // 3) Releasing the probe lets the async body finish and deliver the callback.
            releaseProbe.countDown();
            assertTrue(callbackDelivered.await(10, TimeUnit.SECONDS),
                    "the callback was never delivered after the probe completed");
        } finally {
            releaseProbe.countDown();
            api.shutdown();
        }
    }
}
