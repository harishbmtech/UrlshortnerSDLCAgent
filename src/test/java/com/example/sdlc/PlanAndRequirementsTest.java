package com.example.sdlc;

import com.example.sdlc.agents.PlanValidator;
import com.example.sdlc.agents.PlanningAgent;
import com.example.sdlc.agents.RequirementsAgent;
import com.example.sdlc.model.EngineeringTask;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.Enums.Stage;
import com.example.sdlc.model.Plan;
import com.example.sdlc.model.RequirementSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAndRequirementsTest {

    private static EngineeringTask t(String id, String... deps) {
        return new EngineeringTask(id, id, Stage.IMPLEMENT, List.of(deps), RiskLevel.LOW, "");
    }

    @Test
    void plannerComputesParallelWavesAndRejectsCycles() {
        PlanValidator.Result ok = PlanValidator.validate(List.of(t("A"), t("B", "A"), t("C", "A"), t("D", "B", "C")));
        assertTrue(ok.valid());
        assertEquals(List.of(List.of("A"), List.of("B", "C"), List.of("D")), ok.waves());

        assertFalse(PlanValidator.validate(List.of(t("A", "B"), t("B", "A"))).valid());
        assertFalse(PlanValidator.validate(List.of(t("A", "Z"))).valid());
        assertFalse(PlanValidator.validate(List.of(t("A"), t("A"))).valid());
    }

    @Test
    void requirementClassificationAndAmbiguity() {
        RequirementSpec green = RequirementsAgent.analyse(SdlcOrchestratorScenarioTest.GREENFIELD, Map.of(), true);
        assertEquals(ChangeType.GREENFIELD, green.changeType());
        assertTrue(green.blockingAmbiguities().isEmpty());
        assertTrue(green.features().contains("Click analytics"));

        RequirementSpec brown = RequirementsAgent.analyse(SdlcOrchestratorScenarioTest.BROWNFIELD, Map.of(), true);
        assertEquals(ChangeType.BROWNFIELD, brown.changeType());
        assertEquals(List.of("Max-clicks limit"), brown.features());

        RequirementSpec vague = RequirementsAgent.analyse("Make the short links faster and more secure.", Map.of(), true);
        assertEquals(2, vague.blockingAmbiguities().size());

        RequirementSpec measurable = RequirementsAgent.analyse(
                "Make redirects faster: p95 under 20 ms at 500 rps using a cache", Map.of(), true);
        assertTrue(measurable.blockingAmbiguities().isEmpty(), "measurable targets are not ambiguous");
    }

    @Test
    void everyAcceptanceCriterionHasStableId() {
        RequirementSpec spec = RequirementsAgent.analyse(SdlcOrchestratorScenarioTest.GREENFIELD, Map.of(), false);
        for (int i = 0; i < spec.acceptanceCriteria().size(); i++) {
            assertTrue(spec.acceptanceCriteria().get(i).startsWith("AC-" + (i + 1) + ": "));
        }
    }

    @Test
    void planIsAValidDagAndAdjustsForSchemaChanges() {
        RequirementSpec brown = RequirementsAgent.analyse(SdlcOrchestratorScenarioTest.BROWNFIELD, Map.of(), true);
        Plan plan = PlanningAgent.plan(brown, List.of(), 1);
        assertTrue(PlanValidator.validate(plan.tasks()).valid());
        assertEquals(Stage.ANALYSIS, plan.tasks().get(0).stage());

        Plan adjusted = PlanningAgent.adjust(plan, List.of("ALTER TABLE x ADD COLUMN y INT"), List.of());
        assertEquals(plan.tasks().size() + 1, adjusted.tasks().size());
        assertTrue(PlanValidator.validate(adjusted.tasks()).valid());
    }
}
