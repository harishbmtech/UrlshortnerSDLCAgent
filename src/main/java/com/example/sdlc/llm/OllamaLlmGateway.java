package com.example.sdlc.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Spring AI + Ollama adapter for the {@link LlmGateway} port. This is what makes the graph multi-agent on local
 * models.
 * <ul>
 *   <li><b>One model per agent.</b> Each call is routed to the agent's model from the {@link AgentModelRoster}
 *       through per-request {@link OllamaOptions}. A single Spring AI {@link ChatModel} bean serves all agents.</li>
 *   <li><b>Schema-constrained output.</b> The JSON schema of the agent's output record is sent as Ollama's
 *       {@code format}, so the model can only emit JSON of that shape. {@code JSON} mode is the looser option
 *       for older Ollama servers.</li>
 *   <li><b>Model fallback.</b> If an agent's model is not pulled, the default model is used. If that is missing
 *       too, the call fails and the agent's deterministic strategy takes over (see {@code ResilientExecutor}).</li>
 *   <li><b>Liveness.</b> {@link #isAvailable()} follows the {@link OllamaHealthProbe}. When Ollama is down, agents
 *       run deterministically instead of waiting on timeouts.</li>
 *   <li><b>Per-agent stats.</b> Calls, failures and latency, exposed on {@code GET /api/v1/sdlc/agents}.</li>
 * </ul>
 * Messages are plain {@link SystemMessage}/{@link UserMessage} objects, not templates, so braces in code
 * snippets are never read as template variables.
 */
public class OllamaLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmGateway.class);
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");

    public enum StructuredOutput { SCHEMA, JSON }

    public record Settings(boolean enabled, StructuredOutput structuredOutput, Double temperature, Integer numCtx) {
    }

    /** Per-agent call statistics. */
    static final class Stats {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong totalMillis = new AtomicLong();
        volatile String lastModel;
        volatile String lastError;
    }

    private final ChatModel chatModel;
    private final AgentModelRoster roster;
    private final OllamaHealthProbe probe;
    private final Settings settings;
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();

    public OllamaLlmGateway(ChatModel chatModel, AgentModelRoster roster, OllamaHealthProbe probe, Settings settings) {
        this.chatModel = chatModel;
        this.roster = roster;
        this.probe = probe;
        this.settings = settings;
    }

    @Override
    public boolean isAvailable() {
        return settings.enabled() && probe.status().reachable();
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public String modelFor(String agent) {
        return roster.modelFor(agent);
    }

    /**
     * The model that will actually serve this agent: its own if pulled, else the default if pulled.
     *
     * @throws IllegalStateException if neither is pulled (the agent then uses its deterministic fallback)
     */
    String resolveModel(String agent) {
        String preferred = roster.modelFor(agent);
        OllamaHealthProbe.Status status = probe.status();
        if (!status.reachable() || status.installedModels().isEmpty()) {
            return preferred; // unknown inventory: let Ollama answer (it returns 404 for a missing model)
        }
        if (probe.isInstalled(preferred)) {
            return preferred;
        }
        if (probe.isInstalled(roster.defaultModel())) {
            log.warn("Ollama model '{}' for agent '{}' is not pulled; using default model '{}'. Fix: ollama pull {}",
                    preferred, agent, roster.defaultModel(), preferred);
            return roster.defaultModel();
        }
        throw new IllegalStateException("Ollama model '" + preferred + "' for agent '" + agent
                + "' is not pulled (and neither is default '" + roster.defaultModel() + "'). Run: ollama pull " + preferred);
    }

    @Override
    public <T> T generate(String agent, String systemPrompt, String userPrompt, Class<T> type) {
        Stats s = stats.computeIfAbsent(agent, a -> new Stats());
        s.calls.incrementAndGet();
        long start = System.currentTimeMillis();
        try {
            String model = resolveModel(agent);
            s.lastModel = model;
            BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
            Object format = settings.structuredOutput() == StructuredOutput.SCHEMA ? converter.getJsonSchemaMap() : "json";
            OllamaOptions options = OllamaOptions.builder()
                    .model(model)
                    .format(format)
                    .temperature(settings.temperature())
                    .numCtx(settings.numCtx())
                    .build();
            List<Message> messages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt + "\n\n" + converter.getFormat()));
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new IllegalStateException(agent + ": empty response from Ollama model " + model);
            }
            String text = cleanModelOutput(response.getResult().getOutput().getText());
            if (text.isBlank()) {
                throw new IllegalStateException(agent + ": blank response from Ollama model " + model);
            }
            T value = converter.convert(text);
            log.debug("{} answered by ollama:{} in {} ms", agent, model, System.currentTimeMillis() - start);
            return value;
        } catch (RuntimeException e) {
            s.failures.incrementAndGet();
            s.lastError = e.getMessage();
            throw e;
        } finally {
            s.totalMillis.addAndGet(System.currentTimeMillis() - start);
        }
    }

    /**
     * Local models often wrap JSON in extra text. This removes reasoning blocks ({@code <think>} from qwen3 or
     * deepseek-r1), markdown fences and any chatter before or after the JSON object.
     */
    static String cleanModelOutput(String raw) {
        if (raw == null) return "";
        String text = THINK_BLOCK.matcher(raw).replaceAll("").trim();
        // A fence that opens before the JSON wraps the whole answer. Use the LAST closing fence: generated docs
        // and code inside the JSON strings often contain fences of their own.
        int fence = text.indexOf("```");
        int brace = text.indexOf('{');
        int lastFence = text.lastIndexOf("```");
        if (fence >= 0 && (brace < 0 || fence < brace) && lastFence > fence) {
            text = text.substring(fence + 3, lastFence);
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) {
            text = text.substring(open, close + 1);
        }
        return text.trim();
    }

    @Override
    public Map<String, Object> describe() {
        OllamaHealthProbe.Status status = probe.status();
        List<Map<String, Object>> agents = new ArrayList<>();
        AgentModelRoster.AGENT_ROLES.forEach((agent, role) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            String model = roster.modelFor(agent);
            a.put("agent", agent);
            a.put("role", role);
            a.put("model", model);
            a.put("modelPulled", status.reachable() && probe.isInstalled(model));
            Stats s = stats.get(agent);
            long calls = s == null ? 0 : s.calls.get();
            a.put("llmCalls", calls);
            a.put("llmFailures", s == null ? 0 : s.failures.get());
            a.put("avgLatencyMs", calls == 0 ? 0 : s.totalMillis.get() / calls);
            a.put("lastModelUsed", s == null ? null : s.lastModel);
            a.put("lastError", s == null ? null : s.lastError);
            agents.add(a);
        });
        List<String> missing = roster.requiredModels().stream()
                .filter(m -> !status.reachable() || !probe.isInstalled(m)).toList();
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("baseUrl", probe.baseUrl());
        ollama.put("enabled", settings.enabled());
        ollama.put("reachable", status.reachable());
        ollama.put("installedModels", status.installedModels());
        ollama.put("missingModels", missing);
        ollama.put("pullCommands", missing.stream().map(m -> "ollama pull " + m).toList());
        ollama.put("structuredOutput", settings.structuredOutput().name());
        ollama.put("error", status.error());
        ollama.put("checkedAt", String.valueOf(status.checkedAt()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider());
        out.put("mode", isAvailable() ? "LLM (deterministic fallback on failure)" : "DETERMINISTIC (Ollama unavailable or disabled)");
        out.put("ollama", ollama);
        out.put("agents", agents);
        return out;
    }
}
