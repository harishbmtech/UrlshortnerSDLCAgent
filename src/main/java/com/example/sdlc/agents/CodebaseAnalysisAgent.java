package com.example.sdlc.agents;

import com.example.sdlc.agents.offline.Feature;
import com.example.sdlc.agents.offline.FeatureCatalog;
import com.example.sdlc.codebase.CodebaseIndex;
import com.example.sdlc.codebase.SourceFile;
import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.ImpactAnalysis.ImpactedComponent;
import com.example.sdlc.model.Plan;
import com.example.sdlc.model.RequirementSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Brownfield codebase reasoning: symbol-reference impact analysis over the real source tree.
 * <ul>
 *   <li>declaration changes -> the declaring file is modified</li>
 *   <li>shape changes of records/constructors -> every call site is modified</li>
 *   <li>behaviour changes -> every usage is regression-reviewed</li>
 *   <li>existing capabilities are detected so we do not rebuild what is already there</li>
 * </ul>
 * Any LLM-proposed impact is filtered against the index (hallucinated paths are dropped).
 * When the analysis changes the picture (schema change, capability already present) the plan is re-planned.
 */
public class CodebaseAnalysisAgent extends AgentSupport {

    public CodebaseAnalysisAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "codebase_analysis";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow();
        Plan plan = state.plan().orElseThrow();
        CodebaseIndex index = ctx.codebase().get();

        if (spec.changeType() == ChangeType.GREENFIELD) {
            ImpactAnalysis na = ImpactAnalysis.notApplicable(
                    "Greenfield change: no existing module is modified; the reference architecture applies");
            return Map.of(SdlcState.IMPACT, na,
                    SdlcState.DECISIONS, List.of(decision("Skipped impact analysis", "greenfield", "spec")));
        }

        Outcome<ImpactAnalysis> out = run(state,
                () -> sanitize(ctx.llm().generate(name(), Prompts.CODEBASE,
                        "Requirement spec:\n" + spec + "\n\nCodebase outline:\n" + index.outline(), ImpactAnalysis.class), index),
                ia -> ia.applicable() && !ia.components().isEmpty(),
                () -> analyse(spec, index));

        ImpactAnalysis impact = out.value();
        List<String> satisfied = impact.existingCapabilities().stream()
                .map(c -> c.substring(0, c.indexOf(':')).trim()).toList();
        Plan adjusted = PlanningAgent.adjust(plan, impact.schemaChanges(), satisfied);
        boolean replanned = !adjusted.tasks().equals(plan.tasks());

