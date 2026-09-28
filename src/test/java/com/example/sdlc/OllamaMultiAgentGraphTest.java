package com.example.sdlc;

import com.example.sdlc.agents.AgentDtos.DocsBundle;
import com.example.sdlc.agents.AgentDtos.GeneratedCode;
import com.example.sdlc.agents.AgentDtos.GeneratedFile;
import com.example.sdlc.agents.AgentDtos.PlanDraft;
import com.example.sdlc.agents.AgentDtos.TaskDraft;
import com.example.sdlc.agents.HumanGate;
import com.example.sdlc.graph.SdlcOrchestrator;
import com.example.sdlc.graph.SdlcOrchestrator.DecisionRequest;
import com.example.sdlc.graph.SdlcOrchestrator.RunRecord;
import com.example.sdlc.graph.SdlcOrchestrator.StartRequest;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.AgentModelRoster;
import com.example.sdlc.llm.OllamaHealthProbe;
import com.example.sdlc.llm.OllamaLlmGateway;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.Decision;
import com.example.sdlc.model.Enums.RunStatus;
import com.example.sdlc.model.Enums.Verdict;
import com.example.sdlc.support.FakeOllamaServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-agent graph with every agent on its Ollama model.
 * <p>
 * Record and replay: a first, deterministic run of the greenfield scenario records what each agent produced.
 * That output, serialised to JSON, becomes the "Ollama answer" for the same agent in a second run. In the second
 * run every agent calls the real {@link OllamaLlmGateway}. The gateway routes the call to the agent's model,
 * parses the JSON into the agent's output record, and passes it through the same guards and policy gates as live
 * model output. This checks the LLM path of the whole LangGraph4j graph (including the parallel design / risk /
 * test branches) without needing a GPU in CI.
 */
class OllamaMultiAgentGraphTest {

    static final String REASONING = "llama3.1:8b";
    static final String CODER = "qwen2.5-coder:7b";
    static final AgentModelRoster ROSTER = new AgentModelRoster(REASONING, Map.of(
            "codebase_analysis", CODER, "test_strategy", CODER, "implementation", CODER));

    static final Map<String, String> PROMPT_TO_AGENT = Map.of(
            Prompts.REQUIREMENTS, "requirements_analysis",
            Prompts.PLANNER, "planning",
            Prompts.CODEBASE, "codebase_analysis",
            Prompts.ARCHITECT, "architecture_design",
            Prompts.RISK, "risk_assessment",
            Prompts.TEST, "test_strategy",
            Prompts.DEVELOPER, "implementation",
            Prompts.WRITER, "documentation");

    /** Stands in for Spring AI's OllamaChatModel: answers each agent from the recording. */
    static final class ReplayOllamaModel implements ChatModel {
        final Map<String, String> answers;
        final Map<String, String> modelByAgent = new ConcurrentHashMap<>();
        final Set<String> misbehaving;

