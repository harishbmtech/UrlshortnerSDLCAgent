package com.example.sdlc.agents;

import com.example.sdlc.agents.AgentDtos.DocsBundle;
import com.example.sdlc.agents.AgentDtos.GeneratedFile;
import com.example.sdlc.governance.ResilientExecutor.Outcome;
import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.llm.Prompts;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.DesignSpec;
import com.example.sdlc.model.Enums.ArtifactType;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.RequirementSpec;
import com.example.sdlc.model.RiskAssessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Documentation: change summary / API usage, operational runbook with rollback, changelog.
 */
public class DocumentationAgent extends AgentSupport {

    public DocumentationAgent(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "documentation";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) {
        RequirementSpec spec = state.spec().orElseThrow();
        DesignSpec design = state.design().orElseThrow();
        RiskAssessment risk = state.risk().orElseThrow();
        List<String> changed = state.artifacts().stream()
                .filter(a -> a.type() == ArtifactType.CODE || a.type() == ArtifactType.MIGRATION || a.type() == ArtifactType.TEST)
                .map(Artifact::path).toList();

        Outcome<DocsBundle> out = run(state,
                () -> ctx.llm().generate(name(), Prompts.WRITER, "Spec:\n" + spec + "\nDesign:\n" + design
                        + "\nRisks:\n" + risk + "\nChanged files:\n" + changed, DocsBundle.class),
                b -> !b.documents().isEmpty() && b.documents().stream().allMatch(d -> d.path().startsWith("docs/"))
                        && b.documents().stream().anyMatch(d -> d.path().contains("runbook")),
                () -> docs(spec, design, risk, changed));

        int pv = state.planVersion();
        List<Artifact> artifacts = new ArrayList<>(state.artifacts());
        for (GeneratedFile f : out.value().documents()) {
            artifacts.add(Artifact.of(ArtifactType.DOC, f.path(), f.content(), name(), pv, List.of("plan:v" + pv, "design", "risk", "code")));
        }
        return Map.of(SdlcState.ARTIFACTS, artifacts,
                SdlcState.DECISIONS, List.of(decision(out, out.value().documents().size() + " documents", "", "code", "design")));
    }

    static DocsBundle docs(RequirementSpec spec, DesignSpec design, RiskAssessment risk, List<String> changed) {
        boolean migration = changed.stream().anyMatch(p -> p.endsWith(".sql"));
        String usage = """
                # %s

                %s

                ## Acceptance criteria
                %s
                ## API usage
                ```bash
                # create (optionally with alias, ttlSeconds%s)
                curl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' \\
                     -d '{"url":"https://example.com/docs","ttlSeconds":3600%s}'
                # follow
                curl -si localhost:8080/<code>
                # analytics
                curl -s localhost:8080/api/v1/urls/<code>/stats
                ```

                ## Contract
                See `docs/api/openapi.yaml`.
                """.formatted(spec.title(), design.overview(), bullet(spec.acceptanceCriteria()),
                spec.features().contains("Max-clicks limit") ? ", maxClicks" : "",
                spec.features().contains("Max-clicks limit") ? ",\"maxClicks\":100" : "");
        String runbook = """
                # Runbook

                ## Deploy
                1. `mvn verify` (unit + integration tests) must be green.
                2. Deploy to one canary instance%s.
                3. Verify: `GET /actuator/health` is UP; create + follow a probe link; error rate and p95 latency stable for 15 min.
                4. Roll out to remaining instances.

                ## Rollback
                - Redeploy the previous build. %s
                - Links created during the rollout keep working (no destructive data change).

                ## Failure scenarios
                %s
                ## Top risks and mitigations
                %s""".formatted(
                migration ? " (Flyway applies the new additive migration on startup)" : "",
                migration ? "The migration is additive, so the previous version runs unchanged against the new schema (no down-migration needed)."
                        : "No schema change in this release.",
                bullet(risk.failureScenarios()),
                bullet(risk.risks().stream().map(r -> "[" + r.severity() + "] " + r.description() + " -> " + r.mitigation()).toList()));
        String changelog = "# Changelog\n\n## Unreleased\n### " + (spec.changeType() == ChangeType.GREENFIELD ? "Added" : "Changed")
                + "\n" + bullet(spec.features()) + "\n### Files\n" + bullet(changed);
        return new DocsBundle(List.of(
                new GeneratedFile("docs/CHANGE-SUMMARY.md", ArtifactType.DOC, usage),
                new GeneratedFile("docs/runbook.md", ArtifactType.DOC, runbook),
                new GeneratedFile("docs/CHANGELOG.md", ArtifactType.DOC, changelog)));
    }
}
