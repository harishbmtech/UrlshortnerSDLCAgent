package com.example.sdlc.config;

import com.example.sdlc.governance.SdlcSettings;
import com.example.sdlc.llm.OllamaLlmGateway;
import com.example.sdlc.model.Enums.RiskLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Binds {@code sdlc.*} from application.yml.
 */
@ConfigurationProperties(prefix = "sdlc")
public record SdlcProperties(
        Integer maxImplementationAttempts,
        Integer maxReplans,
        Integer maxNodeExecutions,
        NodeRetry nodeRetry,
        RiskLevel designApprovalRiskThreshold,
        String sourceRoot,
        String migrationsRoot,
        String outputDir,
        Set<String> approvers,
        Llm llm) {

    public record NodeRetry(Integer maxAttempts, Duration backoff) {
    }

    /**
     * Ollama multi-agent settings ({@code sdlc.llm.*}).
     *
     * @param enabled          false forces deterministic mode even if Ollama is running
     * @param defaultModel     model for agents without an entry in {@code agentModels}
     * @param agentModels      agent (graph node id) to Ollama model tag
     * @param structuredOutput SCHEMA sends the output record's JSON schema as Ollama {@code format}; JSON sends "json"
     * @param healthCacheTtl   how long an Ollama liveness check is reused
     */
    public record Llm(Boolean enabled, String defaultModel, Map<String, String> agentModels,
                      OllamaLlmGateway.StructuredOutput structuredOutput, Double temperature, Integer numCtx,
                      Duration healthCacheTtl) {
    }

    public Llm llmOrDefaults() {
        Llm l = llm == null ? new Llm(null, null, null, null, null, null, null) : llm;
        return new Llm(
                l.enabled() == null || l.enabled(),
                l.defaultModel() == null || l.defaultModel().isBlank() ? "llama3.1:8b" : l.defaultModel(),
                l.agentModels() == null ? Map.of() : l.agentModels(),
                l.structuredOutput() == null ? OllamaLlmGateway.StructuredOutput.SCHEMA : l.structuredOutput(),
                l.temperature() == null ? 0.2 : l.temperature(),
                l.numCtx() == null ? 16384 : l.numCtx(),
                l.healthCacheTtl() == null ? Duration.ofSeconds(15) : l.healthCacheTtl());
    }

    public SdlcSettings toSettings() {
        SdlcSettings d = SdlcSettings.defaults();
        return new SdlcSettings(
                maxImplementationAttempts == null ? d.maxImplementationAttempts() : maxImplementationAttempts,
                maxReplans == null ? d.maxReplans() : maxReplans,
                maxNodeExecutions == null ? d.maxNodeExecutions() : maxNodeExecutions,
                nodeRetry == null || nodeRetry.maxAttempts() == null ? d.nodeRetryMaxAttempts() : nodeRetry.maxAttempts(),
                nodeRetry == null || nodeRetry.backoff() == null ? d.nodeRetryBackoff() : nodeRetry.backoff(),
                designApprovalRiskThreshold == null ? d.designApprovalThreshold() : designApprovalRiskThreshold,
                sourceRoot == null ? d.sourceRoot() : Path.of(sourceRoot),
                migrationsRoot == null ? d.migrationsRoot() : Path.of(migrationsRoot),
                outputDir == null ? d.outputDir() : Path.of(outputDir),
                approvers == null || approvers.isEmpty() ? d.approvers() : Set.copyOf(approvers));
    }
}
