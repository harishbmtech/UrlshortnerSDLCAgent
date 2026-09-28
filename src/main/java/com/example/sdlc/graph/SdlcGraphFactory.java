package com.example.sdlc.graph;

import com.example.sdlc.agents.AgentContext;
import com.example.sdlc.agents.AgentSupport;
import com.example.sdlc.agents.ArchitectureAgent;
import com.example.sdlc.agents.CodebaseAnalysisAgent;
import com.example.sdlc.agents.ControlNodes;
import com.example.sdlc.agents.DesignGate;
import com.example.sdlc.agents.DeveloperAgent;
import com.example.sdlc.agents.DocumentationAgent;
import com.example.sdlc.agents.HumanGate;
import com.example.sdlc.agents.PlanningAgent;
import com.example.sdlc.agents.PublishNode;
import com.example.sdlc.agents.ReleaseReadinessAgent;
import com.example.sdlc.agents.RequirementsAgent;
import com.example.sdlc.agents.RiskAgent;
import com.example.sdlc.agents.SummaryNode;
import com.example.sdlc.agents.TestStrategyAgent;
import com.example.sdlc.agents.ValidationAgent;
import com.example.sdlc.governance.RunControl;
import com.example.sdlc.governance.SdlcSettings;
import com.example.sdlc.model.Enums.Verdict;
import com.example.sdlc.model.HumanDecision;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Builds the SDLC orchestration as an explicit LangGraph4j state graph.
 *
 * <pre>
 * START -> requirements_analysis -?-> clarification_gate (HUMAN) -> requirements_analysis   (re-plan loop)
 *                                 -?-> planning -> codebase_analysis
 *            codebase_analysis ==> { architecture_design | risk_assessment | test_strategy }   (parallel fan-out)
 *            { ... } ==> design_gate                                                          (join / sync barrier)
 *            design_gate -?-> design_approval (HUMAN, only if risk >= threshold) -?-> implementation | planning | safe_stop
 *            implementation -> validation -?-> implementation (bounded retry with feedback)
 *                                         -?-> rollback -> safe_stop -> END
 *                                         -?-> documentation -> release_readiness -?-> release_approval (HUMAN)
 *            release_approval -?-> publish -> summary -> END  |  planning (re-plan)  |  safe_stop
 * </pre>
 * Entry/exit gates: every human gate is an {@code interruptBefore} checkpoint; every loop is bounded.
 */
public final class SdlcGraphFactory {

    public static final String REQUIREMENTS = "requirements_analysis";
    public static final String PLANNING = "planning";
    public static final String CODEBASE = "codebase_analysis";
    public static final String ARCHITECTURE = "architecture_design";
    public static final String RISK = "risk_assessment";
    public static final String TESTS = "test_strategy";
    public static final String DESIGN_GATE = "design_gate";
    public static final String IMPLEMENTATION = "implementation";
    public static final String VALIDATION = "validation";
    public static final String ROLLBACK = "rollback";
    public static final String SAFE_STOP = "safe_stop";
    public static final String DOCUMENTATION = "documentation";
    public static final String READINESS = "release_readiness";
    public static final String PUBLISH = "publish";
    public static final String SUMMARY = "summary";

    private SdlcGraphFactory() {
    }

