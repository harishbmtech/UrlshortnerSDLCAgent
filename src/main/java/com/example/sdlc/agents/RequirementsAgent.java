package com.example.sdlc.agents;

import com.example.sdlc.agents.offline.Feature;
import com.example.sdlc.agents.offline.FeatureCatalog;
import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.RequirementSpec.Ambiguity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Requirement understanding: interprets intent, classifies greenfield vs brownfield, derives testable
 * acceptance criteria and flags ambiguity. Blocking ambiguity routes the graph to a human clarification gate.
 */
public class RequirementsAgent extends AgentSupport {

    public static final String PROCEED_ON_ASSUMPTIONS = "proceedOnAssumptions";

    private static final Pattern BROWNFIELD = Pattern.compile(
            "\\b(existing|current|to the (service|api|shortener)|extend|enhance|fix|bug|refactor|regression"
                    + "|make .*(faster|safer|more|better)|improve|add (an? )?(optional )?[\\w -]+ to)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GREENFIELD = Pattern.compile(
            "\\b(from scratch|greenfield|new (service|system)|(build|create|develop) (a |an )?(new )?(url shortener|service|system))\\b",
            Pattern.CASE_INSENSITIVE);

    public RequirementsAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "requirements_analysis";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        boolean proceedOnAssumptions = state.<Boolean>value(PROCEED_ON_ASSUMPTIONS).orElse(false);
        Map<String, String> clarifications = state.clarifications();

        Outcome<RequirementSpec> out = run(state,
                () -> ctx.llm().generate(name(), Prompts.REQUIREMENTS, userPrompt(state), RequirementSpec.class),
                spec -> !spec.acceptanceCriteria().isEmpty()
                        && spec.acceptanceCriteria().stream().allMatch(ac -> ac.startsWith("AC-"))
                        && spec.changeType() != null,
                () -> analyse(state.requirement(), clarifications, !ctx.codebase().get().isEmpty()));

