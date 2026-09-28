package com.example.sdlc.agents;

import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Decision;
import com.example.sdlc.model.EngineeringTask;
import com.example.sdlc.model.Enums.RunStatus;
import com.example.sdlc.model.HumanDecision;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.Plan;
import com.example.sdlc.model.PolicyViolation;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.RiskAssessment;

import java.util.List;

/**
 * Renders the final engineering summary (markdown) from the checkpointed state. Deterministic: the summary
 * reports what actually happened, it is not generated prose.
 */
public final class SummaryWriter {

    private SummaryWriter() {
    }

    public static String write(SdlcState s, RunStatus status, String haltReason, String publishedTo) {
        StringBuilder md = new StringBuilder();
        RequirementSpec spec = s.spec().orElse(null);
        md.append("# Engineering summary - run ").append(s.runId()).append("\n\n");
        md.append("**Outcome:** ").append(status);
        if (haltReason != null) md.append(" - ").append(haltReason);
        if (publishedTo != null) md.append("  \n**Published to:** `").append(publishedTo).append('`');
        md.append("\n\n## 1. Requirement understanding\n");
        md.append("> ").append(s.requirement().replace("\n", "\n> ")).append("\n\n");
        if (spec != null) {
            md.append("- **Classification:** ").append(spec.changeType()).append("\n- **Features:** ")
                    .append(spec.features()).append("\n\n**Acceptance criteria**\n")
                    .append(AgentSupport.bullet(spec.acceptanceCriteria()));
            if (!spec.nonFunctional().isEmpty()) {
                md.append("\n**Non-functional**\n").append(AgentSupport.bullet(spec.nonFunctional()));
            }
            if (!s.clarifications().isEmpty()) {
                md.append("\n**Clarifications from humans**\n");
                s.clarifications().forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
            }
            if (!spec.ambiguities().isEmpty()) {
                md.append("\n**Open ambiguities**\n");
                spec.ambiguities().forEach(a -> md.append("- ").append(a.id()).append(a.blocking() ? " (blocking)" : "")
                        .append(": ").append(a.question()).append('\n'));
            }
        }

        s.plan().ifPresent(p -> planSection(md, p));
        s.impact().filter(ImpactAnalysis::applicable).ifPresent(i -> {
            md.append("\n## 3. Codebase impact (brownfield)\n").append(i.summary()).append("\n\n");
            i.components().forEach(c -> md.append("- `").append(c.path()).append("` [").append(c.layer()).append("] ")
                    .append(c.reason()).append('\n'));
            if (!i.impactedApis().isEmpty()) md.append("\n**APIs:** ").append(i.impactedApis()).append('\n');
            if (!i.schemaChanges().isEmpty()) md.append("**Schema:** ").append(i.schemaChanges()).append('\n');
            if (!i.existingCapabilities().isEmpty()) {
                md.append("\n**Already satisfied by existing code**\n").append(AgentSupport.bullet(i.existingCapabilities()));
            }
            if (!i.dataFlows().isEmpty()) md.append("\n**Data flows**\n").append(AgentSupport.bullet(i.dataFlows()));
        });
        s.design().ifPresent(d -> md.append("\n## 4. Design\n").append(d.overview()).append("\n\n**Decisions**\n")
                .append(AgentSupport.bullet(d.decisions())));

        md.append("\n## 5. Artifacts\n| Type | Path | sha256 | Plan | Producer |\n|---|---|---|---|---|\n");
        for (Artifact a : s.artifacts()) {
            md.append("| ").append(a.type()).append(" | `").append(a.path()).append("` | ")
                    .append(a.sha256(), 0, 12).append(" | v").append(a.planVersion()).append(" | ")
                    .append(a.producedBy()).append(" |\n");
        }

        md.append("\n## 6. Validation\n");
        s.validation().ifPresentOrElse(v -> {
            md.append("Attempt ").append(v.attempt()).append(": **").append(v.passed() ? "PASSED" : "FAILED")
                    .append("** (").append(v.rulesEvaluated().size()).append(" rules, ")
                    .append(v.artifactsChecked()).append(" artifacts)\n\n");
            for (PolicyViolation pv : v.violations()) {
                md.append("- ").append(pv.ruleId()).append(" ").append(pv.severity()).append(" `")
                        .append(pv.artifactPath()).append("`: ").append(pv.message()).append('\n');
            }
            md.append("\nRules: ").append(v.rulesEvaluated()).append('\n');
        }, () -> md.append("Not reached.\n"));
        List<String> validationFeedback = s.feedback().stream().filter(f -> f.startsWith("validation:")).toList();
        if (!validationFeedback.isEmpty()) {
            md.append("\n**Earlier failed attempts (fed back to the implementation agent)**\n")
                    .append(AgentSupport.bullet(validationFeedback));
        }
        s.readiness().ifPresent(r -> md.append("\n**Release readiness:** ").append(r.recommendation())
                .append(" (score ").append(r.score()).append(")\n").append(AgentSupport.bullet(r.passedChecks())));

        s.risk().ifPresent(r -> riskSection(md, r));

        md.append("\n## 8. Human checkpoints\n");
        if (s.approvals().isEmpty()) md.append("- none reached\n");
        for (HumanDecision h : s.approvals()) {
            md.append("- ").append(h.gate()).append(": **").append(h.verdict()).append("** by ").append(h.approver())
                    .append(" at ").append(h.at()).append(h.comment().isBlank() ? "" : " - \"" + h.comment() + "\"").append('\n');
        }

        md.append("\n## 9. Decision lineage\n");
        int i = 1;
        for (Decision d : s.decisions()) {
            md.append(i++).append(". **").append(d.node()).append("** (").append(d.actor()).append("): ")
                    .append(d.decision());
            if (d.rationale() != null && !d.rationale().isBlank()) md.append(" - _").append(d.rationale()).append("_");
            md.append('\n');
        }
        md.append("\n**Execution path:** ").append(String.join(" -> ", s.executionPath())).append('\n');
        md.append("\n**Control loop:** implementation attempts=").append(s.implementationAttempts())
                .append(", re-plans=").append(s.replans()).append(", plan version=v").append(s.planVersion()).append('\n');

        md.append("\n## 10. Assumptions and limitations\n");
        if (spec != null) md.append(AgentSupport.bullet(spec.assumptions()));
        md.append("""
                - Validation is static (policy rules + structural checks); generated code is compiled and tested by CI after publish, not inside the graph.
                - Offline mode uses curated templates for known features; unknown features produce change proposals instead of speculative code.
                - Checkpoints are in memory: a restart loses paused runs (swap MemorySaver for a persistent saver in production).
                """);
        return md.toString();
    }

