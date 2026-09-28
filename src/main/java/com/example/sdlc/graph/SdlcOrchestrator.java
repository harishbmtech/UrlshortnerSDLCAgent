package com.example.sdlc.graph;

import com.example.sdlc.agents.AgentContext;
import com.example.sdlc.agents.HumanGate;
import com.example.sdlc.governance.RunControl;
import com.example.sdlc.governance.SafeStopException;
import com.example.sdlc.model.Enums.RunStatus;
import com.example.sdlc.model.Enums.Verdict;
import com.example.sdlc.model.HumanDecision;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the run lifecycle on top of the compiled LangGraph: start, pause at human gates, inject decisions,
 * resume from checkpoint, kill switch, and status/metrics bookkeeping.
 * <p>
 * Concurrency: one graph thread per run at a time (per-run lock); runs are independent threads of the saver.
 */
public class SdlcOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SdlcOrchestrator.class);

    public record StartRequest(String requirement, Map<String, Integer> faults) {
    }

    public record DecisionRequest(String gate, Verdict verdict, String approver, String comment,
                                  Map<String, String> clarifications) {
    }

    /** Mutable run bookkeeping (not part of the graph state). */
    public static final class RunRecord {
        final String runId;
        final String requirement;
        final Instant createdAt = Instant.now();
        final long startedNanos = System.nanoTime();
        volatile RunStatus status = RunStatus.RUNNING;
        volatile String pendingGate;
        volatile String error;
        volatile long agentNanos;
        volatile Instant updatedAt = Instant.now();

        RunRecord(String runId, String requirement) {
            this.runId = runId;
            this.requirement = requirement;
        }

        public String runId() { return runId; }
        public String requirement() { return requirement; }
        public Instant createdAt() { return createdAt; }
        public Instant updatedAt() { return updatedAt; }
        public RunStatus status() { return status; }
        public String pendingGate() { return pendingGate; }
        public String error() { return error; }
        public long agentMillis() { return agentNanos / 1_000_000; }
    }

    private static final Map<String, RunStatus> GATE_STATUS = Map.of(
            HumanGate.CLARIFICATION, RunStatus.AWAITING_CLARIFICATION,
            HumanGate.DESIGN_APPROVAL, RunStatus.AWAITING_DESIGN_APPROVAL,
            HumanGate.RELEASE_APPROVAL, RunStatus.AWAITING_RELEASE_APPROVAL);

    private final AgentContext ctx;
    private final RunControl control;
    private final CompiledGraph<SdlcState> graph;
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SdlcOrchestrator(AgentContext ctx, RunControl control) {
        this.ctx = ctx;
        this.control = control;
        try {
            this.graph = SdlcGraphFactory.compile(SdlcGraphFactory.build(ctx, control), new MemorySaver());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Invalid SDLC graph definition", e);
        }
    }

    // ------------------------------------------------------------------ commands

    /** Starts a run and executes it synchronously until it pauses at a human gate or terminates. */
    public RunRecord start(StartRequest request) {
        RunRecord run = register(request);
        execute(run, GraphInput.args(initialState(run.runId, request)));
        return run;
    }

    /** Starts a run asynchronously; poll {@link #find(String)}. */
    public RunRecord startAsync(StartRequest request) {
        RunRecord run = register(request);
        executor.submit(() -> execute(run, GraphInput.args(initialState(run.runId, request))));
        return run;
    }

    /** Injects a human decision at the pending gate and resumes the graph from its checkpoint. */
    public RunRecord decide(String runId, DecisionRequest d) {
        RunRecord run = require(runId);
        synchronized (run) {
            if (!run.status.isAwaitingHuman()) {
                throw new IllegalStateException("Run " + runId + " is not waiting for a human (status " + run.status + ")");
            }
            if (!run.pendingGate.equals(d.gate())) {
                throw new IllegalArgumentException("Run is waiting at '" + run.pendingGate + "', not '" + d.gate() + "'");
            }
            if (d.approver() == null || !ctx.settings().approvers().contains(d.approver())) {
                throw new SecurityException("'" + d.approver() + "' is not an authorised approver " + ctx.settings().approvers());
            }
            if (d.verdict() == null) {
                throw new IllegalArgumentException("verdict is required");
            }
            HumanDecision decision = new HumanDecision(d.gate(), d.verdict(), d.approver(), d.comment(),
                    d.clarifications(), Instant.now());
            ctx.audit().record(runId, d.gate(), "human:" + d.approver(), "decision", d.verdict().name(),
                    (d.comment() == null ? "" : d.comment()) + (d.clarifications() == null || d.clarifications().isEmpty()
                            ? "" : " clarifications=" + d.clarifications().keySet()), 0);
            try {
                RunnableConfig cfg = config(runId);
                RunnableConfig updated = graph.updateState(cfg, Map.of(SdlcState.HUMAN_DECISION, decision));
                run.status = RunStatus.RUNNING;
                run.pendingGate = null;
                executeResume(run, updated);
            } catch (Exception e) {
                fail(run, e);
            }
            return run;
        }
    }

    /** Operator kill switch; honoured at the next node boundary. */
    public RunRecord stop(String runId, String reason) {
        RunRecord run = require(runId);
        control.requestStop(runId, reason);
        ctx.audit().record(runId, "operator", "human:operator", "stop-request", "REQUESTED", reason, 0);
        synchronized (run) {
            if (run.status.isAwaitingHuman()) {
                run.status = RunStatus.HALTED;
                run.error = "Stopped while waiting at " + run.pendingGate + ": " + reason;
                run.pendingGate = null;
                ctx.metrics().runHalted();
            }
        }
        return run;
    }

    /** Resumes a FAILED/HALTED (kill switch) run from its last checkpoint - measures recovery time. */
    public RunRecord resume(String runId) {
        RunRecord run = require(runId);
        synchronized (run) {
            if (run.status != RunStatus.FAILED && run.status != RunStatus.HALTED) {
                throw new IllegalStateException("Only FAILED or HALTED runs can be resumed");
            }
            if (state(runId).flatMap(SdlcState::status).isPresent()) {
                throw new IllegalStateException("Run reached a terminal node; start a new run instead");
            }
            control.clear(runId);
            long failedAt = run.updatedAt.toEpochMilli();
            run.status = RunStatus.RUNNING;
            run.error = null;
            executeResume(run, config(runId));
            if (run.status != RunStatus.FAILED) {
                ctx.metrics().recovered(System.currentTimeMillis() - failedAt);
            }
            return run;
        }
    }

    // ------------------------------------------------------------------ queries

    public Optional<RunRecord> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public Collection<RunRecord> list() {
        return runs.values();
    }

    public Optional<SdlcState> state(String runId) {
        if (!runs.containsKey(runId)) return Optional.empty();
        return graph.stateOf(config(runId)).map(StateSnapshot::state);
    }

    public String mermaid() {
        GraphRepresentation r = graph.getGraph(GraphRepresentation.Type.MERMAID, "Agentic SDLC", true);
        return r.content();
    }

    public AgentContext context() {
        return ctx;
    }

    // ------------------------------------------------------------------ internals

    private RunRecord register(StartRequest request) {
        if (request.requirement() == null || request.requirement().isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        if (request.requirement().length() > 8000) {
            throw new IllegalArgumentException("requirement too long (max 8000 chars)");
        }
        String runId = UUID.randomUUID().toString();
        RunRecord run = new RunRecord(runId, request.requirement());
        runs.put(runId, run);
        ctx.metrics().runStarted();
        ctx.audit().record(runId, "orchestrator", "system", "run-start", "STARTED",
                "llm=" + ctx.llm().provider() + (ctx.llm().isAvailable() ? "" : " (unavailable -> deterministic)") + " faults=" + (request.faults() == null ? Map.of() : request.faults()), 0);
        return run;
    }

    private static Map<String, Object> initialState(String runId, StartRequest request) {
        Map<String, Object> init = new HashMap<>();
        init.put(SdlcState.RUN_ID, runId);
        init.put(SdlcState.REQUIREMENT, request.requirement().trim());
        init.put(SdlcState.FAULTS, request.faults() == null ? new HashMap<String, Integer>() : new HashMap<>(request.faults()));
        init.put(SdlcState.PLAN_VERSION, 0);
        init.put(SdlcState.REPLANS, 0);
        init.put(SdlcState.IMPL_ATTEMPTS, 0);
        return init;
    }

    private void executeResume(RunRecord run, RunnableConfig cfg) {
        long t0 = System.nanoTime();
        try {
            for (var ignored : graph.stream(GraphInput.resume(), cfg)) {
                // node outputs are observed through GovernedNode (audit/metrics); nothing to do per step
            }
            settle(run);
        } catch (Exception e) {
            fail(run, e);
        } finally {
            run.agentNanos += System.nanoTime() - t0;
            run.updatedAt = Instant.now();
        }
    }

    private void execute(RunRecord run, GraphInput input) {
        synchronized (run) {
            long t0 = System.nanoTime();
            try {
                for (var ignored : graph.stream(input, config(run.runId))) {
                    // see executeResume
                }
                settle(run);
            } catch (Exception e) {
                fail(run, e);
            } finally {
                run.agentNanos += System.nanoTime() - t0;
                run.updatedAt = Instant.now();
            }
        }
    }

    /** Derives the run status from the checkpoint after the graph returned. */
    private void settle(RunRecord run) {
        StateSnapshot<SdlcState> snap = graph.getState(config(run.runId));
        SdlcState st = snap.state();
        String next = snap.next();
        Optional<String> terminal = st.status();
        if (terminal.isPresent()) {
            run.status = RunStatus.valueOf(terminal.get());
            run.pendingGate = null;
            if (run.status == RunStatus.COMPLETED) {
                long wall = (System.nanoTime() - run.startedNanos) / 1_000_000;
                ctx.metrics().runCompleted(wall, (run.agentNanos + 0) / 1_000_000);
            } else {
                run.error = st.haltReason().orElse(null);
                ctx.metrics().runHalted();
            }
            ctx.audit().record(run.runId, "orchestrator", "system", "run-end", run.status.name(),
                    st.haltReason().orElse("path=" + st.executionPath().size() + " nodes"), 0);
        } else if (next != null && GATE_STATUS.containsKey(next)) {
            run.status = GATE_STATUS.get(next);
            run.pendingGate = next;
            ctx.audit().record(run.runId, next, "system", "interrupt", "AWAITING_HUMAN",
                    "checkpointed before " + next, 0);
        } else {
            throw new IllegalStateException("Graph returned without terminal status or pending gate (next=" + next + ")");
        }
        log.info("[{}] settled: {} {}", run.runId, run.status, run.pendingGate == null ? "" : "at " + run.pendingGate);
    }

    private void fail(RunRecord run, Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        boolean safeStop = root instanceof SafeStopException;
        run.status = safeStop ? RunStatus.HALTED : RunStatus.FAILED;
        run.pendingGate = null;
        run.error = root.getClass().getSimpleName() + ": " + root.getMessage();
        run.updatedAt = Instant.now();
        if (safeStop) ctx.metrics().runHalted();
        else ctx.metrics().runFailed();
        ctx.audit().record(run.runId, "orchestrator", "system", "run-end", run.status.name(), run.error, 0);
        log.warn("[{}] run {}: {}", run.runId, run.status, run.error);
    }

    private RunRecord require(String runId) {
        RunRecord r = runs.get(runId);
        if (r == null) throw new java.util.NoSuchElementException("Unknown run " + runId);
        return r;
    }

    private static RunnableConfig config(String runId) {
        return RunnableConfig.builder().threadId(runId).build();
    }

    public List<String> gates() {
        return List.of(HumanGate.CLARIFICATION, HumanGate.DESIGN_APPROVAL, HumanGate.RELEASE_APPROVAL);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
