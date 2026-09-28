package com.example.sdlc.agents;

import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.TestStrategy;
import com.example.sdlc.model.TestStrategy.TestCase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Test strategy with explicit traceability (parallel branch).
 */
public class TestStrategyAgent extends AgentSupport {

    public TestStrategyAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "test_strategy";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow();
        ImpactAnalysis impact = state.impact().orElse(ImpactAnalysis.notApplicable("n/a"));
        Outcome<TestStrategy> out = run(state,
                () -> ctx.llm().generate(name(), Prompts.TEST, "Spec:\n" + spec + "\n\nImpact:\n" + impact, TestStrategy.class),
                ts -> covers(ts, spec),
                () -> strategy(spec, impact));
        return Map.of(SdlcState.TEST_STRATEGY, out.value(),
                SdlcState.DECISIONS, List.of(decision(out, out.value().cases().size() + " test cases covering "
                        + spec.acceptanceCriteria().size() + " acceptance criteria", out.value().approach(), "spec")));
    }

    static boolean covers(TestStrategy ts, RequirementSpec spec) {
        Set<String> covered = new HashSet<>();
        ts.cases().forEach(c -> covered.add(c.covers()));
        return covered.containsAll(spec.acceptanceCriteriaIds());
    }

    /** Deterministic strategy. */
    public static TestStrategy strategy(RequirementSpec spec, ImpactAnalysis impact) {
        List<TestCase> cases = new ArrayList<>();
        int n = 0;
        for (String ac : spec.acceptanceCriteria()) {
            String id = ac.substring(0, ac.indexOf(':'));
            String text = ac.substring(ac.indexOf(':') + 1).trim();
            String lower = text.toLowerCase(Locale.ROOT);
            cases.add(new TestCase("TC-" + (++n), "Unit: " + shorten(text), "unit", id));
            if (lower.matches(".*\\b(get|post|delete|http|\\d{3})\\b.*")) {
                cases.add(new TestCase("TC-" + (++n), "API: " + shorten(text), "integration", id));
            }
            if (lower.matches(".*(unsafe|private|credential|reject|rate|limit|raw client ips).*")) {
                cases.add(new TestCase("TC-" + (++n), "Security/abuse: " + shorten(text), "security", id));
            }
        }
        if (spec.changeType() == ChangeType.BROWNFIELD) {
            for (ImpactAnalysis.ImpactedComponent c : impact.components()) {
                cases.add(new TestCase("TC-" + (++n), "Regression: existing tests for " + c.path(), "regression",
                        "REGRESSION"));
            }
        }
        List<String> levels = List.of("unit (JUnit 5, in-memory store, fixed clock)",
                "integration (Spring MockMvc + H2 + Flyway)", "security (validator abuse cases)",
                spec.changeType() == ChangeType.BROWNFIELD ? "regression (full existing suite)" : "smoke (end-to-end redirect)");
        String approach = "Test pyramid: fast deterministic unit tests on the framework-free domain, a thin layer of "
                + "HTTP integration tests, explicit abuse cases; every acceptance criterion is traced to >= 1 case.";
        return new TestStrategy(cases, levels, approach);
    }

    private static String shorten(String s) {
        return s.length() <= 90 ? s : s.substring(0, 87) + "...";
    }
}
