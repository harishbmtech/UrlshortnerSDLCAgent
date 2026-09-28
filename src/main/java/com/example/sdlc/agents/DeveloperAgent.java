package com.example.sdlc.agents;

import com.example.sdlc.agents.AgentDtos.GeneratedCode;
import com.example.sdlc.agents.AgentDtos.GeneratedFile;
import com.example.sdlc.agents.offline.CodeTemplates;
import com.example.sdlc.agents.offline.Feature;
import com.example.sdlc.agents.offline.FeatureCatalog;
import com.example.sdlc.codebase.CodebaseIndex;
import com.example.sdlc.codebase.SourceFile;
import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.ArtifactType;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.RequirementSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation agent. Produces code, tests and migrations as artifacts (never writes to the repository
 * directly - publishing is a separate, human-approved step). On a retry it receives the previous validation
 * violations as feedback.
 */
public class DeveloperAgent extends AgentSupport {

    public DeveloperAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "implementation";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        int attempt = state.implementationAttempts() + 1;
        RequirementSpec spec = state.spec().orElseThrow();
        ImpactAnalysis impact = state.impact().orElse(ImpactAnalysis.notApplicable("n/a"));
        CodebaseIndex index = ctx.codebase().get();
        String cycle = "validation: plan v" + state.planVersion() + " ";
        List<String> feedback = state.feedback().stream().filter(f -> f.startsWith(cycle)).toList();
        List<String> review = state.feedback().stream().filter(f -> f.startsWith("review:")).toList();
        // run-wide attempt counter (the per-plan counter resets after a re-plan)
        long runAttempt = state.executionNodes().stream().filter(name()::equals).count() + 1;

        Outcome<GeneratedCode> out = run(state,
                () -> ctx.llm().generate(name(), Prompts.DEVELOPER, prompt(state, index, impact, feedback), GeneratedCode.class),
                gc -> !gc.files().isEmpty() && gc.files().stream()
                        .allMatch(f -> f.path() != null && !f.path().isBlank() && f.content() != null && f.type() != null),
                () -> generate(spec, impact, index, review,
                        ctx.settings().migrationsRoot().toString().replace('\\', '/'), ctx.settings()));

        List<GeneratedFile> files = new ArrayList<>(out.value().files());
        // Fault injection hook for demos/tests: emit code the policy engine must block.
        if (state.faults().getOrDefault("policy.implementation", 0) >= runAttempt) {
            files.add(CodeTemplates.insecureSample(ctx.settings().sourceRoot().toString().replace('\\', '/')));
        }

