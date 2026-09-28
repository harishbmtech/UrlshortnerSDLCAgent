package com.example.sdlc.model;

import com.example.sdlc.model.Enums.Verdict;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * A human checkpoint outcome. {@code clarifications} maps ambiguity id -> answer (clarification gate only).
 */
public record HumanDecision(String gate, Verdict verdict, String approver, String comment,
                            Map<String, String> clarifications, Instant at) implements Serializable {

    public HumanDecision {
        clarifications = clarifications == null ? Map.of() : Map.copyOf(clarifications);
        comment = comment == null ? "" : comment;
    }
}
