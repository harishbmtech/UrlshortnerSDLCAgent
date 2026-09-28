package com.example.sdlc.agents.offline;

/**
 * The deterministic patcher could not find its anchor: the codebase drifted from what the template expects.
 * Surfaces as a node failure so a human (or an LLM-backed run) takes over instead of producing a bad edit.
 */
public class PatchConflictException extends RuntimeException {
    public PatchConflictException(String path, String anchor) {
        super("Patch conflict in " + path + ": anchor not found -> " + anchor.lines().findFirst().orElse(anchor));
    }
}
