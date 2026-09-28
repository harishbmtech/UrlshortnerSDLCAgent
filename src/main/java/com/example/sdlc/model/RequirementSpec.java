package com.example.sdlc.model;

import com.example.sdlc.model.Enums.ChangeType;

import java.io.Serializable;
import java.util.List;

/**
 * Normalised engineering problem produced by the requirements agent.
 *
 * @param acceptanceCriteria each entry is prefixed with a stable id, e.g. {@code "AC-1: ..."}, used for traceability
 */
public record RequirementSpec(
        String title,
        String summary,
        ChangeType changeType,
        List<String> features,
        List<String> acceptanceCriteria,
        List<String> nonFunctional,
        List<Ambiguity> ambiguities,
        List<String> assumptions) implements Serializable {

    public record Ambiguity(String id, String statement, String question, boolean blocking) implements Serializable {
    }

    public RequirementSpec {
        features = features == null ? List.of() : List.copyOf(features);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        nonFunctional = nonFunctional == null ? List.of() : List.copyOf(nonFunctional);
        ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    public List<Ambiguity> blockingAmbiguities() {
        return ambiguities.stream().filter(Ambiguity::blocking).toList();
    }

    public List<String> acceptanceCriteriaIds() {
        return acceptanceCriteria.stream()
                .map(ac -> ac.contains(":") ? ac.substring(0, ac.indexOf(':')).trim() : ac)
                .toList();
    }
}
