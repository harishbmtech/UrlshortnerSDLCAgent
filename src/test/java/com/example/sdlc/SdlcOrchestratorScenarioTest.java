package com.example.sdlc;

import com.example.sdlc.agents.HumanGate;
import com.example.sdlc.graph.SdlcOrchestrator;
import com.example.sdlc.graph.SdlcOrchestrator.DecisionRequest;
import com.example.sdlc.graph.SdlcOrchestrator.RunRecord;
import com.example.sdlc.graph.SdlcOrchestrator.StartRequest;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.Enums.RunStatus;
import com.example.sdlc.model.Enums.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end runs of the real LangGraph4j graph in offline mode: the three required scenarios plus the
 * governance control loops (retry, rollback, fallback, re-plan, kill switch, approver authorisation).
 */
class SdlcOrchestratorScenarioTest {

    static final String GREENFIELD = "Build a new URL shortener service from scratch: create short links via a REST API, "
            + "redirect by code, click analytics, optional link expiry and custom aliases.";
    static final String BROWNFIELD = "Add an optional max-clicks limit to existing short links: once a link has been "
            + "followed N times it must stop redirecting and return 410 Gone.";
    static final String AMBIGUOUS = "Make the short links faster and more secure.";

    private final SdlcOrchestrator orchestrator = TestOrchestrators.create();

    private RunRecord approve(RunRecord run, String gate, String approver) {
        assertEquals(gate, run.pendingGate(), "pending gate");
        return orchestrator.decide(run.runId(), new DecisionRequest(gate, Verdict.APPROVE, approver, "LGTM", Map.of()));
    }

    private SdlcState state(RunRecord run) {
        return orchestrator.state(run.runId()).orElseThrow();
    }

    @Test
    void greenfieldRunsEndToEndWithReleaseApproval() throws Exception {
        RunRecord run = orchestrator.start(new StartRequest(GREENFIELD, Map.of()));

        assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status(), "medium risk design is auto-approved; release needs a human");
        SdlcState s = state(run);
        assertEquals(ChangeType.GREENFIELD, s.spec().orElseThrow().changeType());
        assertFalse(s.impact().orElseThrow().applicable());
        assertTrue(s.validation().orElseThrow().passed());
        assertTrue(s.executionNodes().containsAll(java.util.List.of("architecture_design", "risk_assessment", "test_strategy")),
                "parallel branches executed");
        assertTrue(s.artifacts().stream().anyMatch(a -> a.path().endsWith("ShortUrlService.java")));

        run = approve(run, HumanGate.RELEASE_APPROVAL, "release-manager");

