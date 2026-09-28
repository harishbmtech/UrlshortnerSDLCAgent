package com.example.sdlc.governance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reliability metrics of the orchestration itself: success rate, retry / fallback / rollback frequency,
 * MTTR and end-to-end latency. In-process and dependency-free; a Micrometer binder can export the snapshot.
 */
public class ReliabilityMetrics {

    private final AtomicLong runsStarted = new AtomicLong();
    private final AtomicLong runsCompleted = new AtomicLong();
    private final AtomicLong runsHalted = new AtomicLong();
    private final AtomicLong runsFailed = new AtomicLong();
    private final AtomicLong nodeExecutions = new AtomicLong();
    private final AtomicLong nodeFailures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();
    private final AtomicLong rollbacks = new AtomicLong();
    private final AtomicLong validationFailures = new AtomicLong();
    private final AtomicLong replans = new AtomicLong();
    private final AtomicLong humanApprovals = new AtomicLong();
    private final AtomicLong humanRejections = new AtomicLong();
    private final List<Long> recoveryMillis = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> endToEndMillis = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> agentProcessingMillis = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, NodeStat> nodeStats = new ConcurrentHashMap<>();

    public void runStarted() { runsStarted.incrementAndGet(); }
    public void runCompleted(long wallClockMs, long agentMs) {
        runsCompleted.incrementAndGet();
        endToEndMillis.add(wallClockMs);
        agentProcessingMillis.add(agentMs);
    }
    public void runHalted() { runsHalted.incrementAndGet(); }
    public void runFailed() { runsFailed.incrementAndGet(); }
    public void retry() { retries.incrementAndGet(); }
    public void fallback() { fallbacks.incrementAndGet(); }
    public void rollback() { rollbacks.incrementAndGet(); }
    public void validationFailed() { validationFailures.incrementAndGet(); }
    public void replanned() { replans.incrementAndGet(); }
    public void humanApproved() { humanApprovals.incrementAndGet(); }
    public void humanRejected() { humanRejections.incrementAndGet(); }
    public void recovered(long millisSinceFailure) { recoveryMillis.add(millisSinceFailure); }

    public void nodeExecuted(String node, long durationMs, boolean success) {
        nodeExecutions.incrementAndGet();
        if (!success) nodeFailures.incrementAndGet();
        nodeStats.computeIfAbsent(node, k -> new NodeStat()).record(durationMs, success);
    }

    public Snapshot snapshot() {
        long completed = runsCompleted.get();
        long finished = completed + runsHalted.get() + runsFailed.get();
        Map<String, Map<String, Object>> nodes = new TreeMap<>();
        nodeStats.forEach((k, v) -> nodes.put(k, v.toMap()));
        return new Snapshot(runsStarted.get(), completed, runsHalted.get(), runsFailed.get(),
                finished == 0 ? 0.0 : round((double) completed / finished),
                nodeExecutions.get(), nodeFailures.get(), retries.get(), fallbacks.get(), rollbacks.get(),
                validationFailures.get(), replans.get(), humanApprovals.get(), humanRejections.get(),
                avg(recoveryMillis), avg(endToEndMillis), p95(endToEndMillis), avg(agentProcessingMillis), nodes);
    }

    public record Snapshot(long runsStarted, long runsCompleted, long runsHalted, long runsFailed,
                           double successRate, long nodeExecutions, long nodeFailures, long retries,
                           long fallbacks, long rollbacks, long validationFailures, long replans,
                           long humanApprovals, long humanRejections, double mttrMs,
                           double avgEndToEndLatencyMs, double p95EndToEndLatencyMs,
                           double avgAgentProcessingMs, Map<String, Map<String, Object>> perNode) {
    }

    private static double avg(List<Long> values) {
        synchronized (values) {
            return values.isEmpty() ? 0.0 : round(values.stream().mapToLong(Long::longValue).average().orElse(0));
        }
    }

    private static double p95(List<Long> values) {
        synchronized (values) {
            if (values.isEmpty()) return 0.0;
            List<Long> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return sorted.get(Math.max(0, idx));
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static final class NodeStat {
        private long count;
        private long failures;
        private long totalMs;
        private long maxMs;

        synchronized void record(long ms, boolean success) {
            count++;
            if (!success) failures++;
            totalMs += ms;
            maxMs = Math.max(maxMs, ms);
        }

        synchronized Map<String, Object> toMap() {
            return Map.of("executions", count, "failures", failures,
                    "avgMs", count == 0 ? 0.0 : round((double) totalMs / count), "maxMs", maxMs);
        }
    }
}
