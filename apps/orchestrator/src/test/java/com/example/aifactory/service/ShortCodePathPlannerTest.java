package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodePathPlannerTest {
    private final ShortCodePathPlanner planner = new ShortCodePathPlanner();

    @Test
    void plansEveryMandatorySimpleFixtureWithOneDeveloperAndMandatoryControls() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(fixturePath())).path("cases");

        for (JsonNode fixture : cases) {
            JsonNode scope = fixture.path("scope");
            ShortCodePathPlanner.Plan plan = planner.plan(input(
                    fixture.path("risk").asText(), scope.path("max_modules").asInt(),
                    scope.path("max_domains").asInt(), scope.path("max_files").asInt(), Set.of()))
                    .orElseThrow();

            assertThat(plan.path()).as(fixture.path("id").asText()).isEqualTo("SHORT_CODE_PATH");
            assertThat(plan.agents()).containsExactly("supervisor", "developer", "independent-reviewer");
            assertThat(plan.forbiddenAgents()).containsExactlyInAnyOrder("architecture-agent", "security-agent");
            assertThat(plan.deterministicControls()).containsExactly(
                    "PATCH_VALIDATION", "TESTS", "QUALITY", "SECURITY", "EVIDENCE_MANIFEST");
            assertThat(plan.maxDeveloperDelegations()).isEqualTo(1);
            assertThat(plan.humanGate()).isEqualTo("NONE");
        }
    }

    @Test
    void refusesShortPathWhenRiskScopeImpactOrModeExceedsTheHostPolicy() {
        assertThat(planner.plan(input("R2", 1, 1, 2, Set.of()))).isEmpty();
        assertThat(planner.plan(input("R1", 2, 1, 2, Set.of()))).isEmpty();
        assertThat(planner.plan(input("R1", 1, 2, 2, Set.of()))).isEmpty();
        assertThat(planner.plan(input("R1", 1, 1, 9, Set.of()))).isEmpty();
        assertThat(planner.plan(input("R1", 1, 1, 2, Set.of("authentication")))).isEmpty();
        assertThat(planner.plan(new ShortCodePathPlanner.Input(
                "HIERARCHICAL_SHADOW", "QUALIFIED", "R1", 1, 1, 2, Set.of(),
                true, true, true, false, true))).isEmpty();
        assertThat(planner.plan(new ShortCodePathPlanner.Input(
                "HIERARCHICAL_CANARY", "QUALIFIED", "R1", 1, 1, 2, Set.of(),
                false, true, true, false, true))).isEmpty();
        assertThat(planner.plan(new ShortCodePathPlanner.Input(
                "HIERARCHICAL_ACTIVE", "UNQUALIFIED", "R1", 1, 1, 2, Set.of(),
                true, true, true, false, true))).isEmpty();
    }

    private static ShortCodePathPlanner.Input input(String risk, int modules, int domains, int files,
                                                    Set<String> impacts) {
        return new ShortCodePathPlanner.Input("HIERARCHICAL_ACTIVE", "QUALIFIED", risk,
                modules, domains, files, impacts, true, true, true, false, true);
    }

    private static Path fixturePath() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : java.util.List.of(
                workingDirectory.resolve("resources/multiagents/evaluations/short-path-cases-v1.json"),
                workingDirectory.resolve("../../resources/multiagents/evaluations/short-path-cases-v1.json")
                        .normalize())) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot find short-path evaluation fixtures");
    }
}
