package com.example.sdlc.agents;

import com.example.sdlc.codebase.CodebaseIndex;
import com.example.sdlc.governance.policy.PolicyContext;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.ValidationReport;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic validation gate (no LLM): runs all policy guardrails over the produced artifacts.
 * Failure feeds concrete violations back to the implementation agent (bounded retry); exhaustion triggers rollback.
 * Also measures recovery time (MTTR) from the first failure to the next passing validation.
 */
public class ValidationAgent extends AgentSupport {

    public ValidationAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "validation";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow();
        ImpactAnalysis impact = state.impact().orElse(ImpactAnalysis.notApplicable("n/a"));
        CodebaseIndex index = ctx.codebase().get();
        Set<String> impacted = impact.components().stream().map(ImpactAnalysis.ImpactedComponent::path)
                .collect(Collectors.toSet());
        PolicyContext pc = new PolicyContext(spec.changeType(),
                spec.changeType() == ChangeType.BROWNFIELD ? index.paths() : Set.of(),
                impacted, index.migrations(), spec.acceptanceCriteriaIds(), state.testStrategy().orElse(null));

        int attempt = state.implementationAttempts();
        ValidationReport report = ctx.policy().evaluate(state.artifacts(), pc, attempt);
        long now = System.currentTimeMillis();

        Map<String, Object> out = new HashMap<>();
        out.put(SdlcState.VALIDATION, report);
        if (report.passed()) {
            state.lastFailureAt().ifPresent(t -> ctx.metrics().recovered(now - t));
            out.put(SdlcState.LAST_FAILURE_AT, AgentState.MARK_FOR_REMOVAL);
        } else {
            ctx.metrics().validationFailed();
            if (state.lastFailureAt().isEmpty()) out.put(SdlcState.LAST_FAILURE_AT, now);
            out.put(SdlcState.FEEDBACK, report.violations().stream()
                    .filter(v -> v.severity() != com.example.sdlc.model.Enums.Severity.MINOR)
                    .map(v -> "validation: plan v" + state.planVersion() + " attempt " + attempt + " " + v.ruleId() + " " + v.severity() + " "
                            + v.artifactPath() + " - " + v.message())
                    .toList());
        }
        String verdict = report.passed()
                ? "PASSED (" + report.violations().size() + " minor findings)"
                : "FAILED: " + report.count(com.example.sdlc.model.Enums.Severity.BLOCKER) + " blocker, "
                + report.count(com.example.sdlc.model.Enums.Severity.MAJOR) + " major";
        out.put(SdlcState.DECISIONS, List.of(decision("Validation attempt " + attempt + " " + verdict,
                report.rulesEvaluated().size() + " rules over " + report.artifactsChecked() + " artifacts",
                "artifacts", "policy")));
        return out;
    }
}
