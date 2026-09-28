package com.example.sdlc.governance;

import java.time.Instant;

/**
 * Tamper-evident audit record. {@code hash = sha256(prevHash + canonical(event))}, so any edit or deletion
 * breaks the chain and is detected by {@link HashChainedAuditLog#verify(String)}.
 */
public record AuditEvent(long seq, String runId, Instant at, String node, String actor, String action,
                         String outcome, String detail, long durationMs, String prevHash, String hash) {

    String canonical() {
        return seq + "|" + runId + "|" + at + "|" + node + "|" + actor + "|" + action + "|" + outcome + "|"
                + detail + "|" + durationMs + "|" + prevHash;
    }
}
