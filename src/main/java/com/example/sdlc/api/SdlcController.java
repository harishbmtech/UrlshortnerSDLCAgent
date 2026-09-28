package com.example.sdlc.api;

import com.example.sdlc.governance.HashChainedAuditLog;
import com.example.sdlc.governance.ReliabilityMetrics;
import com.example.sdlc.graph.SdlcOrchestrator;
import com.example.sdlc.graph.SdlcOrchestrator.DecisionRequest;
import com.example.sdlc.graph.SdlcOrchestrator.RunRecord;
import com.example.sdlc.graph.SdlcOrchestrator.StartRequest;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.LlmGateway;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.Verdict;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST surface of the agentic SDLC orchestrator (start runs, human checkpoints, kill switch, observability).
 */
@RestController
@RequestMapping("/api/v1/sdlc")
public class SdlcController {

    public record StartRunRequest(@NotBlank @Size(max = 8000) String requirement, Map<String, Integer> faults,
                                  Boolean async) {
    }

    public record DecisionBody(@NotBlank String gate, @NotNull Verdict verdict, @NotBlank String approver,
                               String comment, Map<String, String> clarifications) {
    }

    public record StopBody(String reason) {
    }

    public record RunListItem(String runId, String status, String pendingGate, String requirement) {
    }

    private final SdlcOrchestrator orchestrator;
    private final HashChainedAuditLog audit;
    private final ReliabilityMetrics metrics;
    private final LlmGateway llm;

    public SdlcController(SdlcOrchestrator orchestrator, HashChainedAuditLog audit, ReliabilityMetrics metrics,
                          LlmGateway llm) {
        this.orchestrator = orchestrator;
        this.audit = audit;
        this.metrics = metrics;
        this.llm = llm;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunView> start(@Valid @RequestBody StartRunRequest body) {
        StartRequest req = new StartRequest(body.requirement(), body.faults());
        boolean async = Boolean.TRUE.equals(body.async());
        RunRecord run = async ? orchestrator.startAsync(req) : orchestrator.start(req);
        return ResponseEntity.status(async ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .location(URI.create("/api/v1/sdlc/runs/" + run.runId()))
                .body(view(run));
    }

    @GetMapping("/runs")
    public List<RunListItem> list() {
        return orchestrator.list().stream()
                .sorted(Comparator.comparing(RunRecord::createdAt).reversed())
                .map(r -> new RunListItem(r.runId(), r.status().name(), r.pendingGate(), r.requirement()))
                .toList();
    }

    @GetMapping("/runs/{runId}")
    public RunView get(@PathVariable String runId) {
        return view(orchestrator.find(runId).orElseThrow(() -> new NoSuchElementException("Unknown run " + runId)));
    }

    @PostMapping("/runs/{runId}/decisions")
    public RunView decide(@PathVariable String runId, @Valid @RequestBody DecisionBody body) {
        return view(orchestrator.decide(runId, new DecisionRequest(body.gate(), body.verdict(), body.approver(),
                body.comment(), body.clarifications())));
    }

    @PostMapping("/runs/{runId}/stop")
    public RunView stop(@PathVariable String runId, @RequestBody(required = false) StopBody body) {
        return view(orchestrator.stop(runId, body == null ? null : body.reason()));
    }

    @PostMapping("/runs/{runId}/resume")
    public RunView resume(@PathVariable String runId) {
        return view(orchestrator.resume(runId));
    }

    @GetMapping("/runs/{runId}/audit")
    public Map<String, Object> audit(@PathVariable String runId) {
        get(runId);
        return Map.of("runId", runId, "chainVerified", audit.verify(runId),
                "events", audit.events(runId).stream().map(HashChainedAuditLog::toMap).toList());
    }

    @GetMapping(value = "/runs/{runId}/summary", produces = MediaType.TEXT_PLAIN_VALUE)
    public String summary(@PathVariable String runId) {
        get(runId);
        return orchestrator.state(runId).flatMap(SdlcState::summary)
                .orElse("Summary is produced when the run completes or halts.");
    }

    @GetMapping(value = "/runs/{runId}/artifact", produces = MediaType.TEXT_PLAIN_VALUE)
    public String artifact(@PathVariable String runId, @RequestParam String path) {
        return orchestrator.state(runId).orElseThrow(() -> new NoSuchElementException("Unknown run " + runId))
                .artifacts().stream().filter(a -> a.path().equals(path)).findFirst().map(Artifact::content)
                .orElseThrow(() -> new NoSuchElementException("No artifact " + path));
    }

    @GetMapping("/metrics")
    public ReliabilityMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    /** The multi-agent roster: which Ollama model serves each agent, Ollama health, missing models, per-agent stats. */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        return llm.describe();
    }

    @GetMapping(value = "/graph", produces = MediaType.TEXT_PLAIN_VALUE)
    public String graph() {
        return orchestrator.mermaid();
    }

    private RunView view(RunRecord run) {
        return RunView.of(run, orchestrator.state(run.runId()).orElse(null));
    }
}
