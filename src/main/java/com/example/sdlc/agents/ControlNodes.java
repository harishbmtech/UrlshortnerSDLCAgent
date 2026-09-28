package com.example.sdlc.agents;

import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Enums.RunStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Control-flow nodes: rollback and safe-stop.
 */
public final class ControlNodes {

    private ControlNodes() {
    }

    /** Restores the artifact set to the frozen, approved design baseline (discarding failed code). */
    public static class Rollback extends AgentSupport {
        public Rollback(AgentContext ctx) {
            super(ctx);
        }

        @Override
        public String name() {
            return "rollback";
        }

        @Override
        public Map<String, Object> apply(SdlcState state) {
            ctx.metrics().rollback();
            int discarded = state.artifacts().size() - state.baselineArtifacts().size();
            return Map.of(
                    SdlcState.ARTIFACTS, state.baselineArtifacts(),
                    SdlcState.HALT_REASON, "Validation still failing after " + state.implementationAttempts()
                            + " bounded attempts; rolled back to the approved design baseline",
                    SdlcState.DECISIONS, List.of(decision("Rolled back " + discarded + " generated artifacts",
                            "retry budget exhausted (max " + ctx.settings().maxImplementationAttempts() + ")",
                            "validation")));
        }
    }

    /** Terminal node for every non-successful path: marks the run HALTED with a reason and a partial summary. */
    public static class SafeStop extends AgentSupport {
        public SafeStop(AgentContext ctx) {
            super(ctx);
        }

        @Override
        public String name() {
            return "safe_stop";
        }

        @Override
        public Map<String, Object> apply(SdlcState state) {
            String reason = state.haltReason().orElseGet(() -> state.approvals().isEmpty()
                    ? "Stopped by governance"
                    : "Stopped at human checkpoint: " + state.approvals().get(state.approvals().size() - 1).verdict());
            Map<String, Object> out = new HashMap<>();
            out.put(SdlcState.STATUS, RunStatus.HALTED.name());
            out.put(SdlcState.HALT_REASON, reason);
            out.put(SdlcState.DECISIONS, List.of(decision("Safe stop", reason, "governance")));
            out.put(SdlcState.SUMMARY, SummaryWriter.write(state, RunStatus.HALTED, reason, null));
            return out;
        }
    }
}
