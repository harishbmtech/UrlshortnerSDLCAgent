package com.example.sdlc.agents;

import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.ArtifactType;
import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.Enums.Verdict;
import com.example.sdlc.model.ReleaseReadiness;
import com.example.sdlc.model.RiskAssessment;
import com.example.sdlc.model.ValidationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic release-readiness checklist. A GO still requires explicit human release approval.
 */
public class ReleaseReadinessAgent extends AgentSupport {

    public ReleaseReadinessAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "release_readiness";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        List<String> passed = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        ValidationReport v = state.validation().orElse(null);
        RiskAssessment risk = state.risk().orElseThrow();
        List<Artifact> artifacts = state.artifacts();
        int pv = state.planVersion();

        check(v != null && v.passed(), "Policy validation passed (no blocker/major)", passed, blockers);
        check(state.testStrategy().map(ts -> TestStrategyAgent.covers(ts, state.spec().orElseThrow())).orElse(false),
                "All acceptance criteria traced to test cases", passed, blockers);
        boolean hasCode = artifacts.stream().anyMatch(a -> a.type() == ArtifactType.CODE);
        check(!hasCode || artifacts.stream().anyMatch(a -> a.type() == ArtifactType.TEST),
                "Code changes ship with tests", passed, blockers);
        check(risk.risks().stream().filter(r -> r.severity().atLeast(RiskLevel.HIGH))
                        .allMatch(r -> r.mitigation() != null && r.mitigation().length() > 10),
                "Every HIGH/CRITICAL risk has a mitigation", passed, blockers);
        check(artifacts.stream().anyMatch(a -> a.path().endsWith("runbook.md") && a.content().contains("## Rollback")),
                "Runbook with rollback procedure present", passed, blockers);
        check(artifacts.stream().filter(a -> a.type() != ArtifactType.DOC).allMatch(a -> a.planVersion() == pv),
                "Artifact lineage consistent with current plan v" + pv + " (no stale outputs)", passed, blockers);
        boolean designNeededHuman = state.<Boolean>value(DesignGate.REQUIRES_APPROVAL).orElse(false);
        check(!designNeededHuman || state.approvals().stream()
                        .anyMatch(a -> a.gate().equals(HumanGate.DESIGN_APPROVAL) && a.verdict() == Verdict.APPROVE),
                designNeededHuman ? "High-risk design carries a human approval"
                        : "Design within autonomy boundary (no human design approval required)", passed, blockers);

        int total = passed.size() + blockers.size();
        int score = total == 0 ? 0 : (int) Math.round(100.0 * passed.size() / total);
        boolean ready = blockers.isEmpty();
        ReleaseReadiness r = new ReleaseReadiness(ready, score, passed, blockers,
                ready ? "GO - awaiting human release approval" : "NO-GO - " + blockers);
        return Map.of(SdlcState.READINESS, r,
                SdlcState.DECISIONS, List.of(decision(r.recommendation(), "score " + score + "/100", "validation", "risk", "artifacts")));
    }

    private static void check(boolean ok, String name, List<String> passed, List<String> blockers) {
        (ok ? passed : blockers).add(name);
    }
}
