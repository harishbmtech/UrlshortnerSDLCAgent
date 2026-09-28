package com.example.sdlc.graph;

import com.example.sdlc.governance.HashChainedAuditLog;
import com.example.sdlc.governance.ReliabilityMetrics;
import com.example.sdlc.governance.RunControl;
import com.example.sdlc.governance.SafeStopException;
import com.example.sdlc.governance.SdlcSettings;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cross-cutting governance applied uniformly to every node (decorator):
 * kill switch, autonomy budget, audit (start/finish/failure with hash chain), latency metrics and
 * execution-path lineage.
 */
public class GovernedNode implements NodeAction<SdlcState> {

    private static final Logger log = LoggerFactory.getLogger(GovernedNode.class);

    private final String nodeId;
    private final String actor;
    private final NodeAction<SdlcState> delegate;
    private final SdlcSettings settings;
    private final RunControl control;
    private final HashChainedAuditLog audit;
    private final ReliabilityMetrics metrics;

    public GovernedNode(String nodeId, String actor, NodeAction<SdlcState> delegate, SdlcSettings settings,
                        RunControl control, HashChainedAuditLog audit, ReliabilityMetrics metrics) {
        this.nodeId = nodeId;
        this.actor = actor;
        this.delegate = delegate;
        this.settings = settings;
        this.control = control;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SdlcState state) throws Exception {
        String runId = state.runId();
        control.checkpoint(runId);
        if (state.executionPath().size() >= settings.maxNodeExecutions()) {
            audit.record(runId, nodeId, "governance", "autonomy-budget", "HALTED",
                    "max node executions " + settings.maxNodeExecutions() + " reached", 0);
            throw new SafeStopException("Autonomy budget exhausted (" + settings.maxNodeExecutions() + " node executions)");
        }
        audit.record(runId, nodeId, actor, "start", "STARTED", "plan v" + state.planVersion(), 0);
        long t0 = System.nanoTime();
        try {
            Map<String, Object> out = new HashMap<>(delegate.apply(state));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            metrics.nodeExecuted(nodeId, ms, true);
            out.put(SdlcState.EXECUTION_PATH, List.of(String.format("%02d:%s", state.executionPath().size() + 1, nodeId)));
            audit.record(runId, nodeId, actor, "complete", "SUCCEEDED", "wrote " + new TreeSet<>(out.keySet()), ms);
            log.info("[{}] {} completed in {} ms", runId, nodeId, ms);
            return out;
        } catch (SafeStopException e) {
            audit.record(runId, nodeId, actor, "complete", "HALTED", e.getMessage(), (System.nanoTime() - t0) / 1_000_000);
            throw e;
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            metrics.nodeExecuted(nodeId, ms, false);
            audit.record(runId, nodeId, actor, "complete", "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage(), ms);
            log.warn("[{}] {} failed: {}", runId, nodeId, e.toString());
            throw e;
        }
    }
}