    private static void planSection(StringBuilder md, Plan p) {
        md.append("\n## 2. Plan v").append(p.version()).append(" and rationale\n").append(p.rationale()).append("\n\n");
        md.append("| Task | Stage | Depends on | Impact | Title |\n|---|---|---|---|---|\n");
        for (EngineeringTask t : p.tasks()) {
            md.append("| ").append(t.id()).append(" | ").append(t.stage()).append(" | ").append(t.dependsOn())
                    .append(" | ").append(t.impact()).append(" | ").append(t.title()).append(" |\n");
        }
        md.append("\n**Execution waves (parallelisable groups):** ").append(p.executionWaves()).append('\n');
    }

    private static void riskSection(StringBuilder md, RiskAssessment r) {
        md.append("\n## 7. Risks, trade-offs, failure scenarios (overall ").append(r.overall()).append(")\n");
        r.risks().forEach(x -> md.append("- **").append(x.id()).append(" ").append(x.severity()).append(" ")
                .append(x.category()).append("** ").append(x.description()).append(" -> _").append(x.mitigation())
                .append("_\n"));
        if (!r.tradeOffs().isEmpty()) md.append("\n**Trade-offs**\n").append(AgentSupport.bullet(r.tradeOffs()));
        if (!r.failureScenarios().isEmpty()) md.append("\n**Failure scenarios**\n").append(AgentSupport.bullet(r.failureScenarios()));
    }
}
