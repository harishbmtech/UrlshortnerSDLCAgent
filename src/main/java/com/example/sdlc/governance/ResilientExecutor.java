package com.example.sdlc.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Bounded retry with exponential backoff, output acceptance guard and deterministic fallback for agent calls.
 * <p>
 * Primary strategy = LLM (when configured) else the agent's deterministic strategy.
 * An output that fails the {@code accept} guard (e.g. a plan with a cycle, an impact map naming files that
 * do not exist) is treated exactly like an error: it is never passed downstream.
 * <p>
 * Fault injection (for demonstrating the control loop): {@code faults["transient.<agent>"] = n} makes the
 * first {@code n} primary attempts fail with a simulated transient error.
 */
public class ResilientExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientExecutor.class);

    public enum Mode { LLM, DETERMINISTIC, FALLBACK }

    public record Outcome<T>(T value, Mode mode, int attempts, String note) {
    }

    private final SdlcSettings settings;
    private final ReliabilityMetrics metrics;
    private final HashChainedAuditLog audit;

    public ResilientExecutor(SdlcSettings settings, ReliabilityMetrics metrics, HashChainedAuditLog audit) {
        this.settings = settings;
        this.metrics = metrics;
        this.audit = audit;
    }

    public <T> Outcome<T> execute(String runId, String agent, Map<String, Integer> faults, boolean primaryIsLlm,
                                  Callable<T> primary, Predicate<T> accept, Supplier<T> fallback) {
        int maxAttempts = Math.max(1, settings.nodeRetryMaxAttempts());
        int injected = faults.getOrDefault("transient." + agent, 0);
        String lastError = null;
        long firstFailureAt = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt <= injected) {
                    throw new IllegalStateException("injected transient fault (attempt " + attempt + ")");
                }
                T value = primary.call();
                if (value == null || !accept.test(value)) {
                    throw new IllegalStateException("output rejected by guard");
                }
                if (firstFailureAt > 0) {
                    metrics.recovered(System.currentTimeMillis() - firstFailureAt);
                }
                return new Outcome<>(value, primaryIsLlm ? Mode.LLM : Mode.DETERMINISTIC, attempt,
                        attempt > 1 ? "succeeded after retry" : "ok");
            } catch (Exception e) {
                lastError = e.getMessage();
                if (firstFailureAt == 0) firstFailureAt = System.currentTimeMillis();
                audit.record(runId, agent, "agent:" + agent, "attempt-" + attempt, "FAILED", lastError, 0);
                log.warn("[{}] {} attempt {}/{} failed: {}", runId, agent, attempt, maxAttempts, lastError);
                if (attempt < maxAttempts) {
                    metrics.retry();
                    sleep(settings.nodeRetryBackoff().toMillis() * (1L << (attempt - 1)));
                }
            }
        }
        metrics.fallback();
        T value = fallback.get();
        metrics.recovered(System.currentTimeMillis() - firstFailureAt);
        audit.record(runId, agent, "agent:" + agent, "fallback", "RECOVERED",
                "primary exhausted after " + maxAttempts + " attempts (" + lastError + "); used deterministic fallback", 0);
        return new Outcome<>(value, Mode.FALLBACK, maxAttempts, "fallback after: " + lastError);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SafeStopException("interrupted during backoff");
        }
    }
}