        assertEquals(RunStatus.COMPLETED, run.status());
        s = state(run);
        Path published = Path.of(s.publishedTo().orElseThrow());
        assertTrue(Files.exists(published.resolve("manifest.json")));
        assertTrue(Files.exists(published.resolve("ENGINEERING-SUMMARY.md")));
        assertTrue(s.summary().orElseThrow().contains("## 6. Validation"));
        assertTrue(orchestrator.context().audit().verify(run.runId()), "audit hash chain intact");
    }

    @Test
    void brownfieldReasonsAboutCodebaseAndReplansForSchemaChange() {
        RunRecord run = orchestrator.start(new StartRequest(BROWNFIELD, Map.of()));

        assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status());
        SdlcState s = state(run);
        assertEquals(ChangeType.BROWNFIELD, s.spec().orElseThrow().changeType());
        var impact = s.impact().orElseThrow();
        assertTrue(impact.components().stream().anyMatch(c -> c.path().endsWith("domain/ShortUrl.java") && c.reason().startsWith("modify")));
        assertTrue(impact.components().stream().anyMatch(c -> c.path().endsWith("web/ShortUrlController.java")));
        assertFalse(impact.schemaChanges().isEmpty());
        assertEquals(2, s.planVersion(), "impact analysis triggered a re-plan (migration task)");
        assertTrue(s.plan().orElseThrow().tasks().stream().anyMatch(t -> t.title().startsWith("Add additive DB migration")));
        assertTrue(s.artifacts().stream().map(Artifact::path).anyMatch(p -> p.endsWith("V2__add_max_clicks_to_short_url.sql")));
        assertTrue(s.artifacts().stream().map(Artifact::path).anyMatch(p -> p.endsWith("MaxClicksLimitTest.java")));
        assertTrue(s.validation().orElseThrow().passed());

        run = approve(run, HumanGate.RELEASE_APPROVAL, "tech-lead");
        assertEquals(RunStatus.COMPLETED, run.status());
    }

    @Test
    void ambiguousRequirementPausesForClarificationThenEscalatesHighRiskDesign() {
        RunRecord run = orchestrator.start(new StartRequest(AMBIGUOUS, Map.of()));

        assertEquals(RunStatus.AWAITING_CLARIFICATION, run.status());
        var spec = state(run).spec().orElseThrow();
        assertTrue(spec.blockingAmbiguities().stream().anyMatch(a -> a.id().equals("AMB-FAST")));
        assertTrue(spec.blockingAmbiguities().stream().anyMatch(a -> a.id().equals("AMB-SECURE")));

        run = orchestrator.decide(run.runId(), new DecisionRequest(HumanGate.CLARIFICATION, Verdict.APPROVE, "tech-lead",
                "answers inline", Map.of(
                        "AMB-FAST", "p95 redirect latency under 20 ms at 500 requests/second by caching hot links in-process",
                        "AMB-SECURE", "block private-network (SSRF) targets and rate-limit link creation")));

        assertEquals(RunStatus.AWAITING_DESIGN_APPROVAL, run.status(), "caching is HIGH risk -> human design review");
        SdlcState s = state(run);
        assertEquals(RiskLevel.HIGH, s.risk().orElseThrow().overall());
        assertEquals(2, s.impact().orElseThrow().existingCapabilities().size(), "SSRF + rate limiting already exist");

        run = approve(run, HumanGate.DESIGN_APPROVAL, "eng-manager");
        assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status());
        assertTrue(state(run).artifacts().stream().anyMatch(a -> a.path().endsWith("CachingShortUrlStore.java")));

        run = approve(run, HumanGate.RELEASE_APPROVAL, "release-manager");
        assertEquals(RunStatus.COMPLETED, run.status());
    }

    @Test
    void policyViolationIsRetriedWithFeedbackThenPasses() {
        RunRecord run = orchestrator.start(new StartRequest(BROWNFIELD, Map.of("policy.implementation", 1)));

        assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status());
        SdlcState s = state(run);
        assertEquals(2, s.implementationAttempts());
        assertTrue(s.feedback().stream().anyMatch(f -> f.contains("SEC-001")));
        assertTrue(s.feedback().stream().anyMatch(f -> f.contains("SEC-002")));
        assertTrue(orchestrator.context().metrics().snapshot().validationFailures() >= 1);
        assertTrue(orchestrator.context().metrics().snapshot().mttrMs() >= 0);
    }

    @Test
    void persistentViolationsAreRolledBackAndSafeStopped() {
        RunRecord run = orchestrator.start(new StartRequest(BROWNFIELD, Map.of("policy.implementation", 9)));

        assertEquals(RunStatus.HALTED, run.status());
        SdlcState s = state(run);
        assertEquals(3, s.implementationAttempts(), "bounded by maxImplementationAttempts");
        assertEquals(s.baselineArtifacts(), s.artifacts(), "rolled back to design baseline");
        assertTrue(s.executionNodes().contains("rollback"));
        assertTrue(s.summary().orElseThrow().contains("HALTED"));
        assertEquals(1, orchestrator.context().metrics().snapshot().rollbacks());
    }

    @Test
    void transientAgentFailuresAreRetriedThenFallBack() {
        RunRecord run = orchestrator.start(new StartRequest(GREENFIELD, Map.of("transient.planning", 5)));

        assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status());
        var m = orchestrator.context().metrics().snapshot();
        assertTrue(m.retries() >= 1);
        assertEquals(1, m.fallbacks());
        assertTrue(state(run).decisions().stream().anyMatch(d -> d.actor().contains("planning/fallback")));
    }

    @Test
    void releaseRejectionTriggersReplanWithFeedback() {
        RunRecord run = orchestrator.start(new StartRequest(BROWNFIELD, Map.of()));
        int before = state(run).planVersion();

        run = orchestrator.decide(run.runId(), new DecisionRequest(HumanGate.RELEASE_APPROVAL, Verdict.REJECT,
                "release-manager", "Return maxClicks in the GET response as well", Map.of()));

        assertEquals(RunStatus.AWAITING_RELEASE_APPROVAL, run.status(), "re-planned and came back to the gate");
        SdlcState s = state(run);
        assertTrue(s.planVersion() > before);
        assertTrue(s.plan().orElseThrow().tasks().stream().anyMatch(t -> t.title().contains("Return maxClicks")));
        assertEquals(1, s.replans());
    }

    @Test
    void killSwitchAndApproverAuthorisation() {
        RunRecord run = orchestrator.start(new StartRequest(AMBIGUOUS, Map.of()));

        assertThrows(SecurityException.class, () -> orchestrator.decide(run.runId(),
                new DecisionRequest(HumanGate.CLARIFICATION, Verdict.APPROVE, "some-agent", "", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.decide(run.runId(),
                new DecisionRequest(HumanGate.RELEASE_APPROVAL, Verdict.APPROVE, "tech-lead", "", Map.of())));

        RunRecord stopped = orchestrator.stop(run.runId(), "operator test");
        assertEquals(RunStatus.HALTED, stopped.status());
        assertNotNull(stopped.error());
    }

    @Test
    void abortAtClarificationSafeStops() {
        RunRecord run = orchestrator.start(new StartRequest(AMBIGUOUS, Map.of()));
        run = orchestrator.decide(run.runId(), new DecisionRequest(HumanGate.CLARIFICATION, Verdict.ABORT,
                "tech-lead", "out of scope", Map.of()));
        assertEquals(RunStatus.HALTED, run.status());
    }

    @Test
    void graphIsExportableAsMermaid() {
        String mermaid = orchestrator.mermaid();
        assertTrue(mermaid.contains("design_gate"));
        assertTrue(mermaid.contains("release_approval"));
    }
}
