package com.example.sdlc.agents;

import com.example.sdlc.governance.ResilientExecutor;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Decision;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Base class for graph nodes. Encapsulates the "LLM first, deterministic strategy as guard-railed fallback"
 * execution policy so every agent behaves the same way under failure.
 */
public abstract class AgentSupport implements NodeAction<SdlcState> {

    protected final AgentContext ctx;

    protected AgentSupport(AgentContext ctx) {
        this.ctx = ctx;
    }

    public abstract String name();

    protected <T> ResilientExecutor.Outcome<T> run(SdlcState state, Callable<T> llmCall, Predicate<T> guard,
                                                   Supplier<T> deterministic) {
        boolean llm = ctx.llm().isAvailable();
        Callable<T> primary = llm ? llmCall : deterministic::get;
        return ctx.executor().execute(state.runId(), name(), state.faults(), llm, primary, guard, deterministic);
    }

    protected Decision decision(ResilientExecutor.Outcome<?> outcome, String decision, String rationale, String... basedOn) {
        String actor = "agent:" + name() + "/" + outcome.mode().name().toLowerCase()
                + (outcome.mode() == ResilientExecutor.Mode.LLM
                        ? "[" + ctx.llm().provider() + ":" + ctx.llm().modelFor(name()) + "]" : "")
                + (outcome.attempts() > 1 ? " (attempts=" + outcome.attempts() + ")" : "");
        return new Decision(name(), actor, decision, rationale, List.of(basedOn), java.time.Instant.now());
    }

    protected Decision decision(String decision, String rationale, String... basedOn) {
        return new Decision(name(), "governance:" + name(), decision, rationale, List.of(basedOn), java.time.Instant.now());
    }

    protected static String bullet(List<String> items) {
        if (items.isEmpty()) return "- (none)\n";
        StringBuilder sb = new StringBuilder();
        items.forEach(i -> sb.append("- ").append(i).append('\n'));
        return sb.toString();
    }
}
