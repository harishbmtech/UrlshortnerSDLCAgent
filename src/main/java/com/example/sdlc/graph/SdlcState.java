package com.example.sdlc.graph;

import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Decision;
import com.example.sdlc.model.DesignSpec;
import com.example.sdlc.model.HumanDecision;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.Plan;
import com.example.sdlc.model.ReleaseReadiness;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.RiskAssessment;
import com.example.sdlc.model.TestStrategy;
import com.example.sdlc.model.ValidationReport;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared, checkpointed state that flows through the LangGraph.
 * <p>
 * Channel semantics:
 * <ul>
 *   <li>{@code decisions}, {@code approvals}, {@code executionPath}, {@code feedback} are append-only
 *       (reducer channels) - they form the decision lineage and cannot be rewritten by a node.
 *       Entries are unique by construction (timestamps / step numbers), which matters because LangGraph4j's
 *       parallel node re-emits the merged state and the appender de-duplicates.</li>
 *   <li>every other key is last-writer-wins; parallel branches write disjoint keys by design.</li>
 * </ul>
 * All values are {@link java.io.Serializable} because checkpoints are persisted by the saver.
 */
public class SdlcState extends AgentState {

    // inputs
    public static final String RUN_ID = "runId";
    public static final String REQUIREMENT = "requirement";
    public static final String FAULTS = "faults";

    // stage outputs
    public static final String SPEC = "spec";
    public static final String CLARIFICATIONS = "clarifications";
    public static final String PLAN = "plan";
    public static final String PLAN_VERSION = "planVersion";
    public static final String IMPACT = "impact";
    public static final String DESIGN = "design";
    public static final String RISK = "risk";
    public static final String TEST_STRATEGY = "testStrategy";
    public static final String BASELINE_ARTIFACTS = "baselineArtifacts";
    public static final String ARTIFACTS = "artifacts";
    public static final String VALIDATION = "validation";
    public static final String READINESS = "readiness";
    public static final String SUMMARY = "summary";
    public static final String PUBLISHED_TO = "publishedTo";

    // control
    public static final String IMPL_ATTEMPTS = "implementationAttempts";
    public static final String REPLANS = "replans";
    public static final String HUMAN_DECISION = "humanDecision";
    public static final String STATUS = "status";
    public static final String HALT_REASON = "haltReason";
    public static final String LAST_FAILURE_AT = "lastFailureAtMillis";

    // append-only lineage
    public static final String DECISIONS = "decisions";
    public static final String APPROVALS = "approvals";
    public static final String EXECUTION_PATH = "executionPath";
    public static final String FEEDBACK = "feedback";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            DECISIONS, Channels.appender(ArrayList::new),
            APPROVALS, Channels.appender(ArrayList::new),
            EXECUTION_PATH, Channels.appender(ArrayList::new),
            FEEDBACK, Channels.appender(ArrayList::new));

    public SdlcState(Map<String, Object> initData) {
        super(initData);
    }

    public String runId() { return this.<String>value(RUN_ID).orElseThrow(); }
    public String requirement() { return this.<String>value(REQUIREMENT).orElse(""); }

    @SuppressWarnings("unchecked")
    public Map<String, Integer> faults() { return this.<Map<String, Integer>>value(FAULTS).orElse(Map.of()); }

    @SuppressWarnings("unchecked")
    public Map<String, String> clarifications() { return this.<Map<String, String>>value(CLARIFICATIONS).orElse(Map.of()); }

    public Optional<RequirementSpec> spec() { return value(SPEC); }
    public Optional<Plan> plan() { return value(PLAN); }
    public int planVersion() { return this.<Integer>value(PLAN_VERSION).orElse(0); }
    public Optional<ImpactAnalysis> impact() { return value(IMPACT); }
    public Optional<DesignSpec> design() { return value(DESIGN); }
    public Optional<RiskAssessment> risk() { return value(RISK); }
    public Optional<TestStrategy> testStrategy() { return value(TEST_STRATEGY); }
    public List<Artifact> baselineArtifacts() { return this.<List<Artifact>>value(BASELINE_ARTIFACTS).orElse(List.of()); }
    public List<Artifact> artifacts() { return this.<List<Artifact>>value(ARTIFACTS).orElse(List.of()); }
    public Optional<ValidationReport> validation() { return value(VALIDATION); }
    public Optional<ReleaseReadiness> readiness() { return value(READINESS); }
    public Optional<String> summary() { return value(SUMMARY); }
    public Optional<String> publishedTo() { return value(PUBLISHED_TO); }

    public int implementationAttempts() { return this.<Integer>value(IMPL_ATTEMPTS).orElse(0); }
    public int replans() { return this.<Integer>value(REPLANS).orElse(0); }
    public Optional<HumanDecision> humanDecision() { return value(HUMAN_DECISION); }
    public Optional<String> status() { return value(STATUS); }
    public Optional<String> haltReason() { return value(HALT_REASON); }
    public Optional<Long> lastFailureAt() { return value(LAST_FAILURE_AT); }

    public List<Decision> decisions() { return this.<List<Decision>>value(DECISIONS).orElse(List.of()); }
    public List<HumanDecision> approvals() { return this.<List<HumanDecision>>value(APPROVALS).orElse(List.of()); }
    /** Steps as {@code "NN:node"}; parallel branches share the same step number. */
    public List<String> executionPath() { return this.<List<String>>value(EXECUTION_PATH).orElse(List.of()); }

    /** Node names only, in execution order. */
    public List<String> executionNodes() {
        return executionPath().stream().map(s -> s.substring(s.indexOf(':') + 1)).toList();
    }
    public List<String> feedback() { return this.<List<String>>value(FEEDBACK).orElse(List.of()); }
}