        RequirementSpec spec = out.value();
        if (proceedOnAssumptions && !spec.blockingAmbiguities().isEmpty()) {
            spec = convertAmbiguitiesToAssumptions(spec);
        }
        String verdict = spec.blockingAmbiguities().isEmpty()
                ? "Requirement is actionable (" + spec.changeType() + ", " + spec.acceptanceCriteria().size() + " acceptance criteria)"
                : spec.blockingAmbiguities().size() + " blocking ambiguities -> human clarification required";
        return Map.of(
                SdlcState.SPEC, spec,
                SdlcState.DECISIONS, List.of(decision(out, verdict,
                        "features=" + spec.features() + "; clarifications applied=" + clarifications.keySet(),
                        "requirement", "clarifications")));
    }

    private String userPrompt(SdlcState state) {
        return """
                Requirement:
                %s

                Human clarifications already given (ambiguity id -> answer):
                %s

                Existing codebase outline (empty means greenfield):
                %s
                """.formatted(state.requirement(), state.clarifications(), ctx.codebase().get().outline());
    }

    /** Deterministic strategy. Public for unit testing. */
    public static RequirementSpec analyse(String requirement, Map<String, String> clarifications, boolean codebaseExists) {
        String clarified = String.join(" ", clarifications.values());
        String text = requirement + " " + clarified;

        ChangeType changeType;
        if (BROWNFIELD.matcher(requirement).find()) changeType = ChangeType.BROWNFIELD;
        else if (GREENFIELD.matcher(requirement).find()) changeType = ChangeType.GREENFIELD;
        else changeType = codebaseExists ? ChangeType.BROWNFIELD : ChangeType.GREENFIELD;

        List<Feature> features = new ArrayList<>(FeatureCatalog.detect(text));
        if (changeType == ChangeType.BROWNFIELD
                && features.stream().anyMatch(f -> !f.key().equals("shorten") && !f.key().equals("redirect"))) {
            // "short link" / "redirect" are incidental in change requests: the core capability already exists
            features.removeIf(f -> f.key().equals("shorten") || f.key().equals("redirect"));
        }

        List<String> acs = new ArrayList<>();
        for (Feature f : features) {
            for (String ac : f.acceptanceCriteria()) acs.add(ac);
        }
        if (features.isEmpty()) {
            for (String sentence : requirement.split("(?<=[.!?])\\s+")) {
                if (!sentence.isBlank()) acs.add(sentence.trim());
            }
        }
        if (changeType == ChangeType.BROWNFIELD) {
            acs.add("Existing behaviour and API contracts remain unchanged; the existing test suite stays green");
        }
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < acs.size(); i++) numbered.add("AC-" + (i + 1) + ": " + acs.get(i));

        List<String> nfr = new ArrayList<>();
        clarifications.forEach((id, answer) -> nfr.add("From clarification " + id + ": " + answer));
        if (changeType == ChangeType.GREENFIELD) {
            nfr.add("Stateless application instances so the service scales horizontally behind a load balancer");
            nfr.add("No raw personal data (client IPs) persisted or logged");
            nfr.add("Schema managed by versioned, reviewable migrations");
        }

        List<Ambiguity> ambiguities = new ArrayList<>();
        boolean measurable = FeatureCatalogAccess.measurable(requirement);
        for (FeatureCatalog.VagueTerm vt : FeatureCatalog.VAGUE_TERMS) {
            String id = "AMB-" + vt.term().toUpperCase(Locale.ROOT);
            if (vt.pattern().matcher(requirement).find() && !measurable && !clarifications.containsKey(id)) {
                ambiguities.add(new Ambiguity(id, "'" + vt.term() + "' is not measurable as stated", vt.question(), true));
            }
        }
        if (features.isEmpty() && ambiguities.isEmpty() && !clarifications.containsKey("AMB-SCOPE")) {
            ambiguities.add(new Ambiguity("AMB-SCOPE", "The request does not map to a known capability",
                    "Which user-visible behaviour should change, and how will we verify it?", true));
        }

        Set<String> assumptions = new LinkedHashSet<>();
        assumptions.add("Java 21 / Spring Boot 3 service; H2 for local runs, PostgreSQL-compatible SQL for production");
        assumptions.add("Single region deployment; management API authentication is handled by the API gateway");
        if (changeType == ChangeType.BROWNFIELD) {
            assumptions.add("Changes must be backward compatible for existing clients and stored links");
        }

        String title = titleOf(requirement);
        return new RequirementSpec(title, requirement.trim(), changeType,
                features.stream().map(Feature::name).toList(), numbered, nfr, ambiguities, List.copyOf(assumptions));
    }

    static RequirementSpec convertAmbiguitiesToAssumptions(RequirementSpec spec) {
        List<String> assumptions = new ArrayList<>(spec.assumptions());
        spec.blockingAmbiguities().forEach(a -> assumptions.add(
                "Approved to proceed without clarifying " + a.id() + " (" + a.statement()
                        + "); interpreted conservatively using existing service defaults"));
        List<Ambiguity> nonBlocking = spec.ambiguities().stream()
                .map(a -> new Ambiguity(a.id(), a.statement(), a.question(), false)).toList();
        return new RequirementSpec(spec.title(), spec.summary(), spec.changeType(), spec.features(),
                spec.acceptanceCriteria(), spec.nonFunctional(), nonBlocking, assumptions);
    }

    private static String titleOf(String requirement) {
        String[] words = requirement.trim().split("\\s+");
        String t = String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 9)));
        return words.length > 9 ? t + "..." : t;
    }

    /** Small indirection so the catalog's measurable pattern stays package-private. */
    static final class FeatureCatalogAccess {
        private static final Pattern MEASURABLE = Pattern.compile(
                "\\d+\\s*(ms|s|sec|seconds|%|rps|qps|req|requests|k|m|x)\\b|p9[059]", Pattern.CASE_INSENSITIVE);

        static boolean measurable(String text) {
            return MEASURABLE.matcher(text).find();
        }
    }
}
