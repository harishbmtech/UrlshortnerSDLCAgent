package com.example.it;

import com.example.sdlc.support.FakeOllamaServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Spring AI wiring: the auto-configured {@code OllamaChatModel} talks HTTP to a fake Ollama server.
 * This checks that per-agent model routing survives Spring AI's option merging and reaches the wire.
 * The fake answers in prose (not JSON), so every agent must fall back to its deterministic strategy and the run
 * must still reach the human release gate.
 */
@SpringBootTest(properties = {"sdlc.output-dir=target/sdlc-ollama-it", "sdlc.llm.health-cache-ttl=0s"})
@AutoConfigureMockMvc
class OllamaWiringIntegrationTest {

    static final FakeOllamaServer OLLAMA = new FakeOllamaServer(List.of("llama3.1:8b", "qwen2.5-coder:7b"));

    @DynamicPropertySource
    static void ollama(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", OLLAMA::baseUrl);
        registry.add("sdlc.llm.enabled", () -> "true");
    }

    @AfterAll
    static void stop() {
        OLLAMA.close();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void agentsReachOllamaThroughSpringAiEachWithItsOwnModel() throws Exception {
        OLLAMA.onChat(body -> "Sorry, I can only answer in prose.");

        mvc.perform(get("/api/v1/sdlc/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("ollama"))
                .andExpect(jsonPath("$.ollama.reachable").value(true))
                .andExpect(jsonPath("$.agents[0].agent").value("requirements_analysis"))
                .andExpect(jsonPath("$.agents[0].model").value("llama3.1:8b"))
                .andExpect(jsonPath("$.agents[6].agent").value("implementation"))
                .andExpect(jsonPath("$.agents[6].model").value("qwen2.5-coder:7b"));

        mvc.perform(post("/api/v1/sdlc/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Build a new URL shortener service from scratch: create short links via a REST API, "
                                + "redirect by code, click analytics, optional link expiry and custom aliases.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_RELEASE_APPROVAL"));

        assertTrue(OLLAMA.chatModels().contains("llama3.1:8b"), "reasoning agents called their model: " + OLLAMA.chatModels());
        assertTrue(OLLAMA.chatModels().contains("qwen2.5-coder:7b"), "coding agents called their model: " + OLLAMA.chatModels());

        mvc.perform(get("/api/v1/sdlc/agents"))
                .andExpect(jsonPath("$.agents[6].llmFailures").value(2));   // 2 attempts, then deterministic fallback
    }
}
