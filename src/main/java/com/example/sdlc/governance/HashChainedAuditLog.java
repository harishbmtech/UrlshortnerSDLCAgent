package com.example.sdlc.governance;

import com.example.sdlc.model.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only, hash-chained audit trail per run. Kept outside the graph state so that no agent can
 * rewrite it; optionally mirrored to a JSONL file for durable, grep-able evidence.
 */
public class HashChainedAuditLog {

    private static final Logger log = LoggerFactory.getLogger(HashChainedAuditLog.class);
    private static final String GENESIS = "0".repeat(64);

    private final Map<String, List<AuditEvent>> byRun = new ConcurrentHashMap<>();
    private final Path jsonlFile;

    public HashChainedAuditLog(Path jsonlFile) {
        this.jsonlFile = jsonlFile;
    }

    public AuditEvent record(String runId, String node, String actor, String action, String outcome,
                             String detail, long durationMs) {
        List<AuditEvent> events = byRun.computeIfAbsent(runId, k -> new ArrayList<>());
        AuditEvent event;
        synchronized (events) {
            String prev = events.isEmpty() ? GENESIS : events.get(events.size() - 1).hash();
            AuditEvent draft = new AuditEvent(events.size() + 1L, runId, Instant.now(), node, actor, action,
                    outcome, detail == null ? "" : detail, durationMs, prev, "");
            event = new AuditEvent(draft.seq(), runId, draft.at(), node, actor, action, outcome, draft.detail(),
                    durationMs, prev, Artifact.sha256(draft.canonical()));
            events.add(event);
        }
        mirror(event);
        return event;
    }

    public List<AuditEvent> events(String runId) {
        List<AuditEvent> events = byRun.getOrDefault(runId, List.of());
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** @return true when the chain for the run is intact */
    public boolean verify(String runId) {
        String prev = GENESIS;
        for (AuditEvent e : events(runId)) {
            if (!e.prevHash().equals(prev)) return false;
            if (!Artifact.sha256(e.canonical()).equals(e.hash())) return false;
            prev = e.hash();
        }
        return true;
    }

    public static Map<String, Object> toMap(AuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("runId", e.runId());
        m.put("at", e.at().toString());
        m.put("node", e.node());
        m.put("actor", e.actor());
        m.put("action", e.action());
        m.put("outcome", e.outcome());
        m.put("detail", e.detail());
        m.put("durationMs", e.durationMs());
        m.put("prevHash", e.prevHash());
        m.put("hash", e.hash());
        return m;
    }

    private void mirror(AuditEvent e) {
        if (jsonlFile == null) return;
        try {
            Files.createDirectories(jsonlFile.toAbsolutePath().getParent());
            synchronized (this) {
                Files.writeString(jsonlFile, Json.write(toMap(e)) + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ex) {
            // Audit mirroring failure must be visible but must not corrupt the in-memory chain.
            log.error("Failed to mirror audit event {} of run {}", e.seq(), e.runId(), ex);
        }
    }
}
