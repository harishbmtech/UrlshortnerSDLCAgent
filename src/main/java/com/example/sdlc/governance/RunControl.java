package com.example.sdlc.governance;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator kill switch. A stop request is honoured at the next node boundary (cooperative, never mid-write).
 */
public class RunControl {

    private final Map<String, String> stopRequests = new ConcurrentHashMap<>();

    public void requestStop(String runId, String reason) {
        stopRequests.put(runId, reason == null ? "stopped by operator" : reason);
    }

    public void clear(String runId) {
        stopRequests.remove(runId);
    }

    public Optional<String> stopRequested(String runId) {
        return Optional.ofNullable(stopRequests.get(runId));
    }

    public void checkpoint(String runId) {
        stopRequested(runId).ifPresent(reason -> {
            throw new SafeStopException("Safe-stop requested: " + reason);
        });
    }
}