    public static StateGraph<SdlcState> build(AgentContext ctx, RunControl control) throws GraphStateException {
        SdlcSettings s = ctx.settings();
        StateGraph<SdlcState> g = new StateGraph<>(SdlcState.SCHEMA, SdlcState::new);

        add(g, new RequirementsAgent(ctx), ctx, control);
        add(g, new HumanGate(ctx, HumanGate.CLARIFICATION), ctx, control);
        add(g, new PlanningAgent(ctx), ctx, control);
        add(g, new CodebaseAnalysisAgent(ctx), ctx, control);
        add(g, new ArchitectureAgent(ctx), ctx, control);
        add(g, new RiskAgent(ctx), ctx, control);
        add(g, new TestStrategyAgent(ctx), ctx, control);
        add(g, new DesignGate(ctx), ctx, control);
        add(g, new HumanGate(ctx, HumanGate.DESIGN_APPROVAL), ctx, control);
        add(g, new DeveloperAgent(ctx), ctx, control);
        add(g, new ValidationAgent(ctx), ctx, control);
        add(g, new ControlNodes.Rollback(ctx), ctx, control);
        add(g, new ControlNodes.SafeStop(ctx), ctx, control);
        add(g, new DocumentationAgent(ctx), ctx, control);
        add(g, new ReleaseReadinessAgent(ctx), ctx, control);
        add(g, new HumanGate(ctx, HumanGate.RELEASE_APPROVAL), ctx, control);
        add(g, new PublishNode(ctx), ctx, control);
        add(g, new SummaryNode(ctx), ctx, control);

        g.addEdge(START, REQUIREMENTS);

        g.addConditionalEdges(REQUIREMENTS, edge_async(st -> {
            boolean blocked = st.spec().map(sp -> !sp.blockingAmbiguities().isEmpty()).orElse(true);
            if (!blocked) return "ok";
            return st.replans() >= s.maxReplans() ? "exhausted" : "clarify";
        }), Map.of("ok", PLANNING, "clarify", HumanGate.CLARIFICATION, "exhausted", SAFE_STOP));

        g.addConditionalEdges(HumanGate.CLARIFICATION, edge_async(st ->
                        lastVerdict(st, HumanGate.CLARIFICATION) == Verdict.ABORT ? "abort" : "reanalyse"),
                Map.of("reanalyse", REQUIREMENTS, "abort", SAFE_STOP));

        g.addEdge(PLANNING, CODEBASE);

        // parallel fan-out ...
        g.addEdge(CODEBASE, ARCHITECTURE);
        g.addEdge(CODEBASE, RISK);
        g.addEdge(CODEBASE, TESTS);
        // ... and join (synchronisation barrier)
        g.addEdge(ARCHITECTURE, DESIGN_GATE);
        g.addEdge(RISK, DESIGN_GATE);
        g.addEdge(TESTS, DESIGN_GATE);

        g.addConditionalEdges(DESIGN_GATE, edge_async(st ->
                        st.<Boolean>value(DesignGate.REQUIRES_APPROVAL).orElse(true) ? "human" : "auto"),
                Map.of("human", HumanGate.DESIGN_APPROVAL, "auto", IMPLEMENTATION));

        g.addConditionalEdges(HumanGate.DESIGN_APPROVAL, edge_async(st -> afterReview(st, HumanGate.DESIGN_APPROVAL, s)),
                Map.of("approve", IMPLEMENTATION, "replan", PLANNING, "stop", SAFE_STOP));

        g.addEdge(IMPLEMENTATION, VALIDATION);

        g.addConditionalEdges(VALIDATION, edge_async(st -> {
            boolean passed = st.validation().map(v -> v.passed()).orElse(false);
            if (passed) return "passed";
            return st.implementationAttempts() < s.maxImplementationAttempts() ? "retry" : "rollback";
        }), Map.of("passed", DOCUMENTATION, "retry", IMPLEMENTATION, "rollback", ROLLBACK));

        g.addEdge(ROLLBACK, SAFE_STOP);
        g.addEdge(SAFE_STOP, END);
        g.addEdge(DOCUMENTATION, READINESS);

        g.addConditionalEdges(READINESS, edge_async(st ->
                        st.readiness().map(r -> r.ready()).orElse(false) ? "go" : "nogo"),
                Map.of("go", HumanGate.RELEASE_APPROVAL, "nogo", SAFE_STOP));

        g.addConditionalEdges(HumanGate.RELEASE_APPROVAL, edge_async(st -> afterReview(st, HumanGate.RELEASE_APPROVAL, s)),
                Map.of("approve", PUBLISH, "replan", PLANNING, "stop", SAFE_STOP));

        g.addEdge(PUBLISH, SUMMARY);
        g.addEdge(SUMMARY, END);
        return g;
    }

    public static CompiledGraph<SdlcState> compile(StateGraph<SdlcState> graph, BaseCheckpointSaver saver)
            throws GraphStateException {
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(HumanGate.CLARIFICATION, HumanGate.DESIGN_APPROVAL, HumanGate.RELEASE_APPROVAL)
                .recursionLimit(200)
                .build());
    }

    private static String afterReview(SdlcState st, String gate, SdlcSettings s) {
        Verdict v = lastVerdict(st, gate);
        if (v == Verdict.APPROVE) return "approve";
        if (v == Verdict.REJECT && st.replans() <= s.maxReplans()) return "replan";
        return "stop";
    }

    static Verdict lastVerdict(SdlcState st, String gate) {
        return st.approvals().stream().filter(a -> a.gate().equals(gate)).reduce((a, b) -> b)
                .map(HumanDecision::verdict).orElse(Verdict.ABORT);
    }

    private static void add(StateGraph<SdlcState> g, AgentSupport agent, AgentContext ctx, RunControl control)
            throws GraphStateException {
        String actor = agent.getClass().getSimpleName().endsWith("Gate") && agent instanceof HumanGate
                ? "human-gate" : "agent:" + agent.name();
        g.addNode(agent.name(), node_async(new GovernedNode(agent.name(), actor, agent, ctx.settings(), control,
                ctx.audit(), ctx.metrics())));
    }
}
