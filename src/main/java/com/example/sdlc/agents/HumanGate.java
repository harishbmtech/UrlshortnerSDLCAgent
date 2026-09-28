package com.example.sdlc.agents;

import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.HumanDecision;
import com.example.sdlc.model.Enums.Verdict;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human-in-the-loop checkpoint. The graph is compiled with {@code interruptBefore} on every gate, so execution
 * pauses (state checkpointed) until a human decision is injected via {@code updateState} and the run resumed.
 * This node only records and validates the decision; routing happens on the conditional edge.
 */
public class HumanGate extends AgentSupport {

    public static final String CLARIFICATION = "clarification_gate";
    public static final String DESIGN_APPROVAL = "design_approval";
    public static final String RELEASE_APPROVAL = "release_approval";

    private final String gate;

    public HumanGate(AgentContext ctx, String gate) {
        super(ctx);
        this.gate = gate;
    }

    @Override
    public String name() {
        return gate;
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        HumanDecision d = state.humanDecision()
                .filter(h -> h.gate().equals(gate))
                .orElseThrow(() -> new IllegalStateException("Gate " + gate + " resumed without a human decision"));
        if (!ctx.settings().approvers().contains(d.approver())) {
            throw new IllegalStateException("Approver '" + d.approver() + "' is not authorised for " + gate);
        }
        Map<String, Object> out = new HashMap<>();
        out.put(SdlcState.APPROVALS, List.of(d));
        // consume the decision so it can never be replayed at a later visit of this gate
        out.put(SdlcState.HUMAN_DECISION, AgentState.MARK_FOR_REMOVAL);
        if (d.verdict() == Verdict.APPROVE) ctx.metrics().humanApproved();
        else ctx.metrics().humanRejected();

        if (gate.equals(CLARIFICATION) && d.verdict() != Verdict.ABORT) {
            Map<String, String> merged = new LinkedHashMap<>(state.clarifications());
            merged.putAll(d.clarifications());
            out.put(SdlcState.CLARIFICATIONS, merged);
            if (d.clarifications().isEmpty()) {
                out.put(RequirementsAgent.PROCEED_ON_ASSUMPTIONS, true);
            }
            out.put(SdlcState.REPLANS, state.replans() + 1);
            ctx.metrics().replanned();
        }
        if (d.verdict() == Verdict.REJECT && !gate.equals(CLARIFICATION)) {
            out.put(SdlcState.FEEDBACK, List.of("review: [" + gate + " by " + d.approver() + "] " + d.comment()));
            out.put(SdlcState.REPLANS, state.replans() + 1);
            ctx.metrics().replanned();
        }
        out.put(SdlcState.DECISIONS, List.of(new com.example.sdlc.model.Decision(gate, "human:" + d.approver(),
                d.verdict().name(), d.comment().isBlank() ? "(no comment)" : d.comment(),
                List.of(gate), d.at())));
        return out;
    }
}
