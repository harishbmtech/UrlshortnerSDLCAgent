package com.example.sdlc.agents;

import com.example.sdlc.agents.AgentDtos.PlanDraft;
import com.example.sdlc.agents.offline.Feature;
import com.example.sdlc.agents.offline.FeatureCatalog;
import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.EngineeringTask;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.Enums.Stage;
import com.example.sdlc.model.Plan;
import com.example.sdlc.model.RequirementSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Task decomposition into a dependency DAG with execution waves. Every (re-)plan increments the plan version;
 * downstream artifacts carry the version they were derived from (lineage), so stale outputs are detectable.
 */
public class PlanningAgent extends AgentSupport {

    public PlanningAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "planning";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow(() -> new IllegalStateException("planning requires a spec"));
        int version = state.planVersion() + 1;
        List<String> feedback = state.feedback().stream().filter(f -> f.startsWith("review:")).toList();

        Outcome<Plan> out = run(state,
                () -> fromDraft(ctx.llm().generate(name(), Prompts.PLANNER, prompt(spec, feedback), PlanDraft.class), version),
                plan -> PlanValidator.validate(plan.tasks()).valid(),
                () -> plan(spec, feedback, version));

        Plan plan = out.value();
        String why = version == 1 ? "initial plan" : "re-plan v" + version + " (upstream change: " +
                (feedback.isEmpty() ? "clarified requirements" : feedback.get(feedback.size() - 1)) + ")";
        return Map.of(
                SdlcState.PLAN, plan,
                SdlcState.PLAN_VERSION, version,
                SdlcState.DECISIONS, List.of(decision(out,
                        plan.tasks().size() + " tasks in " + plan.executionWaves().size() + " waves (" + why + ")",
                        plan.rationale(), "spec", "feedback")));
    }

    private String prompt(RequirementSpec spec, List<String> feedback) {
        return "Requirement spec:\n" + spec + "\n\nReviewer feedback to address:\n" + feedback;
    }

    private static Plan fromDraft(PlanDraft draft, int version) {
        List<EngineeringTask> tasks = draft.tasks().stream().map(t -> new EngineeringTask(t.id(), t.title(),
                enumOr(Stage.class, t.stage(), Stage.IMPLEMENT), t.dependsOn(),
                enumOr(RiskLevel.class, t.impact(), RiskLevel.MEDIUM), t.rationale())).toList();
        PlanValidator.Result r = PlanValidator.validate(tasks);
        return new Plan(version, tasks, r.waves(), draft.rationale());
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E dflt) {
        try {
            return value == null ? dflt : Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }

    /** Deterministic decomposition. Public for unit testing. */
    public static Plan plan(RequirementSpec spec, List<String> feedback, int version) {
        List<EngineeringTask> tasks = new ArrayList<>();
        boolean brownfield = spec.changeType() == ChangeType.BROWNFIELD;
        int n = 0;
        String analysis = null;
        if (brownfield) {
            analysis = "T" + (++n);
            tasks.add(new EngineeringTask(analysis, "Analyse impact on the existing codebase", Stage.ANALYSIS,
                    List.of(), RiskLevel.LOW, "Know the blast radius before designing a change"));
        }
        List<String> base = analysis == null ? List.of() : List.of(analysis);
        String design = "T" + (++n);
        tasks.add(new EngineeringTask(design, "Design API contract and schema changes", Stage.DESIGN, base,
                RiskLevel.MEDIUM, "Contract-first keeps clients and tests aligned"));
        String threat = "T" + (++n);
        tasks.add(new EngineeringTask(threat, "Threat model and risk assessment", Stage.DESIGN, base,
                RiskLevel.MEDIUM, "Security and failure modes are design inputs, not afterthoughts"));
        String testPlan = "T" + (++n);
        tasks.add(new EngineeringTask(testPlan, "Test strategy with acceptance-criteria traceability", Stage.TEST,
                List.of(design), RiskLevel.LOW, "Every AC must map to at least one automated test"));

        List<String> impl = new ArrayList<>();
        for (String featureName : spec.features()) {
            Feature f = FeatureCatalog.byName(featureName).orElse(null);
            RiskLevel impact = f == null ? RiskLevel.MEDIUM : f.risks().stream().map(r -> r.severity())
                    .reduce(RiskLevel.LOW, RiskLevel::max);
            String id = "T" + (++n);
            impl.add(id);
            tasks.add(new EngineeringTask(id, "Implement: " + featureName, Stage.IMPLEMENT, List.of(design),
                    impact, "Delivers " + featureName));
        }
        for (String fb : feedback) {
            String id = "T" + (++n);
            impl.add(id);
            tasks.add(new EngineeringTask(id, "Address reviewer feedback: " + fb.substring("review:".length()).trim(),
                    Stage.IMPLEMENT, List.of(design), RiskLevel.MEDIUM, "Human reviewer requested a change"));
        }
        if (impl.isEmpty()) {
            String id = "T" + (++n);
            impl.add(id);
            tasks.add(new EngineeringTask(id, "Implement requirement", Stage.IMPLEMENT, List.of(design),
                    RiskLevel.MEDIUM, "No catalogued feature matched; generic implementation task"));
        }
        List<String> testDeps = new ArrayList<>(impl);
        testDeps.add(testPlan);
        String tests = "T" + (++n);
        tasks.add(new EngineeringTask(tests, "Unit and integration tests", Stage.TEST, testDeps, RiskLevel.MEDIUM,
                "Verify each acceptance criterion"));
        List<String> releaseDeps = new ArrayList<>(List.of(tests, threat));
        if (brownfield) {
            String regression = "T" + (++n);
            tasks.add(new EngineeringTask(regression, "Regression suite on impacted modules", Stage.TEST, impl,
                    RiskLevel.MEDIUM, "Protect existing behaviour"));
            releaseDeps.add(regression);
        }
        String docs = "T" + (++n);
        tasks.add(new EngineeringTask(docs, "API docs, runbook and changelog", Stage.DOCS, impl, RiskLevel.LOW,
                "Operability and reviewability"));
        releaseDeps.add(docs);
        tasks.add(new EngineeringTask("T" + (++n), "Release readiness review and human sign-off", Stage.RELEASE,
                releaseDeps, RiskLevel.HIGH, "Publishing is a high-impact action and needs human approval"));

        PlanValidator.Result r = PlanValidator.validate(tasks);
        if (!r.valid()) throw new IllegalStateException("internal plan invalid: " + r.errors());
        String rationale = (brownfield ? "Brownfield: analysis gates design; " : "Greenfield: contract-first; ")
                + "design and threat modelling run in parallel; implementation tasks per feature run in parallel; "
                + "release is gated on tests, docs and risk review.";
        return new Plan(version, tasks, r.waves(), rationale);
    }

    /** Re-plans in place when upstream facts change (e.g. impact analysis finds a schema change). */
    public static Plan adjust(Plan plan, List<String> schemaChanges, List<String> alreadySatisfiedFeatures) {
        List<EngineeringTask> tasks = new ArrayList<>();
        String designId = plan.tasks().stream().filter(t -> t.stage() == Stage.DESIGN).map(EngineeringTask::id)
                .findFirst().orElse(null);
        List<String> removed = new ArrayList<>();
        for (EngineeringTask t : plan.tasks()) {
            boolean satisfied = t.stage() == Stage.IMPLEMENT && alreadySatisfiedFeatures.stream()
                    .anyMatch(f -> t.title().equals("Implement: " + f));
            if (satisfied) removed.add(t.id());
            else tasks.add(t);
        }
        // drop dangling dependencies to removed tasks
        tasks.replaceAll(t -> new EngineeringTask(t.id(), t.title(), t.stage(),
                t.dependsOn().stream().filter(d -> !removed.contains(d)).toList(), t.impact(), t.rationale()));
        if (!schemaChanges.isEmpty() && tasks.stream().noneMatch(t -> t.title().startsWith("Add additive DB migration"))) {
            String id = "T" + (tasks.size() + removed.size() + 1);
            tasks.add(new EngineeringTask(id, "Add additive DB migration: " + String.join("; ", schemaChanges),
                    Stage.IMPLEMENT, designId == null ? List.of() : List.of(designId), RiskLevel.MEDIUM,
                    "Impact analysis found a schema change; released migrations are immutable"));
            tasks.replaceAll(t -> t.stage() == Stage.TEST && t.title().startsWith("Unit and integration")
                    ? new EngineeringTask(t.id(), t.title(), t.stage(),
                    java.util.stream.Stream.concat(t.dependsOn().stream(), java.util.stream.Stream.of(id)).toList(),
                    t.impact(), t.rationale())
                    : t);
        }
        PlanValidator.Result r = PlanValidator.validate(tasks);
        if (!r.valid()) return plan;
        return new Plan(plan.version(), tasks, r.waves(), plan.rationale()
                + (removed.isEmpty() ? "" : " Dropped tasks already satisfied by existing code: " + removed + ".")
                + (schemaChanges.isEmpty() ? "" : " Added migration task after impact analysis."));
    }
}
