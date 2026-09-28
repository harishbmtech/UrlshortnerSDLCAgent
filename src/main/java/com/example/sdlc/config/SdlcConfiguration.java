package com.example.sdlc.config;

import com.example.sdlc.agents.AgentContext;
import com.example.sdlc.codebase.CodebaseIndex;
import com.example.sdlc.governance.HashChainedAuditLog;
import com.example.sdlc.governance.ReliabilityMetrics;
import com.example.sdlc.governance.ResilientExecutor;
import com.example.sdlc.governance.RunControl;
import com.example.sdlc.governance.SdlcSettings;
import com.example.sdlc.governance.policy.PolicyEngine;
import com.example.sdlc.graph.SdlcOrchestrator;
import com.example.sdlc.llm.AgentModelRoster;
import com.example.sdlc.llm.LlmGateway;
import com.example.sdlc.llm.OfflineLlmGateway;
import com.example.sdlc.llm.OllamaHealthProbe;
import com.example.sdlc.llm.OllamaLlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the framework-free orchestration core into Spring. The LLM layer is Spring AI's Ollama
 * {@link ChatModel}, shared by all agents, each routed to its own local model. If Ollama is disabled
 * ({@code OLLAMA_ENABLED=false}) or not running, every agent runs its deterministic strategy and the graph
 * still completes.
 */
@Configuration
public class SdlcConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SdlcConfiguration.class);

    @Bean
    public SdlcSettings sdlcSettings(SdlcProperties props) {
        SdlcSettings settings = props.toSettings();
        if (!java.nio.file.Files.isDirectory(settings.sourceRoot())) {
            log.warn("sdlc.source-root '{}' not found relative to {} - brownfield analysis will see an empty codebase. "
                    + "Run from the project root or set SDLC_SOURCE_ROOT.", settings.sourceRoot(),
                    java.nio.file.Path.of("").toAbsolutePath());
        }
        return settings;
    }

    @Bean
    public ReliabilityMetrics reliabilityMetrics() {
        return new ReliabilityMetrics();
    }

    @Bean
    public HashChainedAuditLog auditLog(SdlcSettings settings) {
        return new HashChainedAuditLog(settings.outputDir().resolve("audit.jsonl"));
    }

    @Bean
    public RunControl runControl() {
        return new RunControl();
    }

    @Bean
    public PolicyEngine policyEngine() {
        return PolicyEngine.withDefaults();
    }

    @Bean
    public LlmGateway llmGateway(ObjectProvider<ChatModel> chatModel, SdlcProperties props, Environment env) {
        SdlcProperties.Llm llm = props.llmOrDefaults();
        ChatModel model = chatModel.getIfAvailable();
        if (!llm.enabled() || model == null) {
            log.info("Ollama disabled (OLLAMA_ENABLED=false or spring.ai.model.chat=none): agents use deterministic strategies");
            return new OfflineLlmGateway();
        }
        String baseUrl = env.getProperty("spring.ai.ollama.base-url", "http://localhost:11434");
        AgentModelRoster roster = new AgentModelRoster(llm.defaultModel(), llm.agentModels());
        OllamaHealthProbe probe = new OllamaHealthProbe(baseUrl, llm.healthCacheTtl());
        OllamaLlmGateway gateway = new OllamaLlmGateway(model, roster, probe,
                new OllamaLlmGateway.Settings(true, llm.structuredOutput(), llm.temperature(), llm.numCtx()));
        OllamaHealthProbe.Status status = probe.refresh();
        log.info("Multi-agent Ollama roster ({}):", baseUrl);
        AgentModelRoster.AGENT_ROLES.keySet().forEach(a -> log.info("  {} -> {}", String.format("%-22s", a), roster.modelFor(a)));
        if (status.reachable()) {
            roster.requiredModels().stream().filter(m -> !probe.isInstalled(m))
                    .forEach(m -> log.warn("Model '{}' is not pulled. Run: ollama pull {}", m, m));
        }
        return gateway;
    }

    @Bean
    public ResilientExecutor resilientExecutor(SdlcSettings settings, ReliabilityMetrics metrics, HashChainedAuditLog audit) {
        return new ResilientExecutor(settings, metrics, audit);
    }

    @Bean
    public AgentContext agentContext(LlmGateway llm, ResilientExecutor executor, SdlcSettings settings,
                                     PolicyEngine policy, ReliabilityMetrics metrics, HashChainedAuditLog audit) {
        return new AgentContext(llm, executor, settings,
                () -> CodebaseIndex.scan(settings.sourceRoot(), settings.migrationsRoot()), policy, metrics, audit);
    }

    @Bean(destroyMethod = "close")
    public SdlcOrchestrator sdlcOrchestrator(AgentContext ctx, RunControl control) {
        return new SdlcOrchestrator(ctx, control);
    }
}
