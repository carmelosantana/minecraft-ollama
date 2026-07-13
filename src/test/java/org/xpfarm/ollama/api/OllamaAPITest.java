package org.xpfarm.ollama.api;

import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.models.GenerateRequest;
import org.xpfarm.ollama.api.models.GenerateResponse;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.api.models.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OllamaAPI
 */
class OllamaAPITest {
    
    @Mock
    private OllamaPlugin mockPlugin;
    
    private OllamaAPI ollamaAPI;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Mock plugin configuration
        when(mockPlugin.getConfig()).thenReturn(mock(org.bukkit.configuration.file.FileConfiguration.class));
        when(mockPlugin.getConfig().getString("api.endpoint", "http://localhost:11434"))
                .thenReturn("http://localhost:11434");
        when(mockPlugin.getConfig().getString("api.model", "llama3.2"))
                .thenReturn("llama3.2");
        when(mockPlugin.getConfig().getInt("api.timeout", 30))
                .thenReturn(30);
        when(mockPlugin.getConfig().getInt("performance.max_concurrent_requests", 5))
                .thenReturn(5);
        
        ollamaAPI = new OllamaAPI(mockPlugin);
    }
    
    @Test
    void testGenerateRequest() throws InterruptedException {
        // This is a unit test for the API structure
        // In a real test environment, you would mock the HTTP client
        
        GenerateRequest request = new GenerateRequest();
        request.setModel("test-model");
        request.setPrompt("Hello, world!");
        request.setStream(false);
        
        assertEquals("test-model", request.getModel());
        assertEquals("Hello, world!", request.getPrompt());
        assertFalse(request.isStream());
    }
    
    @Test
    void testGenerateResponse() {
        GenerateResponse response = new GenerateResponse();
        response.setResponse("Hello! How can I help you today?");
        response.setDone(true);
        response.setEval_count(10);
        response.setEval_duration(1000000000L); // 1 second in nanoseconds
        
        assertEquals("Hello! How can I help you today?", response.getResponse());
        assertTrue(response.isDone());
        assertFalse(response.hasError());
        assertEquals(10.0, response.getTokensPerSecond(), 0.1);
    }
    
    @Test
    void testChatMessage() {
        ChatMessage userMessage = ChatMessage.user("How do I build a house?");
        ChatMessage assistantMessage = ChatMessage.assistant("To build a house in Minecraft...");
        ChatMessage systemMessage = ChatMessage.system("You are a helpful Minecraft assistant.");
        
        assertEquals("user", userMessage.getRole());
        assertEquals("How do I build a house?", userMessage.getContent());
        
        assertEquals("assistant", assistantMessage.getRole());
        assertEquals("To build a house in Minecraft...", assistantMessage.getContent());
        
        assertEquals("system", systemMessage.getRole());
        assertEquals("You are a helpful Minecraft assistant.", systemMessage.getContent());
    }
    
    @Test
    void testChatResponse() {
        ChatResponse response = new ChatResponse();
        ChatMessage message = ChatMessage.assistant("Here's how to build a house...");
        response.setMessage(message);
        response.setDone(true);
        
        assertEquals("Here's how to build a house...", response.getContent());
        assertTrue(response.isDone());
        assertFalse(response.hasError());
    }
    
    @Test
    void testErrorHandling() {
        GenerateResponse errorResponse = new GenerateResponse();
        errorResponse.setError("Connection failed");
        
        assertTrue(errorResponse.hasError());
        assertEquals("Connection failed", errorResponse.getError());
    }
    
    @Test
    void testConfiguration() {
        assertEquals("llama3.2", ollamaAPI.getDefaultModel());
        assertEquals("http://localhost:11434", ollamaAPI.getEndpoint());
    }
    
    // Integration tests would require a running Ollama instance
    // These should be run separately from unit tests
    
    /*
    @Test
    void testRealOllamaConnection() throws InterruptedException {
        // This test requires Ollama to be running
        // Skip if not available
        
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        
        ollamaAPI.testConnection((isSuccess, message) -> {
            success[0] = isSuccess;
            latch.countDown();
        });
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        // Note: This will fail if Ollama is not running
        // assertTrue(success[0], "Ollama should be accessible");
    }
    */
}
