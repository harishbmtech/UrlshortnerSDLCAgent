package com.example.sdlc.governance.policy;

import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.PolicyViolation;

import java.util.List;

/**
 * A deterministic guardrail. Rules are pure functions so they are trivially unit-testable and auditable;
 * agents (LLM or not) can never waive them.
 */
public interface PolicyRule {

    String id();

    String description();

    List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx);
}
