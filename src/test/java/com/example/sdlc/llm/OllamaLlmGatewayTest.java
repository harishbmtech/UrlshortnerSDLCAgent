package com.example.sdlc.llm;

import com.example.sdlc.model.TestStrategy;
import com.example.sdlc.support.FakeOllamaServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaOptions;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ollama adapter on its own: per-agent model routing, schema-constrained output, model fallback, liveness
 * and cleaning of local-model output. The Ollama server is faked on a local port.
 */
class OllamaLlmGatewayTest {

    static final String REASONING = "llama3.1:8b";
    static final String CODER = "qwen2.5-coder:7b";

    static final AgentModelRoster ROSTER = new AgentModelRoster(REASONING, Map.of(
            "implementation", CODER, "test_strategy", CODER, "codebase_analysis", CODER));

    static final String STRATEGY_JSON = """
            {"cases":[{"id":"TC-1","name":"creates link","level":"unit","covers":"AC-1"}],
             "levels":["unit"],"approach":"test pyramid"}""";

    /** Captures the per-request Ollama options and replies with fixed text. */
    static final class CapturingModel implements ChatModel {
        final List<OllamaOptions> requests = new CopyOnWriteArrayList<>();
        volatile String reply = STRATEGY_JSON;

        @Override
        public ChatResponse call(Prompt prompt) {
            requests.add((OllamaOptions) prompt.getOptions());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    private static OllamaLlmGateway gateway(ChatModel model, String baseUrl, OllamaLlmGateway.StructuredOutput mode) {
        return new OllamaLlmGateway(model, ROSTER, new OllamaHealthProbe(baseUrl, Duration.ZERO),
                new OllamaLlmGateway.Settings(true, mode, 0.2, 16384));
    }

    @Test
    void eachAgentIsServedByItsOwnModelWithSchemaConstrainedOutput() {
        try (FakeOllamaServer ollama = new FakeOllamaServer(List.of(REASONING, CODER))) {
            CapturingModel model = new CapturingModel();
            OllamaLlmGateway gw = gateway(model, ollama.baseUrl(), OllamaLlmGateway.StructuredOutput.SCHEMA);

            assertTrue(gw.isAvailable());
            TestStrategy ts = gw.generate("test_strategy", "sys", "user", TestStrategy.class);
            gw.generate("risk_assessment", "sys", "user", TestStrategy.class);

            assertEquals("AC-1", ts.cases().get(0).covers(), "JSON answer mapped onto the agent's output record");
            assertEquals(CODER, model.requests.get(0).getModel());
            assertEquals(REASONING, model.requests.get(1).getModel());
            assertTrue(model.requests.get(0).getFormat() instanceof Map<?, ?>, "JSON schema sent as Ollama format");
            assertEquals(Integer.valueOf(16384), model.requests.get(0).getNumCtx());

            Map<String, Object> view = gw.describe();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) view.get("agents");
            Map<String, Object> impl = agents.stream().filter(a -> a.get("agent").equals("implementation")).findFirst().orElseThrow();
            assertEquals(CODER, impl.get("model"));
            assertEquals(true, impl.get("modelPulled"));
            assertEquals(8, agents.size());
        }
    }

    @Test
    void plainJsonModeIsAvailableForOlderOllamaServers() {
        try (FakeOllamaServer ollama = new FakeOllamaServer(List.of(REASONING, CODER))) {
            CapturingModel model = new CapturingModel();
            gateway(model, ollama.baseUrl(), OllamaLlmGateway.StructuredOutput.JSON)
                    .generate("planning", "sys", "user", TestStrategy.class);
            assertEquals("json", model.requests.get(0).getFormat());
        }
    }

