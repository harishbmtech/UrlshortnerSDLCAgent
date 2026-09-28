package com.example.sdlc.api;

import com.example.sdlc.graph.SdlcOrchestrator.RunRecord;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Decision;
import com.example.sdlc.model.HumanDecision;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.Plan;
import com.example.sdlc.model.ReleaseReadiness;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.RiskAssessment;
import com.example.sdlc.model.ValidationReport;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API projection of a run: bookkeeping + the relevant slices of the checkpointed graph state.
 */
public record RunView(
        String runId,
        String status,
        String pendingGate,
        String nextAction,
        String error,
        Instant createdAt,
        Instant updatedAt,
        long agentProcessingMs,
        int planVersion,
        int implementationAttempts,
        int replans,
        RequirementSpec spec,
        Map<String, String> clarifications,
        Plan plan,
        ImpactAnalysis impact,
        RiskAssessment risk,
        ValidationReport validation,
        ReleaseReadiness readiness,
        List<ArtifactRef> artifacts,
        List<HumanDecision> approvals,
        List<Decision> decisions,
        List<String> executionPath,
        String publishedTo) {

    public record ArtifactRef(String path, String type, int sizeBytes, String sha256, int planVersion, String producedBy,
                              List<String> derivedFrom) {
    }

    static RunView of(RunRecord r, SdlcState s) {
        String next = switch (r.status()) {
            case AWAITING_CLARIFICATION -> "POST /api/v1/sdlc/runs/" + r.runId()
                    + "/decisions {gate: clarification_gate, verdict: APPROVE, approver, clarifications: {AMB-..: answer}}";
            case AWAITING_DESIGN_APPROVAL, AWAITING_RELEASE_APPROVAL -> "POST /api/v1/sdlc/runs/" + r.runId()
                    + "/decisions {gate: " + r.pendingGate() + ", verdict: APPROVE|REJECT|ABORT, approver, comment}";
            case FAILED, HALTED -> "inspect /audit and /summary; POST /resume to continue from the last checkpoint";
            default -> null;
        };
        return new RunView(r.runId(), r.status().name(), r.pendingGate(), next, r.error(), r.createdAt(), r.updatedAt(),
                r.agentMillis(),
                s == null ? 0 : s.planVersion(),
                s == null ? 0 : s.implementationAttempts(),
                s == null ? 0 : s.replans(),
                s == null ? null : s.spec().orElse(null),
                s == null ? Map.of() : s.clarifications(),
                s == null ? null : s.plan().orElse(null),
                s == null ? null : s.impact().orElse(null),
                s == null ? null : s.risk().orElse(null),
                s == null ? null : s.validation().orElse(null),
                s == null ? null : s.readiness().orElse(null),
                s == null ? List.of() : s.artifacts().stream().map(a -> new ArtifactRef(a.path(), a.type().name(),
                        a.content().length(), a.sha256(), a.planVersion(), a.producedBy(), a.derivedFrom())).toList(),
                s == null ? List.of() : s.approvals(),
                s == null ? List.of() : s.decisions(),
                s == null ? List.of() : s.executionPath(),
                s == null ? null : s.publishedTo().orElse(null));
    }
}