        ReplayOllamaModel(Map<String, String> answers, Set<String> misbehaving) {
            this.answers = answers;
            this.misbehaving = misbehaving;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String system = prompt.getInstructions().get(0).getText();
            String agent = PROMPT_TO_AGENT.get(system);
            modelByAgent.put(agent, ((OllamaOptions) prompt.getOptions()).getModel());
            String text = misbehaving.contains(agent)
                    ? "I'm sorry, as a language model I can only describe the code in prose."
                    : "<think>reasoning...</think>\n```json\n" + answers.get(agent) + "\n```";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    /** Runs greenfield deterministically and returns each agent's output as the JSON a model would return. */
    static Map<String, String> recordGreenfield() throws Exception {
        SdlcOrchestrator offline = TestOrchestrators.create();
        RunRecord run = offline.start(new StartRequest(SdlcOrchestratorScenarioTest.GREENFIELD, Map.of()));
        SdlcState s = offline.state(run.runId()).orElseThrow();
        ObjectMapper json = new ObjectMapper();
        Map<String, String> answers = new ConcurrentHashMap<>();
        answers.put("requirements_analysis", json.writeValueAsString(s.spec().orElseThrow()));
        answers.put("planning", json.writeValueAsString(new PlanDraft(s.plan().orElseThrow().tasks().stream()
                .map(t -> new TaskDraft(t.id(), t.title(), t.stage().name(), t.dependsOn(), t.impact().name(), t.rationale()))
                .toList(), s.plan().orElseThrow().rationale())));
        answers.put("architecture_design", json.writeValueAsString(s.design().orElseThrow()));
        answers.put("risk_assessment", json.writeValueAsString(s.risk().orElseThrow()));
        answers.put("test_strategy", json.writeValueAsString(s.testStrategy().orElseThrow()));
        answers.put("implementation", json.writeValueAsString(new GeneratedCode(s.artifacts().stream()
                .filter(a -> a.producedBy().equals("implementation"))
                .map(a -> new GeneratedFile(a.path(), a.type(), a.content())).toList(), "replayed")));
        answers.put("documentation", json.writeValueAsString(new DocsBundle(s.artifacts().stream()
                .filter(a -> a.producedBy().equals("documentation"))
                .map(a -> new GeneratedFile(a.path(), a.type(), a.content())).toList())));
        offline.close();
        return answers;
    }

    private static List<Decision> agentDecisions(SdlcState s, String agent) {
        return s.decisions().stream().filter(d -> d.actor().startsWith("agent:" + agent + "/")).toList();
    }

    @Test
    void everyAgentRunsOnItsOwnOllamaModelThroughTheWholeGraph() throws Exception {
        Map<String, String> answers = recordGreenfield();
        try (FakeOllamaServer ollama = new FakeOllamaServer(List.of(REASONING, CODER))) {
            ReplayOllamaModel model = new ReplayOllamaModel(answers, Set.of());
            OllamaLlmGateway gateway = new OllamaLlmGateway(model, ROSTER, new OllamaHealthProbe(ollama.baseUrl(), Duration.ofSeconds(30)),
                    new OllamaLlmGateway.Settings(true, OllamaLlmGateway.StructuredOutput.SCHEMA, 0.2, 16384));
            SdlcOrchestrator orchestrator = TestOrchestrators.create(gateway);

            RunRecord run = orchestrator.start(new StartRequest(SdlcOrchestratorScenarioTest.GREENFIELD, Map.of()));
            assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status(), String.valueOf(run.error()));
            SdlcState s = orchestrator.state(run.runId()).orElseThrow();

            for (String agent : List.of("requirements_analysis", "planning", "architecture_design", "risk_assessment",
                    "test_strategy", "implementation", "documentation")) {
                String expected = ROSTER.modelFor(agent);
                assertEquals(expected, model.modelByAgent.get(agent), agent + " routed to its model");
                List<Decision> ds = agentDecisions(s, agent);
                assertTrue(!ds.isEmpty() && ds.stream().allMatch(d -> d.actor().startsWith("agent:" + agent + "/llm[ollama:" + expected + "]")),
                        agent + " decision attributed to its Ollama model: " + ds);
            }
            assertEquals(CODER, model.modelByAgent.get("implementation"));
            assertEquals(REASONING, model.modelByAgent.get("architecture_design"));
            assertTrue(s.validation().orElseThrow().passed(), "model-written code passed the policy gate");

            run = orchestrator.decide(run.runId(), new DecisionRequest(HumanGate.RELEASE_APPROVAL, Verdict.APPROVE,
                    "release-manager", "LGTM", Map.of()));
            assertEquals(RunStatus.COMPLETED, run.status());
            assertTrue(orchestrator.context().audit().verify(run.runId()));
            orchestrator.close();
        }
    }

    @Test
    void misbehavingLocalModelIsContainedByGuardsAndFallback() throws Exception {
        Map<String, String> answers = recordGreenfield();
        try (FakeOllamaServer ollama = new FakeOllamaServer(List.of(REASONING, CODER))) {
            // the coder model answers in prose instead of JSON
            ReplayOllamaModel model = new ReplayOllamaModel(answers, Set.of("implementation"));
            OllamaLlmGateway gateway = new OllamaLlmGateway(model, ROSTER, new OllamaHealthProbe(ollama.baseUrl(), Duration.ofSeconds(30)),
                    new OllamaLlmGateway.Settings(true, OllamaLlmGateway.StructuredOutput.SCHEMA, 0.2, 16384));
            SdlcOrchestrator orchestrator = TestOrchestrators.create(gateway);

            RunRecord run = orchestrator.start(new StartRequest(SdlcOrchestratorScenarioTest.GREENFIELD, Map.of()));
            assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status());
            SdlcState s = orchestrator.state(run.runId()).orElseThrow();

            assertTrue(agentDecisions(s, "implementation").stream().allMatch(d -> d.actor().startsWith("agent:implementation/fallback")),
                    "unparseable model output never reaches downstream nodes");
            assertTrue(agentDecisions(s, "risk_assessment").get(0).actor().contains("/llm[ollama:" + REASONING + "]"),
                    "other agents keep using their models");
            assertTrue(orchestrator.context().metrics().snapshot().fallbacks() >= 1);
            orchestrator.close();
        }
    }

    @Test
    void ollamaDownMeansTheSameGraphRunsDeterministically() throws Exception {
        int closedPort;
        try (java.net.ServerSocket sock = new java.net.ServerSocket(0)) {
            closedPort = sock.getLocalPort();
        }
        ReplayOllamaModel model = new ReplayOllamaModel(Map.of(), Set.of());
        OllamaLlmGateway gateway = new OllamaLlmGateway(model, ROSTER,
                new OllamaHealthProbe("http://127.0.0.1:" + closedPort, Duration.ofSeconds(30)),
                new OllamaLlmGateway.Settings(true, OllamaLlmGateway.StructuredOutput.SCHEMA, 0.2, 16384));
        SdlcOrchestrator orchestrator = TestOrchestrators.create(gateway);

        RunRecord run = orchestrator.start(new StartRequest(SdlcOrchestratorScenarioTest.GREENFIELD, Map.of()));
        assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status());
        assertTrue(model.modelByAgent.isEmpty(), "no model calls while Ollama is down");
        SdlcState s = orchestrator.state(run.runId()).orElseThrow();
        assertTrue(agentDecisions(s, "implementation").get(0).actor().startsWith("agent:implementation/deterministic"));
        orchestrator.close();
    }
}
