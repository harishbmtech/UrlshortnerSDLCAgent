package com.example.sdlc.llm;

/**
 * Null-object gateway used when Ollama is disabled (OLLAMA_ENABLED=false): every agent falls back to
 * its deterministic strategy, so the full SDLC graph still runs end-to-end without network or API keys.
 */
public class OfflineLlmGateway implements LlmGateway {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String provider() {
        return "offline";
    }

    @Override
    public <T> T generate(String agent, String systemPrompt, String userPrompt, Class<T> type) {
        throw new IllegalStateException("No LLM configured");
    }
}
