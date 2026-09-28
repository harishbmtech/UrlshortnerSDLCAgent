package com.example.sdlc.model;

/**
 * Shared enumerations of the SDLC domain. Grouped in one file because they are tiny and change together.
 */
public final class Enums {

    private Enums() {
    }

    public enum ChangeType { GREENFIELD, BROWNFIELD }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL;

        public boolean atLeast(RiskLevel other) {
            return this.ordinal() >= other.ordinal();
        }

        public static RiskLevel max(RiskLevel a, RiskLevel b) {
            return a.ordinal() >= b.ordinal() ? a : b;
        }
    }

    public enum Stage { ANALYSIS, DESIGN, IMPLEMENT, TEST, DOCS, RELEASE }

    public enum ArtifactType { CODE, TEST, MIGRATION, API_SPEC, SCHEMA, ADR, DOC }

    public enum Severity { BLOCKER, MAJOR, MINOR }

    public enum Verdict { APPROVE, REJECT, ABORT }

    public enum RunStatus {
        RUNNING,
        AWAITING_CLARIFICATION,
        AWAITING_DESIGN_APPROVAL,
        AWAITING_RELEASE_APPROVAL,
        COMPLETED,
        HALTED,
        FAILED;

        public boolean isTerminal() {
            return this == COMPLETED || this == HALTED;
        }

        public boolean isAwaitingHuman() {
            return name().startsWith("AWAITING_");
        }
    }
}
