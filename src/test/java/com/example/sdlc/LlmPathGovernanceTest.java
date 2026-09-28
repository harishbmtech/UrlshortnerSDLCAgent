package com.example.sdlc;

import com.example.sdlc.agents.AgentDtos.PlanDraft;
import com.example.sdlc.agents.AgentDtos.TaskDraft;
import com.example.sdlc.graph.SdlcOrchestrator;
import com.example.sdlc.graph.SdlcOrchestrator.RunRecord;
import com.example.sdlc.graph.SdlcOrchestrator.StartRequest;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.LlmGateway;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.RiskAssessment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the LLM code path with a scripted model: the governance layer must reject bad model output
 * (cyclic plans, hallucinated files, under-rated risk) and fall back safely.
 */
class LlmPathGovernanceTest {

    /** Scripted LLM: returns a canned answer per output type; unscripted types fail (-> deterministic fallback). */
    static final class ScriptedLlm implements LlmGateway {
        final Map<Class<?>, Supplier<Object>> answers = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String provider() {
            return "scripted";
        }

        @Override
        public <T> T generate(String agent, String systemPrompt, String userPrompt, Class<T> type) {
            calls.incrementAndGet();
            Supplier<Object> s = answers.get(type);
            if (s == null) throw new IllegalStateException("model timeout (simulated) for " + agent);
            return type.cast(s.get());
        }
    }

    @Test
    void badModelOutputIsRejectedAndReplacedByGuardedFallback() {
        ScriptedLlm llm = new ScriptedLlm();
        // cyclic plan -> must be rejected by the DAG guard
        llm.answers.put(PlanDraft.class, () -> new PlanDraft(List.of(
                new TaskDraft("T1", "a", "DESIGN", List.of("T2"), "LOW", ""),
                new TaskDraft("T2", "b", "IMPLEMENT", List.of("T1"), "LOW", "")), "cyclic"));
        // hallucinated file -> must be filtered out, leaving nothing -> rejected -> fallback
        llm.answers.put(ImpactAnalysis.class, () -> new ImpactAnalysis(true,
                List.of(new ImpactAnalysis.ImpactedComponent("src/main/java/com/example/Imaginary.java", "web", "modify")),
                List.of(), List.of(), List.of(), List.of(), 1, "made up"));
        // model under-rates the risk -> governance floor applies
        llm.answers.put(RiskAssessment.class, () -> new RiskAssessment(RiskLevel.LOW,
                List.of(new RiskAssessment.RiskItem("R-1", "x", "tiny", RiskLevel.LOW, "none needed really")),
                List.of(), List.of()));

        SdlcOrchestrator o = TestOrchestrators.create(llm);
        RunRecord run = o.start(new StartRequest(SdlcOrchestratorScenarioTest.BROWNFIELD, Map.of()));
        SdlcState s = o.state(run.runId()).orElseThrow();

        assertTrue(llm.calls.get() > 0);
        assertEquals(ChangeType.BROWNFIELD, s.spec().orElseThrow().changeType());
        assertTrue(s.decisions().stream().anyMatch(d -> d.actor().equals("agent:planning/fallback (attempts=2)")));
        ImpactAnalysis impact = s.impact().orElseThrow();
        assertFalse(impact.components().stream().anyMatch(c -> c.path().contains("Imaginary")), "hallucination dropped");
        assertTrue(s.risk().orElseThrow().overall().atLeast(RiskLevel.MEDIUM), "risk floor enforced for brownfield");
        assertTrue(s.decisions().stream().anyMatch(d -> d.actor().startsWith("agent:risk_assessment/llm")));
        assertTrue(o.context().metrics().snapshot().fallbacks() >= 2);
    }
}
