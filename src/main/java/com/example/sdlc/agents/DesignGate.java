package com.example.sdlc.agents;

import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.DesignSpec;
import com.example.sdlc.model.Enums.ArtifactType;
import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.RiskAssessment;
import com.example.sdlc.model.TestStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronisation barrier after the parallel design branches (architecture, risk, test strategy).
 * Deterministic: verifies all three branch outputs exist and belong to the current plan, freezes them as
 * the <em>design baseline</em> (the rollback target) and decides whether a human must approve the design.
 */
public class DesignGate extends AgentSupport {

    public static final String REQUIRES_APPROVAL = "designRequiresApproval";

    public DesignGate(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "design_gate";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow();
        DesignSpec design = state.design().orElseThrow(() -> new IllegalStateException("join: missing design branch"));
        RiskAssessment risk = state.risk().orElseThrow(() -> new IllegalStateException("join: missing risk branch"));
        TestStrategy tests = state.testStrategy().orElseThrow(() -> new IllegalStateException("join: missing test branch"));
        int pv = state.planVersion();
        String runTag = state.runId().substring(0, Math.min(8, state.runId().length()));

        List<Artifact> baseline = new ArrayList<>();
        List<String> lineage = List.of("plan:v" + pv, "spec", "design", "risk", "testStrategy");
        baseline.add(Artifact.of(ArtifactType.API_SPEC, "docs/api/openapi.yaml", design.openApiYaml(), name(), pv, lineage));
        if (!design.schemaDdl().isBlank()) {
            baseline.add(Artifact.of(ArtifactType.SCHEMA, "docs/design/schema-change.sql", design.schemaDdl(), name(), pv, lineage));
        }
        baseline.add(Artifact.of(ArtifactType.ADR, "docs/adr/ADR-" + runTag + "-design.md", adr(spec, design, risk), name(), pv, lineage));
        baseline.add(Artifact.of(ArtifactType.DOC, "docs/test-plan.md", testPlan(tests), name(), pv, lineage));

        RiskLevel threshold = ctx.settings().designApprovalThreshold();
        boolean needsHuman = risk.overall().atLeast(threshold);
        Map<String, Object> out = new HashMap<>();
        out.put(SdlcState.BASELINE_ARTIFACTS, baseline);
        out.put(SdlcState.ARTIFACTS, baseline);
        out.put(SdlcState.IMPL_ATTEMPTS, 0);
        out.put(REQUIRES_APPROVAL, needsHuman);
        out.put(SdlcState.DECISIONS, List.of(decision(
                needsHuman ? "Escalate design to human approval" : "Design auto-approved within autonomy boundary",
                "overall risk " + risk.overall() + (needsHuman ? " >= " : " < ") + "threshold " + threshold
                        + "; baseline frozen with " + baseline.size() + " artifacts",
                "design", "risk", "testStrategy")));
        return out;
    }

    private static String adr(RequirementSpec spec, DesignSpec d, RiskAssessment r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ADR: ").append(spec.title()).append("\n\n## Context\n").append(spec.summary()).append("\n\n");
        sb.append("## Design\n").append(d.overview()).append("\n\n### Components\n").append(bullet(d.components()));
        sb.append("\n## Decisions\n").append(bullet(d.decisions()));
        sb.append("\n## Risks\n");
        r.risks().forEach(i -> sb.append("- **").append(i.id()).append(" [").append(i.severity()).append("] ")
                .append(i.category()).append("**: ").append(i.description()).append(" _Mitigation:_ ")
                .append(i.mitigation()).append('\n'));
        sb.append("\n## Trade-offs\n").append(bullet(r.tradeOffs()));
        return sb.toString();
    }

    private static String testPlan(TestStrategy ts) {
        StringBuilder sb = new StringBuilder("# Test plan\n\n").append(ts.approach()).append("\n\n## Levels\n")
                .append(bullet(ts.levels())).append("\n| Case | Level | Covers | Name |\n|---|---|---|---|\n");
        ts.cases().forEach(c -> sb.append("| ").append(c.id()).append(" | ").append(c.level()).append(" | ")
                .append(c.covers()).append(" | ").append(c.name().replace("|", "/")).append(" |\n"));
        return sb.toString();
    }
}
