package com.example.sdlc.agents;

import com.example.sdlc.codebase.CodebaseIndex;
import com.example.sdlc.governance.HashChainedAuditLog;
import com.example.sdlc.governance.ReliabilityMetrics;
import com.example.sdlc.governance.ResilientExecutor;
import com.example.sdlc.governance.SdlcSettings;
import com.example.sdlc.governance.policy.PolicyEngine;
import com.example.sdlc.llm.LlmGateway;

import java.util.function.Supplier;

/**
 * Collaborators shared by all agents (constructor-injected; no static state).
 */
public record AgentContext(LlmGateway llm, ResilientExecutor executor, SdlcSettings settings,
                           Supplier<CodebaseIndex> codebase, PolicyEngine policy, ReliabilityMetrics metrics,
                           HashChainedAuditLog audit) {
}
