package com.example.sdlc.model;

import java.io.Serializable;
import java.util.List;

/**
 * Task DAG. {@code executionWaves} is the topological layering: tasks in the same wave have no
 * dependency on each other and may run in parallel.
 */
public record Plan(int version, List<EngineeringTask> tasks, List<List<String>> executionWaves,
                   String rationale) implements Serializable {

    public Plan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        executionWaves = executionWaves == null ? List.of() : executionWaves.stream().map(List::copyOf).toList();
    }
}
