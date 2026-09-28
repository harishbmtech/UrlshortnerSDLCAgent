package com.example.sdlc;

import com.example.sdlc.governance.AuditEvent;
import com.example.sdlc.governance.HashChainedAuditLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashChainedAuditLogTest {

    @Test
    void chainVerifiesAndDetectsTampering() throws Exception {
        HashChainedAuditLog log = new HashChainedAuditLog(null);
        log.record("r1", "planning", "agent", "start", "STARTED", "", 0);
        log.record("r1", "planning", "agent", "complete", "SUCCEEDED", "wrote [plan]", 5);
        log.record("r1", "release_approval", "human:tech-lead", "decision", "APPROVE", "LGTM", 0);
        assertTrue(log.verify("r1"));
        assertEquals(3, log.events("r1").size());

        // tamper: rewrite the human decision in place
        Field f = HashChainedAuditLog.class.getDeclaredField("byRun");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<AuditEvent>> byRun = (Map<String, List<AuditEvent>>) f.get(log);
        List<AuditEvent> events = byRun.get("r1");
        AuditEvent e = events.get(2);
        events.set(2, new AuditEvent(e.seq(), e.runId(), e.at(), e.node(), e.actor(), e.action(), "REJECT",
                e.detail(), e.durationMs(), e.prevHash(), e.hash()));

        assertFalse(log.verify("r1"));
    }
}
