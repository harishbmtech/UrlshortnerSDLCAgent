package com.example.sdlc.agents;

import com.example.sdlc.agents.offline.Feature;
import com.example.sdlc.agents.offline.FeatureCatalog;
import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.RiskAssessment;
import com.example.sdlc.model.RiskAssessment.RiskItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Risk, trade-off and failure-scenario analysis (parallel branch). Its overall risk level drives whether the
 * design gate may auto-approve or must escalate to a human.
 */
public class RiskAgent extends AgentSupport {

    public RiskAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "risk_assessment";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow();
        ImpactAnalysis impact = state.impact().orElse(ImpactAnalysis.notApplicable("n/a"));
        Outcome<RiskAssessment> out = run(state,
                () -> ctx.llm().generate(name(), Prompts.RISK, "Spec:\n" + spec + "\n\nImpact:\n" + impact, RiskAssessment.class),
                r -> !r.risks().isEmpty() && r.risks().stream().allMatch(i -> i.mitigation() != null && !i.mitigation().isBlank()),
                () -> assess(spec, impact));
        RiskAssessment r = out.value();
        // Governance floor: the LLM cannot rate a change below what deterministic facts imply.
        RiskLevel floor = floor(spec, impact);
        if (!r.overall().atLeast(floor)) {
            r = new RiskAssessment(floor, r.risks(), r.tradeOffs(), r.failureScenarios());
        }
        return Map.of(SdlcState.RISK, r,
                SdlcState.DECISIONS, List.of(decision(out, "Overall risk " + r.overall() + " (" + r.risks().size() + " risks)",
                        "floor=" + floor, "spec", "impact")));
    }

    static RiskLevel floor(RequirementSpec spec, ImpactAnalysis impact) {
        RiskLevel f = RiskLevel.LOW;
        if (spec.changeType() == ChangeType.BROWNFIELD) f = RiskLevel.MEDIUM;
        if (!impact.schemaChanges().isEmpty()) f = RiskLevel.max(f, RiskLevel.MEDIUM);
        if (impact.blastRadius() > 8) f = RiskLevel.max(f, RiskLevel.HIGH);
        return f;
    }

    /** Deterministic strategy. */
    public static RiskAssessment assess(RequirementSpec spec, ImpactAnalysis impact) {
        Set<String> satisfied = impact.existingCapabilities().stream()
                .map(c -> c.substring(0, c.indexOf(':')).trim()).collect(Collectors.toSet());
        List<RiskItem> risks = new ArrayList<>();
        List<String> tradeOffs = new ArrayList<>();
        int n = 0;
        for (String name : spec.features()) {
            Feature f = FeatureCatalog.byName(name).orElse(null);
            if (f == null || satisfied.contains(f.name())) continue;
            for (Feature.RiskSpec rs : f.risks()) {
                risks.add(new RiskItem("R-" + (++n), rs.category(), rs.description(), rs.severity(), rs.mitigation()));
            }
            tradeOffs.addAll(f.tradeOffs());
        }
        if (spec.changeType() == ChangeType.BROWNFIELD) {
            risks.add(new RiskItem("R-" + (++n), "regression",
                    "Change touches " + impact.blastRadius() + " existing file(s) on the redirect/create paths",
                    impact.blastRadius() > 8 ? RiskLevel.HIGH : RiskLevel.MEDIUM,
                    "Regression suite on impacted modules; deploy behind canary; one-click rollback to previous build"));
        }
        if (risks.isEmpty()) {
            risks.add(new RiskItem("R-" + (++n), "delivery", "No material technical risk identified",
                    RiskLevel.LOW, "Standard review and CI"));
        }
        RiskLevel overall = risks.stream().map(RiskItem::severity).reduce(RiskLevel.LOW, RiskLevel::max);
        overall = RiskLevel.max(overall, floor(spec, impact));
        List<String> failures = new ArrayList<>(List.of(
                "Database unavailable -> redirects fail with 5xx; readiness probe removes the instance; alert on error rate",
                "Random-code collision storm (keyspace exhaustion) -> bounded retry then 500; alert and raise code length",
                "Flyway migration fails at startup -> instance does not start (fail fast); previous version keeps serving"));
        if (spec.features().contains("Hot-link caching")) {
            failures.add("Cache serves a link deactivated on another replica until TTL (30s) expires");
        }
        if (spec.features().contains("Max-clicks limit")) {
            failures.add("Burst of concurrent redirects on an almost-exhausted link overshoots maxClicks");
        }
        return new RiskAssessment(overall, risks, tradeOffs, failures);
    }
}