        int pv = state.planVersion();
        List<String> lineage = List.of("plan:v" + pv, "design", "impact", "attempt:" + attempt);
        List<Artifact> artifacts = new ArrayList<>(state.baselineArtifacts());
        for (GeneratedFile f : files) {
            artifacts.add(Artifact.of(f.type(), f.path(), f.content(), name(), pv, lineage));
        }
        long code = files.stream().filter(f -> f.type() == ArtifactType.CODE).count();
        long tests = files.stream().filter(f -> f.type() == ArtifactType.TEST).count();
        return Map.of(
                SdlcState.ARTIFACTS, artifacts,
                SdlcState.IMPL_ATTEMPTS, attempt,
                SdlcState.DECISIONS, List.of(decision(out, "Attempt " + attempt + ": " + code + " code, " + tests
                                + " test, " + (files.size() - code - tests) + " other files",
                        (out.value().notes() == null ? "" : out.value().notes())
                                + (feedback.isEmpty() ? "" : " | addressed feedback: " + feedback.get(feedback.size() - 1)),
                        "plan", "design", "impact", "validation-feedback")));
    }

    private String prompt(SdlcState state, CodebaseIndex index, ImpactAnalysis impact, List<String> feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("Requirement spec:\n").append(state.spec().orElseThrow()).append("\n\nPlan:\n")
                .append(state.plan().orElseThrow()).append("\n\nDesign:\n").append(state.design().orElseThrow())
                .append("\n\nImpact analysis:\n").append(impact).append("\n\n");
        for (ImpactAnalysis.ImpactedComponent c : impact.components()) {
            if (!c.reason().startsWith("modify")) continue;
            index.byPath(c.path()).ifPresent(sf -> sb.append("----- CURRENT CONTENT OF ").append(sf.path())
                    .append(" -----\n").append(sf.content()).append('\n'));
        }
        if (!feedback.isEmpty()) {
            sb.append("\nValidation feedback from the previous attempt (fix ALL of these):\n");
            feedback.forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        List<String> review = state.feedback().stream().filter(f -> f.startsWith("review:")).toList();
        if (!review.isEmpty()) {
            sb.append("\nHuman reviewer feedback to implement:\n");
            review.forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        return sb.toString();
    }

    /** Deterministic strategy: reference implementation + verified feature patches. */
    static GeneratedCode generate(RequirementSpec spec, ImpactAnalysis impact, CodebaseIndex index,
                                  List<String> reviewFeedback, String migrationsRoot,
                                  com.example.sdlc.governance.SdlcSettings settings) {
        Map<String, GeneratedFile> byPath = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (spec.changeType() == ChangeType.GREENFIELD) {
            for (GeneratedFile f : CodeTemplates.referenceImplementation(settings.sourceRoot(), settings.migrationsRoot())) {
                byPath.put(f.path(), f);
            }
            notes.add("greenfield: replayed curated reference implementation (" + byPath.size() + " files)");
        }
        Set<String> satisfied = impact.existingCapabilities().stream()
                .map(c -> c.substring(0, c.indexOf(':')).trim()).collect(Collectors.toSet());

        java.util.function.Function<String, Optional<String>> read = path ->
                Optional.ofNullable(byPath.get(path)).map(GeneratedFile::content)
                        .or(() -> index.byPath(path).map(SourceFile::content));
        java.util.function.Function<String, Optional<String>> pathOf = type -> {
            Pattern decl = Pattern.compile("\\b(class|interface|record|enum)\\s+" + type + "\\b");
            return byPath.values().stream().filter(f -> f.path().endsWith(".java") && decl.matcher(f.content()).find())
                    .map(GeneratedFile::path).findFirst()
                    .or(() -> index.declaring(type).map(SourceFile::path));
        };

        for (String name : spec.features()) {
            Feature f = FeatureCatalog.byName(name).orElse(null);
            if (satisfied.contains(name)) {
                notes.add(name + ": already implemented, no change");
                continue;
            }
            if (f == null) {
                GeneratedFile p = CodeTemplates.changeProposal(name, spec.summary());
                byPath.put(p.path(), p);
                notes.add(name + ": no verified template -> change proposal");
                continue;
            }
            List<GeneratedFile> generated = switch (f.key()) {
                case "maxclicks" -> CodeTemplates.maxClicks(pathOf, read, migrationsRoot, nextMigration(index, byPath));
                case "cache" -> CodeTemplates.caching(pathOf, read);
                default -> List.of();
            };
            if (generated.isEmpty() && spec.changeType() == ChangeType.BROWNFIELD) {
                GeneratedFile p = CodeTemplates.changeProposal(name, spec.summary());
                byPath.put(p.path(), p);
                notes.add(name + ": no verified template -> change proposal");
            }
            generated.forEach(g -> byPath.put(g.path(), g));
            if (!generated.isEmpty()) notes.add(name + ": " + generated.size() + " files via verified template");
        }
        int n = 0;
        for (String fb : reviewFeedback) {
            // Offline templates cannot act on free-text review comments: record them as an explicit proposal
            // (an LLM-backed run receives them in the prompt instead).
            GeneratedFile p = CodeTemplates.changeProposal("review-feedback-" + (++n), fb.substring("review:".length()).trim());
            byPath.put(p.path(), p);
            notes.add("reviewer feedback #" + n + " -> change proposal (needs LLM or engineer)");
        }
        return new GeneratedCode(List.copyOf(byPath.values()), String.join("; ", notes));
    }

    private static int nextMigration(CodebaseIndex index, Map<String, GeneratedFile> pending) {
        int fromPending = pending.keySet().stream()
                .map(p -> p.substring(p.lastIndexOf('/') + 1))
                .filter(n -> n.matches("V\\d+__.*\\.sql"))
                .mapToInt(n -> Integer.parseInt(n.substring(1, n.indexOf("__"))))
                .max().orElse(0) + 1;
        return Math.max(index.nextMigrationVersion(), fromPending);
    }
}
