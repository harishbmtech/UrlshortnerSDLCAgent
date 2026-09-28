package com.example.sdlc;

import com.example.sdlc.agents.AgentContext;
import com.example.sdlc.codebase.CodebaseIndex;
import com.example.sdlc.governance.HashChainedAuditLog;
import com.example.sdlc.governance.ReliabilityMetrics;
import com.example.sdlc.governance.ResilientExecutor;
import com.example.sdlc.governance.RunControl;
import com.example.sdlc.governance.SdlcSettings;
import com.example.sdlc.governance.policy.PolicyEngine;
import com.example.sdlc.graph.SdlcOrchestrator;
import com.example.sdlc.llm.LlmGateway;
import com.example.sdlc.llm.OfflineLlmGateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Builds a fully wired orchestrator without Spring (offline LLM, temp output dir). */
public final class TestOrchestrators {

    private TestOrchestrators() {
    }

    public static SdlcOrchestrator create() {
        return create(new OfflineLlmGateway());
    }

    public static SdlcOrchestrator create(LlmGateway llm) {
        try {
            Path out = Files.createTempDirectory("sdlc-test");
            SdlcSettings d = SdlcSettings.defaults();
            SdlcSettings settings = new SdlcSettings(d.maxImplementationAttempts(), d.maxReplans(), d.maxNodeExecutions(),
                    2, Duration.ofMillis(1), d.designApprovalThreshold(), d.sourceRoot(), d.migrationsRoot(), out,
                    d.approvers());
            ReliabilityMetrics metrics = new ReliabilityMetrics();
            HashChainedAuditLog audit = new HashChainedAuditLog(out.resolve("audit.jsonl"));
            AgentContext ctx = new AgentContext(llm, new ResilientExecutor(settings, metrics, audit), settings,
                    () -> CodebaseIndex.scan(settings.sourceRoot(), settings.migrationsRoot()),
                    PolicyEngine.withDefaults(), metrics, audit);
            return new SdlcOrchestrator(ctx, new RunControl());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
