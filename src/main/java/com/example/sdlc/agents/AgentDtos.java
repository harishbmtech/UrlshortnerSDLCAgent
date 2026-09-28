package com.example.sdlc.agents;

import com.example.sdlc.model.Enums.ArtifactType;

import java.util.List;

/**
 * Structured-output contracts for LLM calls (mapped by Spring AI's BeanOutputConverter).
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    public record GeneratedFile(String path, ArtifactType type, String content) {
    }

    public record GeneratedCode(List<GeneratedFile> files, String notes) {
        public GeneratedCode {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public record PlanDraft(List<TaskDraft> tasks, String rationale) {
        public PlanDraft {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    public record TaskDraft(String id, String title, String stage, List<String> dependsOn, String impact,
                            String rationale) {
    }

    public record DocsBundle(List<GeneratedFile> documents) {
        public DocsBundle {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