    @Test
    void missingAgentModelFallsBackToDefaultModelThenFailsFast() {
        try (FakeOllamaServer ollama = new FakeOllamaServer(List.of(REASONING))) {
            CapturingModel model = new CapturingModel();
            OllamaLlmGateway gw = gateway(model, ollama.baseUrl(), OllamaLlmGateway.StructuredOutput.SCHEMA);

            gw.generate("implementation", "sys", "user", TestStrategy.class);
            assertEquals(REASONING, model.requests.get(0).getModel(), "coder model not pulled -> default model");

            ollama.setInstalled(List.of("mistral:7b"));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> gw.generate("implementation", "sys", "user", TestStrategy.class));
            assertTrue(e.getMessage().contains("ollama pull " + CODER), e.getMessage());
            assertEquals(1, model.requests.size(), "no model call when nothing usable is pulled");
        }
    }

    @Test
    void unreachableOllamaSwitchesAgentsToDeterministicMode() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        OllamaLlmGateway gw = gateway(new CapturingModel(), "http://127.0.0.1:" + closedPort, OllamaLlmGateway.StructuredOutput.SCHEMA);
        assertFalse(gw.isAvailable());
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) gw.describe().get("ollama");
        assertEquals(false, ollama.get("reachable"));
        assertEquals(List.of("ollama pull " + REASONING, "ollama pull " + CODER), ollama.get("pullCommands"));
    }

    @Test
    void failedModelCallsAreCountedPerAgent() {
        try (FakeOllamaServer ollama = new FakeOllamaServer(List.of(REASONING, CODER))) {
            CapturingModel model = new CapturingModel();
            model.reply = "Sure! I'd be happy to help, but I need more details.";
            OllamaLlmGateway gw = gateway(model, ollama.baseUrl(), OllamaLlmGateway.StructuredOutput.SCHEMA);
            assertThrows(RuntimeException.class, () -> gw.generate("documentation", "sys", "user", TestStrategy.class));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agents = (List<Map<String, Object>>) gw.describe().get("agents");
            Map<String, Object> docs = agents.stream().filter(a -> a.get("agent").equals("documentation")).findFirst().orElseThrow();
            assertEquals(1L, docs.get("llmCalls"));
            assertEquals(1L, docs.get("llmFailures"));
        }
    }

    @Test
    void localModelOutputIsCleanedBeforeParsing() {
        assertEquals("{\"a\":1}", OllamaLlmGateway.cleanModelOutput("<think>\nlet me plan {x}\n</think>\n{\"a\":1}"));
        assertEquals("{\"a\":1}", OllamaLlmGateway.cleanModelOutput("Here you go:\n```json\n{\"a\":1}\n```\nDone."));
        assertEquals("{\"a\":{\"b\":2}}", OllamaLlmGateway.cleanModelOutput("Result: {\"a\":{\"b\":2}} hope that helps"));
        assertEquals("", OllamaLlmGateway.cleanModelOutput(null));
        // regression: fences inside JSON string values (generated docs) must not cut the JSON short
        String docs = "{\"documents\":[{\"path\":\"docs/runbook.md\",\"content\":\"```bash\\ncurl x\\n```\"}]}";
        assertEquals(docs, OllamaLlmGateway.cleanModelOutput("```json\n" + docs + "\n```"));
    }

    @Test
    void tagsAreParsedAndLatestIsImplicit() {
        assertEquals(java.util.Set.of("llama3.1:8b", "phi4:latest"),
                OllamaHealthProbe.parseModelNames("{\"models\":[{\"name\":\"llama3.1:8b\",\"details\":{\"family\":\"llama\"}},{\"name\":\"phi4\"}]}"));
        assertEquals("qwen3:latest", OllamaHealthProbe.normalize("qwen3"));
        assertEquals(CODER, ROSTER.modelFor("implementation"));
        assertEquals(REASONING, ROSTER.modelFor("planning"));
        assertEquals(List.of(REASONING, CODER), List.copyOf(ROSTER.requiredModels()));
        // bracketed YAML keys and Spring's relaxed-binding form resolve to the same agent
        AgentModelRoster fromYaml = new AgentModelRoster(REASONING, Map.of("[test_strategy]", CODER, "codebaseanalysis", CODER));
        assertEquals(CODER, fromYaml.modelFor("test_strategy"));
        assertEquals(CODER, fromYaml.modelFor("codebase_analysis"));
        assertEquals(REASONING, fromYaml.modelFor("planning"));
    }
}
