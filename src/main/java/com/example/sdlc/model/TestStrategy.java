package com.example.sdlc.model;

import java.io.Serializable;
import java.util.List;

/**
 * Test plan with explicit traceability: each case names the acceptance criterion id it covers.
 */
public record TestStrategy(List<TestCase> cases, List<String> levels, String approach) implements Serializable {

    public record TestCase(String id, String name, String level, String covers) implements Serializable {
    }

    public TestStrategy {
        cases = cases == null ? List.of() : List.copyOf(cases);
        levels = levels == null ? List.of() : List.copyOf(levels);
    }
}
