package com.example.sdlc.config;

import com.example.sdlc.agents.HumanGate;
import com.example.sdlc.graph.SdlcOrchestrator;
import com.example.sdlc.graph.SdlcOrchestrator.DecisionRequest;
import com.example.sdlc.graph.SdlcOrchestrator.RunRecord;
import com.example.sdlc.graph.SdlcOrchestrator.StartRequest;
import com.example.sdlc.model.Enums.RunStatus;
import com.example.sdlc.model.Enums.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-command demo ({@code --sdlc.demo.enabled=true}): runs the three required scenarios through the real
 * graph, playing the human roles with explicit, logged decisions. The API stays up afterwards for inspection.
 */
@Component
@ConditionalOnProperty(name = "sdlc.demo.enabled", havingValue = "true")
public class DemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    public static final String GREENFIELD = "Build a new URL shortener service from scratch: create short links via a REST API, "
            + "redirect by code, click analytics, optional link expiry and custom aliases.";
    public static final String BROWNFIELD = "Add an optional max-clicks limit to existing short links: once a link has been "
            + "followed N times it must stop redirecting and return 410 Gone.";
    public static final String AMBIGUOUS = "Make the short links faster and more secure.";

    private final SdlcOrchestrator orchestrator;

    public DemoRunner(SdlcOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(String... args) {
        Map<String, RunRecord> results = new LinkedHashMap<>();
        results.put("greenfield", drive(orchestrator.start(new StartRequest(GREENFIELD, Map.of()))));
        results.put("brownfield (+1 injected policy violation)",
                drive(orchestrator.start(new StartRequest(BROWNFIELD, Map.of("policy.implementation", 1)))));
        results.put("ambiguous", drive(orchestrator.start(new StartRequest(AMBIGUOUS, Map.of()))));

        StringBuilder sb = new StringBuilder("\n================ SDLC DEMO RESULTS ================\n");
        results.forEach((name, r) -> sb.append(String.format("%-45s %-10s run=%s%n", name, r.status(), r.runId())));
        sb.append("Inspect: GET /api/v1/sdlc/runs/{runId} | /summary | /audit ; metrics: GET /api/v1/sdlc/metrics\n");
        sb.append("Metrics: ").append(orchestrator.context().metrics().snapshot()).append('\n');
        log.info(sb.toString());
    }

    static final String FAST = "p95 redirect latency under 20 ms at 500 requests/second by caching hot links in-process";
    static final String SECURE = "block private-network (SSRF) targets and rate-limit link creation";

    /**
     * Answers every blocking ambiguity the requirements agent raised. The ids come from the run, because an
     * Ollama model may name them differently from the deterministic analyser (AMB-FAST / AMB-SECURE).
     */
    private Map<String, String> clarifications(RunRecord run) {
        Map<String, String> answers = new LinkedHashMap<>();
        orchestrator.state(run.runId()).flatMap(s -> s.spec()).ifPresent(spec -> spec.ambiguities().forEach(a -> {
            String text = (a.id() + " " + a.statement() + " " + a.question()).toLowerCase();
            boolean perf = text.contains("fast") || text.contains("latency") || text.contains("perform");
            boolean sec = text.contains("secur");
            answers.put(a.id(), perf && !sec ? FAST : sec && !perf ? SECURE : FAST + "; " + SECURE);
        }));
        if (answers.isEmpty()) {
            answers.put("AMB-FAST", FAST);
            answers.put("AMB-SECURE", SECURE);
        }
        return answers;
    }

    /** Plays the human roles: answers clarifications, approves design and release. */
    private RunRecord drive(RunRecord run) {
        for (int guard = 0; guard < 6 && run.status().isAwaitingHuman(); guard++) {
            String gate = run.pendingGate();
            Map<String, String> answers = gate.equals(HumanGate.CLARIFICATION) ? clarifications(run) : Map.of();
            String approver = switch (gate) {
                case HumanGate.DESIGN_APPROVAL -> "eng-manager";
                case HumanGate.RELEASE_APPROVAL -> "release-manager";
                default -> "tech-lead";
            };
            log.info("[demo] run {} waiting at {} -> {} approves", run.runId(), gate, approver);
            run = orchestrator.decide(run.runId(), new DecisionRequest(gate, Verdict.APPROVE, approver,
                    "demo approval", answers));
        }
        if (run.status() != RunStatus.COMPLETED) {
            log.warn("[demo] run {} ended {}: {}", run.runId(), run.status(), run.error());
        }
        return run;
    }
}
