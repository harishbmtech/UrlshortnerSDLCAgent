package com.example.sdlc.model;

import com.example.sdlc.model.Enums.Severity;

import java.io.Serializable;
import java.util.List;

/**
 * Deterministic validation result. Passing = no BLOCKER and no MAJOR violations.
 */
public record ValidationReport(boolean passed, int attempt, List<PolicyViolation> violations,
                               List<String> rulesEvaluated, int artifactsChecked) implements Serializable {

    public ValidationReport {
        violations = violations == null ? List.of() : List.copyOf(violations);
        rulesEvaluated = rulesEvaluated == null ? List.of() : List.copyOf(rulesEvaluated);
    }

    public long count(Severity s) {
        return violations.stream().filter(v -> v.severity() == s).count();
    }
}
