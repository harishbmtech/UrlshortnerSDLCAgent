package com.example.sdlc.agents;

import com.example.sdlc.agents.offline.Feature;
import com.example.sdlc.agents.offline.FeatureCatalog;
import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.DesignSpec;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.ImpactAnalysis;
import com.example.sdlc.model.RequirementSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Architecture &amp; design (runs in parallel with risk and test strategy): components, API contract, schema, ADRs.
 */
public class ArchitectureAgent extends AgentSupport {

    public ArchitectureAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "architecture_design";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow();
        ImpactAnalysis impact = state.impact().orElse(ImpactAnalysis.notApplicable("n/a"));
        Outcome<DesignSpec> out = run(state,
                () -> ctx.llm().generate(name(), Prompts.ARCHITECT,
                        "Spec:\n" + spec + "\n\nImpact analysis:\n" + impact, DesignSpec.class),
                d -> d.overview() != null && !d.components().isEmpty()
                        && !d.schemaDdl().toLowerCase().matches("(?s).*\\b(drop|truncate)\\b.*"),
                () -> design(spec, impact, ctx.settings().migrationsRoot()));
        return Map.of(SdlcState.DESIGN, out.value(),
                SdlcState.DECISIONS, List.of(decision(out, out.value().decisions().size() + " design decisions",
                        out.value().overview(), "spec", "impact")));
    }

    /** Deterministic strategy. */
    public static DesignSpec design(RequirementSpec spec, ImpactAnalysis impact, Path migrationsRoot) {
        Set<String> satisfied = impact.existingCapabilities().stream()
                .map(c -> c.substring(0, c.indexOf(':')).trim()).collect(Collectors.toSet());
        List<Feature> features = spec.features().stream()
                .map(FeatureCatalog::byName).flatMap(java.util.Optional::stream)
                .filter(f -> !satisfied.contains(f.name())).toList();

        Set<String> components = new LinkedHashSet<>();
        List<String> decisions = new ArrayList<>();
        List<String> endpoints = new ArrayList<>();
        for (Feature f : features) {
            components.addAll(f.components());
            decisions.addAll(f.decisions());
            decisions.addAll(f.tradeOffs().stream().map(t -> "Trade-off: " + t).toList());
            endpoints.addAll(f.endpoints());
        }
        impact.components().forEach(c -> components.add(c.path() + " (" + c.reason() + ")"));
        if (components.isEmpty()) components.add("No new components: requirement satisfied by existing capabilities");

        String ddl;
        if (spec.changeType() == ChangeType.GREENFIELD) {
            ddl = readMigrations(migrationsRoot);
        } else {
            ddl = impact.schemaChanges().stream().map(s -> s + ";").collect(Collectors.joining("\n"));
            if (!ddl.isBlank()) decisions.add("Schema change is additive and nullable: zero-downtime, no backfill, old app versions keep working");
        }
        if (spec.changeType() == ChangeType.BROWNFIELD) {
            decisions.add("Backward compatibility: only optional request fields and additive columns; no endpoint is removed or renamed");
        }
        String overview = (spec.changeType() == ChangeType.GREENFIELD
                ? "Layered hexagonal service: REST controllers -> framework-free domain services -> ShortUrlStore port -> JPA adapter; "
                + "Flyway-managed schema; stateless instances."
                : "Incremental change to the existing layered service touching " + impact.blastRadius() + " file(s).")
                + " Features: " + features.stream().map(Feature::name).toList();
        return new DesignSpec(overview, List.copyOf(components), openApi(endpoints, spec), ddl, decisions);
    }

    private static String openApi(List<String> endpoints, RequirementSpec spec) {
        if (endpoints.isEmpty()) return "# No API contract change\n";
        StringBuilder y = new StringBuilder("""
                openapi: 3.0.3
                info:
                  title: URL Shortener API
                  version: "1.x"
                paths:
                """);
        for (String e : endpoints.stream().distinct().toList()) {
            String method = e.substring(0, e.indexOf(' ')).toLowerCase();
            String rest = e.substring(e.indexOf(' ') + 1);
            String path = rest.contains(" ") ? rest.substring(0, rest.indexOf(' ')) : rest;
            String note = rest.contains("(") ? rest.substring(rest.indexOf('(') + 1, rest.lastIndexOf(')')) : "";
            y.append("  ").append(path).append(":\n    ").append(method).append(":\n");
            y.append("      summary: ").append(summary(method, path)).append('\n');
            if (!note.isEmpty()) y.append("      description: ").append(note).append('\n');
            if (method.equals("post")) {
                y.append("""
                              requestBody:
                                required: true
                                content:
                                  application/json:
                                    schema:
                                      type: object
                                      required: [url]
                                      properties:
                                        url: {type: string, maxLength: 2048}
                                        alias: {type: string, pattern: '^[A-Za-z0-9_-]{4,32}$'}
                                        ttlSeconds: {type: integer, minimum: 1}
                        """);
                if (spec.features().contains("Max-clicks limit")) {
                    y.append("                maxClicks: {type: integer, minimum: 1, description: optional redirect budget}\n");
                }
                y.append("      responses:\n        '201': {description: Created}\n        '400': {description: Invalid URL/alias}\n"
                        + "        '409': {description: Alias taken}\n        '429': {description: Rate limited}\n");
            } else if (path.equals("/{code}")) {
                y.append("      responses:\n        '302': {description: Redirect to target}\n"
                        + "        '404': {description: Unknown code}\n        '410': {description: Expired, disabled or exhausted}\n");
            } else if (method.equals("delete")) {
                y.append("      responses:\n        '204': {description: Disabled}\n        '404': {description: Unknown code}\n");
            } else {
                y.append("      responses:\n        '200': {description: OK}\n        '404': {description: Unknown code}\n");
            }
        }
        return y.toString();
    }

    private static String summary(String method, String path) {
        return switch (method + " " + path) {
            case "post /api/v1/urls" -> "Create a short link";
            case "get /api/v1/urls/{code}" -> "Get link metadata";
            case "delete /api/v1/urls/{code}" -> "Disable a link";
            case "get /api/v1/urls/{code}/stats" -> "Click analytics";
            case "get /{code}" -> "Redirect";
            default -> method.toUpperCase() + " " + path;
        };
    }

    private static String readMigrations(Path root) {
        if (root == null || !Files.isDirectory(root)) return "";
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(f -> f.toString().endsWith(".sql")).sorted().map(f -> {
                try {
                    return "-- " + f.getFileName() + "\n" + Files.readString(f);
                } catch (IOException e) {
                    return "";
                }
            }).collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "";
        }
    }
}
