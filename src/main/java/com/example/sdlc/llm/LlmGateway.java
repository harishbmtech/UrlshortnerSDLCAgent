package com.example.sdlc.llm;

import java.util.Map;

/**
 * Port to a large language model. Agents depend on this interface only, so the orchestration core is
 * framework-agnostic and testable. {@code OllamaLlmGateway} is the production adapter (Spring AI + Ollama).
 */
public interface LlmGateway {

    /** @return false when no model can be used right now; agents then use their deterministic strategies */
    boolean isAvailable();

    String provider();

    /** The model that serves this agent (for lineage and audit). */
    default String modelFor(String agent) {
        return provider();
    }

    /**
     * Calls the model and maps the answer to {@code type} (structured output).
     *
     * @param agent the calling agent; selects the model and is used for tracing
     */
    <T> T generate(String agent, String systemPrompt, String userPrompt, Class<T> type);

    /** Status of the provider and the per-agent model assignment, for the API. */
    default Map<String, Object> describe() {
        return Map.of("provider", provider(), "available", isAvailable());
    }
}
