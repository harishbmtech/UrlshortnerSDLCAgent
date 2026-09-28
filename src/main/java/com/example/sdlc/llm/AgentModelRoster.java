package com.example.sdlc.llm;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which local Ollama model each agent in the multi-agent graph uses.
 * <p>
 * Agents do different kinds of work, so they do not all need the same model. Reasoning-heavy roles
 * (requirements, planning, architecture, risk, docs) get a general instruction-tuned model. Code-heavy roles
 * (codebase analysis, test strategy, implementation) get a code-tuned model. Any agent can be given its own
 * model in {@code application.yml} under {@code sdlc.llm.agent-models}.
 *
 * @param defaultModel model used by any agent without an explicit entry, and the first fallback when an
 *                     agent's own model is not pulled
 * @param agentModels  agent name (the graph node id) to Ollama model tag
 */
public record AgentModelRoster(String defaultModel, Map<String, String> agentModels) {

    /** The LLM-backed agents of the SDLC graph and the job each one does. Control nodes and gates are not listed. */
    public static final Map<String, String> AGENT_ROLES;

    static {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("requirements_analysis", "Interprets intent, flags ambiguity, writes testable acceptance criteria");
        roles.put("planning", "Decomposes the spec into a task DAG with dependencies and waves");
        roles.put("codebase_analysis", "Maps the change onto existing modules, APIs, data flows (brownfield)");
        roles.put("architecture_design", "Produces the design: components, API/schema, key decisions");
        roles.put("risk_assessment", "Rates risk, lists failure scenarios, trade-offs and mitigations");
        roles.put("test_strategy", "Defines unit/integration/regression tests tied to acceptance criteria");
        roles.put("implementation", "Writes production code, migrations and tests");
        roles.put("documentation", "Writes API docs, runbook notes and the change log");
        AGENT_ROLES = java.util.Collections.unmodifiableMap(roles);
    }

    public AgentModelRoster {
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new IllegalArgumentException("sdlc.llm.default-model must be set");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        if (agentModels != null) {
            agentModels.forEach((k, v) -> normalized.put(key(k), v));
        }
        agentModels = Map.copyOf(normalized);
    }

    /**
     * Keys are matched without punctuation, so {@code implementation}, {@code [test_strategy]} and the
     * {@code teststrategy} form that Spring's relaxed map binding produces for unbracketed keys all work.
     */
    private static String key(String agent) {
        return agent.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public String modelFor(String agent) {
        String m = agentModels.get(key(agent));
        return m == null || m.isBlank() ? defaultModel : m;
    }

    /** Every distinct model the roster needs pulled, default first. */
    public Set<String> requiredModels() {
        Set<String> models = new LinkedHashSet<>();
        models.add(defaultModel);
        AGENT_ROLES.keySet().forEach(a -> models.add(modelFor(a)));
        return models;
    }
}
