package com.example.sdlc.governance.policy;

import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.Severity;
import com.example.sdlc.model.PolicyViolation;
import com.example.sdlc.model.ValidationReport;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates all guardrails. The engine is deterministic by design: the component that grades the work is
 * never the (probabilistic) component that produced it.
 */
public class PolicyEngine {

    private final List<PolicyRule> rules;

    public PolicyEngine(List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static PolicyEngine withDefaults() {
        return new PolicyEngine(Rules.defaults());
    }

    public ValidationReport evaluate(List<Artifact> artifacts, PolicyContext ctx, int attempt) {
        List<PolicyViolation> violations = new ArrayList<>();
        List<String> evaluated = new ArrayList<>();
        for (PolicyRule rule : rules) {
            evaluated.add(rule.id() + " " + rule.description());
            violations.addAll(rule.evaluate(artifacts, ctx));
        }
        boolean passed = violations.stream()
                .noneMatch(v -> v.severity() == Severity.BLOCKER || v.severity() == Severity.MAJOR);
        return new ValidationReport(passed, attempt, violations, evaluated, artifacts.size());
    }

    public List<PolicyRule> rules() {
        return rules;
    }
}
