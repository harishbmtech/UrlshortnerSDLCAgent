package com.example.sdlc.agents;

import com.example.sdlc.governance.HashChainedAuditLog;
import com.example.sdlc.governance.Json;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.Verdict;
import com.example.sdlc.model.HumanDecision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The high-impact action: materialises approved artifacts into the output directory with a manifest
 * (checksums + lineage) and the run's audit trail. Defence in depth: re-checks the preconditions itself
 * instead of trusting the graph routing.
 */
public class PublishNode extends AgentSupport {

    public PublishNode(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) throws IOException {
        HumanDecision approval = state.approvals().stream()
                .filter(a -> a.gate().equals(HumanGate.RELEASE_APPROVAL))
                .reduce((a, b) -> b)
                .filter(a -> a.verdict() == Verdict.APPROVE)
                .orElseThrow(() -> new IllegalStateException("publish without release approval is forbidden"));
        if (state.validation().map(v -> !v.passed()).orElse(true)) {
            throw new IllegalStateException("publish with failing validation is forbidden");
        }

        Path root = ctx.settings().outputDir().resolve(state.runId()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (Artifact a : state.artifacts()) {
            Path target = root.resolve(a.path()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalStateException("path traversal blocked: " + a.path());
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, a.content(), StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", a.path());
            m.put("type", a.type().name());
            m.put("sha256", a.sha256());
            m.put("producedBy", a.producedBy());
            m.put("planVersion", a.planVersion());
            m.put("derivedFrom", a.derivedFrom());
            manifest.add(m);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("runId", state.runId());
        doc.put("approvedBy", approval.approver());
        doc.put("approvedAt", approval.at().toString());
        doc.put("planVersion", state.planVersion());
        doc.put("artifacts", manifest);
        Files.writeString(root.resolve("manifest.json"), Json.write(doc), StandardCharsets.UTF_8);
        String auditLines = ctx.audit().events(state.runId()).stream()
                .map(e -> Json.write(HashChainedAuditLog.toMap(e))).collect(Collectors.joining("\n"));
        try {
            Files.writeString(root.resolve("audit.jsonl"), auditLines + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of(SdlcState.PUBLISHED_TO, root.toString(),
                SdlcState.DECISIONS, List.of(decision("Published " + manifest.size() + " artifacts",
                        "approved by " + approval.approver() + "; manifest with sha256 + lineage written", "release_approval")));
    }
}
