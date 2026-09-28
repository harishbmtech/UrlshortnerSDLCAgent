package com.example.sdlc.agents;

import com.example.sdlc.model.EngineeringTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a task graph (unique ids, known dependencies, acyclic) and computes execution waves
 * (Kahn's algorithm, level by level). Used as the acceptance guard for LLM-produced plans.
 */
public final class PlanValidator {

    private PlanValidator() {
    }

    public record Result(boolean valid, List<List<String>> waves, List<String> errors) {
    }

    public static Result validate(List<EngineeringTask> tasks) {
        List<String> errors = new ArrayList<>();
        Map<String, EngineeringTask> byId = new LinkedHashMap<>();
        for (EngineeringTask t : tasks) {
            if (t.id() == null || t.id().isBlank()) errors.add("task without id: " + t.title());
            else if (byId.putIfAbsent(t.id(), t) != null) errors.add("duplicate task id " + t.id());
        }
        for (EngineeringTask t : tasks) {
            for (String d : t.dependsOn()) {
                if (!byId.containsKey(d)) errors.add(t.id() + " depends on unknown task " + d);
                if (d.equals(t.id())) errors.add(t.id() + " depends on itself");
            }
        }
        if (tasks.isEmpty()) errors.add("plan has no tasks");
        if (!errors.isEmpty()) return new Result(false, List.of(), errors);

        List<List<String>> waves = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (done.size() < byId.size()) {
            List<String> wave = byId.keySet().stream()
                    .filter(id -> !done.contains(id))
                    .filter(id -> done.containsAll(byId.get(id).dependsOn()))
                    .toList();
            if (wave.isEmpty()) {
                List<String> cyclic = byId.keySet().stream().filter(id -> !done.contains(id)).toList();
                return new Result(false, List.of(), List.of("dependency cycle among " + cyclic));
            }
            waves.add(wave);
            done.addAll(wave);
        }
        return new Result(true, waves, List.of());
    }
}
