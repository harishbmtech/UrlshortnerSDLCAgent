package com.example.sdlc.model;

import java.io.Serializable;
import java.util.List;

/**
 * Brownfield codebase reasoning output: what the change touches and how far it ripples.
 */
public record ImpactAnalysis(
        boolean applicable,
        List<ImpactedComponent> components,
        List<String> impactedApis,
        List<String> dataFlows,
        List<String> schemaChanges,
        List<String> existingCapabilities,
        int blastRadius,
        String summary) implements Serializable {

    public record ImpactedComponent(String path, String layer, String reason) implements Serializable {
    }

    public ImpactAnalysis {
        components = components == null ? List.of() : List.copyOf(components);
        impactedApis = impactedApis == null ? List.of() : List.copyOf(impactedApis);
        dataFlows = dataFlows == null ? List.of() : List.copyOf(dataFlows);
        schemaChanges = schemaChanges == null ? List.of() : List.copyOf(schemaChanges);
        existingCapabilities = existingCapabilities == null ? List.of() : List.copyOf(existingCapabilities);
    }

    public static ImpactAnalysis notApplicable(String why) {
        return new ImpactAnalysis(false, List.of(), List.of(), List.of(), List.of(), List.of(), 0, why);
    }
}