        Map<String, Object> result = new HashMap<>();
        result.put(SdlcState.IMPACT, impact);
        List<Object> decisions = new ArrayList<>();
        decisions.add(decision(out, "Blast radius " + impact.blastRadius() + " files; schema changes "
                        + impact.schemaChanges().size() + "; existing capabilities " + impact.existingCapabilities().size(),
                impact.summary(), "spec", "codebase"));
        if (replanned) {
            Plan revised = new Plan(plan.version() + 1, adjusted.tasks(), adjusted.executionWaves(), adjusted.rationale());
            result.put(SdlcState.PLAN, revised);
            result.put(SdlcState.PLAN_VERSION, revised.version());
            decisions.add(decision("Re-planned to v" + revised.version() + " after impact analysis",
                    "schemaChanges=" + impact.schemaChanges() + ", alreadySatisfied=" + satisfied, "impact", "plan"));
            ctx.metrics().replanned();
        }
        result.put(SdlcState.DECISIONS, decisions);
        return result;
    }

    /** Deterministic strategy. Public for unit testing. */
    public static ImpactAnalysis analyse(RequirementSpec spec, CodebaseIndex index) {
        Map<String, ImpactedComponent> components = new LinkedHashMap<>();
        Set<String> schema = new LinkedHashSet<>();
        List<String> existing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (String featureName : spec.features()) {
            Feature f = FeatureCatalog.byName(featureName).orElse(null);
            if (f == null) {
                unknown.add(featureName);
                continue;
            }
            Feature.Impact imp = f.impact();
            String capability = imp.existingCapabilitySymbol();
            if (capability != null) {
                var holder = index.declaring(capability).or(() -> index.files().stream()
                        .filter(sf -> sf.references(capability)).findFirst());
                if (holder.isPresent()) {
                    existing.add(f.name() + ": already implemented (" + capability + " in " + holder.get().path() + ")");
                    continue;
                }
            }
            for (String type : imp.declares()) {
                index.declaring(type).ifPresentOrElse(
                        sf -> put(components, sf, "modify", "declares " + type),
                        () -> unknown.add(f.name() + " expects type " + type));
            }
            for (String type : imp.callSitesOf()) {
                Pattern ctor = Pattern.compile("new\\s+(\\w+\\.)?" + type + "\\s*\\(");
                for (SourceFile sf : index.files()) {
                    if (!sf.declares(type) && ctor.matcher(sf.code()).find()) {
                        put(components, sf, "modify", "constructs " + type);
                    }
                }
            }
            for (String symbol : imp.reviewUsageOf()) {
                for (SourceFile sf : index.referencing(symbol)) {
                    put(components, sf, "review", "uses " + symbol);
                }
                index.declaring(symbol).ifPresent(sf -> put(components, sf, "review", "declares " + symbol));
            }
            if (imp.schemaChange() != null) schema.add(imp.schemaChange());
        }

        List<String> apis = new ArrayList<>();
        List<String> flows = new ArrayList<>();
        for (ImpactedComponent c : components.values()) {
            index.byPath(c.path()).ifPresent(sf -> apis.addAll(sf.endpoints()));
        }
        if (components.values().stream().anyMatch(c -> c.layer().equals("web"))) {
            flows.add("HTTP request -> web controller -> ShortUrlService -> ShortUrlStore -> database");
        }
        if (!schema.isEmpty()) {
            flows.add("Schema: new column read by the entity on every redirect lookup (hot path)");
        }
        if (components.values().stream().anyMatch(c -> c.reason().contains("ShortUrlStore"))) {
            flows.add("Redirect lookup path: RedirectController -> ShortUrlService.resolve -> ShortUrlStore.findByCode");
        }
        int blast = components.size();
        String summary = "Impacted " + blast + " file(s) ("
                + components.values().stream().filter(c -> c.reason().startsWith("modify")).count() + " modified, "
                + components.values().stream().filter(c -> c.reason().startsWith("review")).count() + " regression-review)"
                + (existing.isEmpty() ? "" : "; " + existing.size() + " requested capabilities already exist")
                + (unknown.isEmpty() ? "" : "; no offline knowledge for " + unknown);
        return new ImpactAnalysis(true, List.copyOf(components.values()), apis.stream().distinct().toList(),
                flows, List.copyOf(schema), existing, blast, summary);
    }

    private static void put(Map<String, ImpactedComponent> map, SourceFile sf, String kind, String why) {
        ImpactedComponent prev = map.get(sf.path());
        String reason = kind + ": " + why;
        if (prev != null) {
            boolean modify = prev.reason().startsWith("modify") || kind.equals("modify");
            String merged = (modify ? "modify: " : "review: ")
                    + prev.reason().substring(prev.reason().indexOf(':') + 2) + "; " + why;
            map.put(sf.path(), new ImpactedComponent(sf.path(), sf.layer(), merged));
        } else {
            map.put(sf.path(), new ImpactedComponent(sf.path(), sf.layer(), reason));
        }
    }

    /** Hallucination guard: drop any component whose path does not exist in the codebase. */
    static ImpactAnalysis sanitize(ImpactAnalysis ia, CodebaseIndex index) {
        Set<String> known = index.paths();
        List<ImpactedComponent> real = ia.components().stream().filter(c -> known.contains(c.path())).toList();
        return new ImpactAnalysis(ia.applicable(), real, ia.impactedApis(), ia.dataFlows(), ia.schemaChanges(),
                ia.existingCapabilities(), real.size(), ia.summary());
    }
}
